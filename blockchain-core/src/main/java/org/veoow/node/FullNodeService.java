package org.veoow.node;

import io.grpc.stub.StreamObserver;
import lombok.Getter;
import org.veoow.config.BlockchainDatabase;
import org.veoow.grpc.*;
import org.veoow.model.Block;
import org.veoow.model.Transaction;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Getter
public class FullNodeService extends BlockchainServiceGrpc.BlockchainServiceImplBase {
  private final BlockchainDatabase db;
  private final Queue<org.veoow.grpc.Transaction> mempool = new ConcurrentLinkedQueue<>();
  private final int difficulty = 4;

  public FullNodeService(BlockchainDatabase db) throws Exception {
    this.db = db;

    if(db.getLastBlock() == null) {
      Block genesisBlock = createGenesisBlock();
      genesisBlock.mineBlock();

      try {
        db.saveBlock(genesisBlock);
        System.out.println("Genesis block created!");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public void addTransaction(org.veoow.grpc.Transaction request, StreamObserver<TransactionResponse> responseObserver) {
    mempool.add(request);
    System.out.println("Transaction added at mempool: " + request.getTransactionId());

    TransactionResponse response = TransactionResponse.newBuilder()
            .setAccepted(true)
            .setMessage("Transaction Received")
            .build();

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  public void getMempool(Empty request, StreamObserver<MempoolResponse> responseObserver) {
    MempoolResponse.Builder responseBuilder = MempoolResponse.newBuilder();
    responseBuilder.addAllTransactions(mempool);
    responseObserver.onNext(responseBuilder.build());
    responseObserver.onCompleted();
  }

  public void saveNewBlock() throws Exception {
      Block newBlock = new Block();
      Block lastBlock = db.getLastBlock();

      if (isValidNewBlock(newBlock, lastBlock)) {
        db.saveBlock(newBlock);
        System.out.println("✅ Bloco adicionado à blockchain!");
      } else {
        System.out.println("❌ Bloco inválido.");
      }
  }

  public boolean isValidNewBlock(Block newBlock, Block lastBlock) {
    String target = "0".repeat(newBlock.getDifficulty());
    return newBlock.getHash().startsWith(target) &&
          newBlock.getPreviousHash().equals(lastBlock.getHash());
  }

  public Block getBlockByHash(String hash) throws Exception {
    return db.getBlockByHash(hash);
  }

  public void getBlockHeaders(Empty request, StreamObserver<org.veoow.grpc.BlockHeaders> responseObserver) {
    org.veoow.grpc.BlockHeaders.Builder responseBuilder = org.veoow.grpc.BlockHeaders.newBuilder();

    try {
      List<org.veoow.node.dto.BlockHeader> headers = db.getAllBlockHeaders();

      for (org.veoow.node.dto.BlockHeader header : headers) {
        org.veoow.grpc.BlockHeader grpcHeader = org.veoow.grpc.BlockHeader.newBuilder()
                .setHash(header.hash())
                .setPreviousHash(header.previousHash())
                .setTimestamp(header.timestamp())
                .setNonce(header.nonce())
                .build();

        responseBuilder.addHeaders(grpcHeader);
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  public BlockValidationResponse submitMinedBlock(org.veoow.grpc.Block grpcBlock) throws Exception {
    Block newBlock = convertFromGrpcBlock(grpcBlock);
    Block lastBlock = db.getLastBlock();

    if (!isValidNewBlock(newBlock, lastBlock)) {
      return BlockValidationResponse.newBuilder()
              .setValid(false)
              .setMessage("Invalid Block, now you'll be penalized :(")
              .build();
    }

    try {
      db.saveBlock(newBlock);
      mempool.removeAll(newBlock.getTransactions());
      return BlockValidationResponse.newBuilder()
              .setValid(true)
              .setMessage("Bloco aceito e salvo")
              .build();
    } catch (Exception e) {
      return BlockValidationResponse.newBuilder()
              .setValid(false)
              .setMessage("Erro ao salvar bloco: " + e.getMessage())
              .build();
    }
  }

  private Block createGenesisBlock() {
    return new Block("0", new ArrayList<>(), difficulty);
  }

  private Block convertFromGrpcBlock(org.veoow.grpc.Block grpcBlock) {
    List<Transaction> transactions = grpcBlock.getTransactionsList().stream()
            .map(this::convertFromGrpcTransaction)
            .collect(Collectors.toList());

    return new Block(
            grpcBlock.getHash(),
            grpcBlock.getPreviousHash(),
            transactions,
            grpcBlock.getTimestamp(),
            grpcBlock.getDifficulty(),
            grpcBlock.getNonce()
    );
  }

  private Transaction convertFromGrpcTransaction(org.veoow.grpc.Transaction grpcTx) {
    byte[] signatureBytes = Base64.getDecoder().decode(grpcTx.getSignature());
    return new Transaction(
            grpcTx.getTransactionId(),
            grpcTx.getFrom(),
            grpcTx.getTo(),
            BigDecimal.valueOf(grpcTx.getAmount()),
            signatureBytes
    );
  }

  private org.veoow.grpc.Transaction convertToGrpcTransaction(Transaction tx) {
    return org.veoow.grpc.Transaction.newBuilder()
            .setTransactionId(tx.getTransactionId())
            .setFrom(tx.getSender())
            .setTo(tx.getReceiver())
            .setAmount(tx.getAmount().doubleValue())
            .setSignature(Base64.getEncoder().encodeToString(tx.getSignature()))
            .build();
  }
}
