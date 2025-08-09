package org.veoow.node;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import lombok.Getter;
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
public class LightNodeService {

  private List<BlockHeader> knownBlockHeaders;
  private List<Transaction> knowMemPool;

  public void syncHeadersFromFullNode(List<BlockHeader> headers) {
    this.knownBlockHeaders = headers;
    System.out.println("🔄 Light Node sync headers.");
  }

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

    System.out.println("🔄 Light Node sync mempool (via gRPC).");
    for (Transaction transaction : knowMemPool) {
      System.out.println(transaction);
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
            .collect(Collectors.toList());

    System.out.println("🔄 Light Node sync headers (via gRPC).");
    printHeaders();
  }

  public void printHeaders() {
    System.out.println("📦 Headers known for the Light Node:");
    if (knownBlockHeaders == null || knownBlockHeaders.isEmpty()) {
      System.out.println("None header known.");
      return;
    }
    for (BlockHeader header : knownBlockHeaders) {
      System.out.println(header);
    }
  }

  public void mineMempool(String fullNodeHost, int fullNodePort, int difficulty) {
    if (knowMemPool == null || knowMemPool.isEmpty()) {
      System.out.println("⛔️ Mempool vazia. Nada para minerar.");
      return;
    }

    if (knownBlockHeaders == null || knownBlockHeaders.isEmpty()) {
      System.out.println("⚠️ Nenhum header conhecido. Não é possível minerar.");
      return;
    }

    try {
      while (!knowMemPool.isEmpty()) {
        String previousHash = knownBlockHeaders.get(knownBlockHeaders.size() - 1).hash();
        Transaction tx = knowMemPool.get(0);
        Block block = new Block(previousHash, List.of(tx), difficulty);
        System.out.println("⛏️ Mining...");
        block.mineBlock();
        System.out.println("✅ Block Mined: " + block.getHash());

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(fullNodeHost, fullNodePort)
                .usePlaintext()
                .build();

        BlockchainServiceGrpc.BlockchainServiceBlockingStub stub =
                BlockchainServiceGrpc.newBlockingStub(channel);

        org.veoow.grpc.Block grpcBlock = convertToGrpcBlock(block);
        BlockValidationResponse response = stub.submitMinedBlock(grpcBlock);

        System.out.println("📤 Block sent to the FullNode → " + response.getMessage());

        if (response.getValid()) {
          var blockHeaders = stub.getBlockHeaders(Empty.newBuilder().build());
          var mempool = stub.getMempool(Empty.newBuilder().build());

          syncHeadersFromGrpc(blockHeaders);
          syncMemPool(mempool);
        }

        channel.shutdown();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private org.veoow.grpc.Block convertToGrpcBlock(Block block) {
    var grpcBlockBuilder = org.veoow.grpc.Block.newBuilder()
          .setHash(block.getHash())
          .setPreviousHash(block.getPreviousHash())
          .setDifficulty(block.getDifficulty())
          .setNonce(block.getNonce())
          .setTimestamp(block.getTimestamp());

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
