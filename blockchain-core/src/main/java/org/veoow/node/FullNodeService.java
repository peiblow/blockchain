package org.veoow.node;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.veoow.config.BlockchainDatabase;
import org.veoow.grpc.*;
import org.veoow.model.Block;
import org.veoow.model.Transaction;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Slf4j
@Getter
public class FullNodeService extends BlockchainServiceGrpc.BlockchainServiceImplBase {
  private final BlockchainDatabase db;
  private final Queue<Transaction> mempool = new ConcurrentLinkedQueue<>();
  private final HashMap<String, Block> orphanBlocks = new HashMap<>();

  private int difficulty = 4;
  private int blocksProcessedCounter = 0;
  private List<Long> avgMiningTimeMs = new ArrayList<>();

  public FullNodeService(BlockchainDatabase db) throws Exception {
    this.db = db;

    if(db.getLastBlock() == null) {
      Block genesisBlock = createGenesisBlock();
      genesisBlock.mineBlock();

      try {
        db.saveBlock(genesisBlock);
        log.info("Genesis block created!");
      } catch (Exception e) {
        log.error("Error to create the genesis block: {}", e.getMessage());
      }
    }
  }

  // GRPC Controller Implementation -------------------------------------------------------------------------
  @Override
  public void addTransaction(org.veoow.grpc.Transaction request, StreamObserver<TransactionResponse> responseObserver) {
    Transaction newTransactionBlock = new Transaction(
          request.getFrom(),
          request.getTo(),
          BigDecimal.valueOf(request.getAmount()),
          request.getSignature()
    );

    mempool.add(newTransactionBlock);
    log.info("Transaction added at mempool: {}", newTransactionBlock.getTransactionId());

    TransactionResponse response = TransactionResponse.newBuilder()
            .setAccepted(true)
            .setMessage("Transaction Received")
            .build();

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  public void getMempool(Empty request, StreamObserver<MempoolResponse> responseObserver) {
    MempoolResponse.Builder responseBuilder = MempoolResponse.newBuilder();
    responseBuilder.addAllTransactions(
          mempool.stream()
                .map(this::convertToGrpcTransaction)
                .toList()
    );
    responseObserver.onNext(responseBuilder.build());
    responseObserver.onCompleted();
  }

  public Block getBlockByHash(String hash) throws Exception {
    return db.getBlockByHash(hash);
  }

  @Override
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

  @Override
  public void submitMinedBlock(org.veoow.grpc.Block request, StreamObserver<BlockValidationResponse> responseObserver) {
    try {
      log.info("### NEW BLOCK RECEIVED!!");
      Block newBlock = convertFromGrpcBlock(request);
      Block lastBlock = db.getLastBlock();
      String sourcePeer = request.getPeerAddress();

      if (!isValidNewBlock(newBlock, lastBlock)) {
        if ("full".equalsIgnoreCase(request.getSourceType())) {
          boolean chainUpdated = handleChainSelection(sourcePeer);
          if (!chainUpdated) {
            responseObserver.onNext(BlockValidationResponse.newBuilder()
                  .setValid(false)
                  .setMessage("❌ Invalid Block or shorter chain.")
                  .build());
            responseObserver.onCompleted();
            return;
          }
        } else {
          responseObserver.onNext(BlockValidationResponse.newBuilder()
                .setValid(false)
                .setMessage("❌ Invalid Block from LightNode.")
                .build());
          responseObserver.onCompleted();
          return;
        }
      }

      db.saveBlock(newBlock);
      newBlock.getTransactions().forEach(transaction -> mempool.removeIf(tx -> tx.getTransactionId().equals(transaction.getTransactionId())));

      log.info("📤 New block added in the Blockchain: " + getBlockByHash(newBlock.getHash()));

      blocksProcessedCounter += 1;
      avgMiningTimeMs.add(request.getMiningTimeMs());

      if (!request.getSourceType().equals("full")) {
        propagateBlockToTrustedPeers(newBlock);
      }

      if (blocksProcessedCounter >= 100) {
        increaseDifficulty();
      }

      responseObserver.onNext(BlockValidationResponse.newBuilder()
            .setValid(true)
            .setMessage("Block accepted, saved and propagated!")
            .build());
      responseObserver.onCompleted();

    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void getFullBlockchain(Empty request, StreamObserver<Blockchain> responseObserver) {
    try {
      List<Block> blocks = db.getAllBlocks();

      Blockchain.Builder blockchainBuilder = Blockchain.newBuilder();

      for (Block block : blocks) {
        blockchainBuilder.addBlocks(convertToGrpcBlock(block));
      }

      responseObserver.onNext(blockchainBuilder.build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  @Override
  public void getBlockchainLength(Empty request, StreamObserver<ChainLengthResponse> responseObserver) {
    try {
      int length = db.getBlockchainSize();

      ChainLengthResponse response = ChainLengthResponse.newBuilder()
            .setLength(length)
            .build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
    }
  }

  // Internal Methods -------------------------------------------------------------------------

  private Block createGenesisBlock() {
    return new Block("0", new ArrayList<>(), difficulty);
  }

  private boolean isValidNewBlock(Block newBlock, Block lastBlock) throws Exception {
    String target = "0".repeat(difficulty);

    Block parentBlock = db.getBlockByHash(newBlock.getPreviousHash());

    if (parentBlock == null) {
      orphanBlocks.put(newBlock.getPreviousHash(), newBlock);
      log.warn("\uD83D\uDED1 Orphan block stored: {}", newBlock.getHash());
      return true;
    }

    return newBlock.getHash().startsWith(target) &&
          newBlock.getPreviousHash().equals(lastBlock.getHash());
  }

  private int requestBlockchainLengthFromPeer(String peerAddress) {
    String[] hostPort = peerAddress.split(":");
    String host = hostPort[0];
    int port = Integer.parseInt(hostPort[1]);

    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
          .usePlaintext()
          .build();

    BlockchainServiceGrpc.BlockchainServiceBlockingStub stub = BlockchainServiceGrpc.newBlockingStub(channel);

    try {
      ChainLengthResponse response = stub.getBlockchainLength(Empty.newBuilder().build());
      return response.getLength();
    } catch (Exception e) {
      log.error("Erro ao requisitar tamanho da cadeia do peer {}: {}", peerAddress, e.getMessage());
      return -1;
    } finally {
      channel.shutdown();
    }
  }

  private List<Block> requestFullChainFromPeer(String peerAddress) {
    String[] hostPort = peerAddress.split(":");
    String host = hostPort[0];
    int port = Integer.parseInt(hostPort[1]);

    ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
          .usePlaintext()
          .build();

    BlockchainServiceGrpc.BlockchainServiceBlockingStub stub = BlockchainServiceGrpc.newBlockingStub(channel);

    try {
      Blockchain grpcBlockchain = stub.getFullBlockchain(Empty.newBuilder().build());

      return grpcBlockchain.getBlocksList().stream()
            .map(this::convertFromGrpcBlock)
            .toList();
    } catch (Exception e) {
      log.error("Erro ao requisitar cadeia completa do peer {}: {}", peerAddress, e.getMessage());
      return List.of();
    } finally {
      channel.shutdown();
    }
  }

  private boolean handleChainSelection(String peerAddress) {
    log.info("### CHECKING CHAIN!!");
    int remoteChainLength = requestBlockchainLengthFromPeer(peerAddress);
    int localChainLength = db.getBlockchainSize();

    if (remoteChainLength <= localChainLength) {
      log.info("ℹ️ Cadeia do peer " + peerAddress + " não é maior, não substituindo.");
      return false;
    }

    List<Block> remoteChain = requestFullChainFromPeer(peerAddress);

    if (remoteChain != null && remoteChain.size() > localChainLength) {
      try {
        db.replaceChain(remoteChain);
        log.info("🔄 Cadeia local substituída pela cadeia maior do peer " + peerAddress);

        return true;
      } catch (Exception e) {
        log.error("Erro ao substituir cadeia local: " + e.getMessage());
        return false;
      }
    } else {
      log.info("ℹ️ Cadeia recebida é inválida ou menor que a local");
      return false;
    }
  }

  private Block convertFromGrpcBlock(org.veoow.grpc.Block grpcBlock) {
    List<Transaction> transactions = grpcBlock.getTransactionsList().stream()
            .map(this::convertFromGrpcTransaction)
          .toList();

    return new Block(
            grpcBlock.getHash(),
            grpcBlock.getPreviousHash(),
            transactions,
            grpcBlock.getTimestamp(),
            grpcBlock.getNonce(),
            grpcBlock.getDifficulty()
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

  private org.veoow.grpc.Block convertToGrpcBlock(Block block) {
    org.veoow.grpc.Block.Builder builder = org.veoow.grpc.Block.newBuilder()
          .setHash(block.getHash())
          .setPreviousHash(block.getPreviousHash())
          .setTimestamp(block.getTimestamp())
          .setNonce(block.getNonce())
          .setDifficulty(block.getDifficulty());

    for (Transaction tx : block.getTransactions()) {
      builder.addTransactions(convertToGrpcTransaction(tx));
    }

    return builder.build();
  }

  private void propagateBlockToTrustedPeers(Block block) {
    log.info("## Propagation Started!! ##");
    ManagedChannel bootstrapChannel = ManagedChannelBuilder
          .forAddress("bootstrap", 50051)
          .usePlaintext()
          .build();

    BootstrapServiceGrpc.BootstrapServiceBlockingStub bootstrapStub =
          BootstrapServiceGrpc.newBlockingStub(bootstrapChannel);

    PeerList peerList = bootstrapStub.getPeerList(Empty.newBuilder().build());
    List<NodeInfo> trustedPeers = peerList.getPeersList();

    bootstrapChannel.shutdown();

    for (NodeInfo peer : trustedPeers) {
      try {
        String[] hostPort = peer.getAddress().split(":");
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        ManagedChannel channel = ManagedChannelBuilder
              .forAddress(host, port)
              .usePlaintext()
              .build();

        BlockchainServiceGrpc.BlockchainServiceBlockingStub stub =
              BlockchainServiceGrpc.newBlockingStub(channel);

        if (!isMyself(host, port)) {
          org.veoow.grpc.Block newPropagationBlock = org.veoow.grpc.Block.newBuilder()
                .setHash(block.getHash())
                .setPreviousHash(block.getPreviousHash())
                .setTimestamp(block.getTimestamp())
                .setNonce(block.getNonce())
                .setDifficulty(block.getDifficulty())
                .setSourceType("full")
                .build();

          stub.submitMinedBlock(newPropagationBlock);
          log.info("✅ Block propagated for peer {}", peer.getAddress());
        }

        channel.shutdown();

      } catch (Exception e) {
        log.error("❌ Failed in propagate for peer: {}: {}", peer.getAddress(), e.getMessage());
      }
    }
  }

  private void increaseDifficulty() {
    int avgTimeTarget = 210;
    double avgTime = avgMiningTimeMs.stream()
            .mapToInt(Long::intValue)
            .average()
            .orElse(0);

    double activeNodes = List.of("localhost:9090").size();
    double activeNodesTarget = 20;

    double a = 1;
    double b = 0.5;

    var newComplexity = difficulty * Math.pow((avgTimeTarget/avgTime), a) * Math.pow((activeNodes/activeNodesTarget), b);

    difficulty = Math.max(Math.toIntExact(Math.round(newComplexity)), 4);
    log.info("New difficulty is: {}", difficulty);

    avgMiningTimeMs = new ArrayList<>();
    blocksProcessedCounter = 0;
  }

  private boolean isMyself(String host, int port) {
    return host.equals("full-node-b") && port == 9090;
  }
}
