package org.veoow;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import lombok.extern.slf4j.Slf4j;
import org.veoow.config.BlockchainDatabase;
import org.veoow.grpc.*;
import org.veoow.model.Transaction;
import org.veoow.node.FullNodeService;
import org.veoow.node.LightNodeService;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
public class Main {
  public static void main(String[] args) {
    if (args.length == 0) {
      log.warn("Pleas specify Light or Full node");
      System.exit(1);
    }

    String nodeType = args[0].toLowerCase();
    String hostPeer = args.length > 1 ?
          args[1].toLowerCase()
          : null;

    try {
      Files.createDirectories(Paths.get("data"));
    } catch (IOException e) {
      log.error("Error to create database folder: {}", e.getMessage());
    }

    switch (nodeType) {
      case "full" -> runFullNode(hostPeer);
      case "light" -> runLightNode();
      default -> {
        log.warn("Invalid node type, please use 'full' or 'light' ");
        System.exit(1);
      }
    }
  }

  private static void runFullNode(String hostPeer) {
    try (var db = new BlockchainDatabase("data/blockchain")) {
      FullNodeService fullNodeService = new FullNodeService(db);
      Server server = ServerBuilder.forPort(9090)
              .addService(fullNodeService)
              .build();

      server.start();
      log.info("FullNode gRPC server started on port 9090");

      ManagedChannel bootstrapChannel = ManagedChannelBuilder
              .forAddress("bootstrap", 50051)
              .usePlaintext()
              .build();

      BootstrapServiceGrpc.BootstrapServiceBlockingStub bootstrapStub =
              BootstrapServiceGrpc.newBlockingStub(bootstrapChannel);

      NodeInfo nodeInfo = NodeInfo.newBuilder()
              .setId(UUID.randomUUID().toString())
              .setAddress(hostPeer + ":9090")
              .build();

      bootstrapStub.registerNode(nodeInfo);
      bootstrapChannel.shutdown();

      server.awaitTermination();
    } catch (Exception e) {
      log.error("Error to run fullNode: {}", e.getMessage());
    }
  }

  private static void runLightNode() {
    try {
      var lightNode = new LightNodeService();

      ManagedChannel bootstrapChannel = ManagedChannelBuilder
              .forAddress("bootstrap", 50051)
              .usePlaintext()
              .build();

      BootstrapServiceGrpc.BootstrapServiceBlockingStub bootstrapStub =
              BootstrapServiceGrpc.newBlockingStub(bootstrapChannel);

      PeerList peerList = bootstrapStub.getPeerList(Empty.newBuilder().build());
      bootstrapChannel.shutdown();

      if (peerList.getPeersCount() == 0) {
        log.info("No peer available for sync.");
        return;
      }

      NodeInfo peer =
              peerList.getPeersList().size() > 1
                      ? selectBestPeer(peerList)
                      : peerList.getPeers(0);

      if (peer == null) {
        log.info("No peer available for sync.");
        return;
      }

      String[] hostPort = peer.getAddress().split(":");
      String host = hostPort[0];
      int port = Integer.parseInt(hostPort[1]);

      ManagedChannel channel = ManagedChannelBuilder
              .forAddress(host, port)
              .usePlaintext()
              .build();

      BlockchainServiceGrpc.BlockchainServiceBlockingStub stub = BlockchainServiceGrpc.newBlockingStub(channel);

      log.info("🔄 LightNode syncing with peer: {}", peer.getId());

      var response = stub.getBlockHeaders(Empty.newBuilder().build());
      var mempool = stub.getMempool(Empty.newBuilder().build());

      lightNode.syncHeadersFromGrpc(response);

      lightNode.syncMemPool(mempool);
      lightNode.mineMempool(host, port, 4);

      Thread.currentThread().join();

    } catch (Exception e) {
      log.error("Error to run lightNode: {}", e.getMessage());
    }
  }

  private static NodeInfo selectBestPeer(PeerList peerList) {
    List<NodeInfo> peers = peerList.getPeersList();
    NodeInfo bestPeer = null;
    long bestLatency = Long.MAX_VALUE;

    for (NodeInfo peer : peers) {
      try {
        String[] hostPort = peer.getAddress().split(":");
        String host = hostPort[0];
        int port = Integer.parseInt(hostPort[1]);

        long start = System.nanoTime();

        try (Socket socket = new Socket()) {
          socket.connect(new InetSocketAddress(host, port), 300);
        }

        long latency = System.nanoTime() - start;
        if (latency < bestLatency) {
          bestLatency = latency;
          bestPeer = peer;
        }

      } catch (Exception e) {
        log.error("Unavailable Peer: {}", peer.getAddress());
      }
    }

    return bestPeer;
  }
}
