package org.veoow.node;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.veoow.grpc.*;
import org.veoow.model.Block;
import org.veoow.model.Transaction;
import org.veoow.node.dto.BlockHeader;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Slf4j
public class LightNodeService {

  private List<BlockHeader> knownBlockHeaders;
  private List<Transaction> knowMemPool;

  public void syncMemPool(MempoolResponse grpcMemPool) {
    this.knowMemPool = grpcMemPool.getTransactionsList().stream()
            .map(pbTransaction -> {
              String fakeSig = Base64.getEncoder().encodeToString("sig".getBytes());
              byte[] signatureBytes = Base64.getDecoder().decode(fakeSig);
              return new Transaction(
                      pbTransaction.getTransactionId(),
                      pbTransaction.getFrom(),
                      pbTransaction.getTo(),
                      BigDecimal.valueOf(pbTransaction.getAmount()),
                      signatureBytes
              );
            }).collect(Collectors.toCollection(ArrayList::new));

    log.info("🔄 Light Node sync mempool (via gRPC).");
    for (Transaction transaction : knowMemPool) {
      log.info(transaction.toString());
    }
  }

  public void syncHeadersFromGrpc(BlockHeaders grpcHeaders) {
    this.knownBlockHeaders = grpcHeaders.getHeadersList().stream()
            .map(pbHeader -> new BlockHeader(
                    pbHeader.getHash(),
                    pbHeader.getPreviousHash(),
                    pbHeader.getTimestamp(),
                    pbHeader.getNonce()
            ))
            .sorted(Comparator.comparingLong(BlockHeader::timestamp))
            .toList();

    log.info("🔄 Light Node sync headers (via gRPC).");
    printHeaders();
  }

  public void printHeaders() {
    log.info("📦 Headers known for the Light Node:");
    if (knownBlockHeaders == null || knownBlockHeaders.isEmpty()) {
      log.info("None header known.");
      return;
    }
    for (BlockHeader header : knownBlockHeaders) {
      log.info(header.toString());
    }
  }

  public void mineMempool(String fullNodeHost, int fullNodePort, int difficulty) {
    if (knowMemPool == null || knowMemPool.isEmpty()) {
      log.warn("⛔️ Mempool vazia. Nada para minerar.");
      return;
    }

    if (knownBlockHeaders == null || knownBlockHeaders.isEmpty()) {
      log.warn("⚠️ Nenhum header conhecido. Não é possível minerar.");
      return;
    }

    try {
      while (!knowMemPool.isEmpty()) {
        String previousHash = knownBlockHeaders.get(knownBlockHeaders.size() - 1).hash();
        Transaction tx = knowMemPool.get(0);
        Block block = new Block(previousHash, List.of(tx), difficulty);
        log.info("⛏️ Mining...");
        long miningTimeMs = block.mineBlock();
        log.info("✅ Block Mined: {}", block.getHash());

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(fullNodeHost, fullNodePort)
                .usePlaintext()
                .build();

        BlockchainServiceGrpc.BlockchainServiceBlockingStub stub =
                BlockchainServiceGrpc.newBlockingStub(channel);

        org.veoow.grpc.Block grpcBlock = convertToGrpcBlock(block, miningTimeMs);
        BlockValidationResponse response = stub.submitMinedBlock(grpcBlock);

        log.info("📤 Block sent to the FullNode → {}", response.getMessage());

        if (response.getValid()) {
          var blockHeaders = stub.getBlockHeaders(Empty.newBuilder().build());
          var mempool = stub.getMempool(Empty.newBuilder().build());

          syncHeadersFromGrpc(blockHeaders);
          syncMemPool(mempool);
        }

        channel.shutdown();
      }
    } catch (Exception e) {
      log.error("Error to mine blocks in MemPool: {}" , e.getMessage());
    }
  }

  private org.veoow.grpc.Block convertToGrpcBlock(Block block, long miningTimeMs) {
    var grpcBlockBuilder = org.veoow.grpc.Block.newBuilder()
            .setHash(block.getHash())
            .setPreviousHash(block.getPreviousHash())
            .setDifficulty(block.getDifficulty())
            .setNonce(block.getNonce())
            .setTimestamp(block.getTimestamp())
            .setMiningTimeMs(miningTimeMs);

    for (Transaction tx : block.getTransactions()) {
      org.veoow.grpc.Transaction grpcTx = org.veoow.grpc.Transaction.newBuilder()
            .setTransactionId(tx.getTransactionId())
            .setFrom(tx.getSender())
            .setTo(tx.getReceiver())
            .setAmount(tx.getAmount().doubleValue())
            .setSignature(Base64.getEncoder().encodeToString(tx.getSignature()))
            .build();

      grpcBlockBuilder.addTransactions(grpcTx);
    }

    return grpcBlockBuilder.build();
  }
}
