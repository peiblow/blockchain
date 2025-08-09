package org.veoow;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.veoow.config.BlockchainDatabase;
import org.veoow.grpc.*;
import org.veoow.node.FullNodeService;
import org.veoow.node.LightNodeService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

public class Main {
  public static void main(String[] args) {
    if (args.length == 0) {
      System.out.println("Pleas specify Light or Full node");
      System.exit(1);
    }

    String nodeType = args[0].toLowerCase();

    try {
      Files.createDirectories(Paths.get("data"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    switch (nodeType) {
      case "full" -> runFullNode();
      case "light" -> runLightNode();
      default -> {
        System.out.println("Invalid node type, please use 'full' or 'light' ");
        System.exit(1);
      }
    }
  }

  private static void runFullNode() {
    try (var db = new BlockchainDatabase("data/blockchain")) {
      FullNodeService fullNodeService = new FullNodeService(db);
      Server server = ServerBuilder.forPort(9090)
              .addService(fullNodeService)
              .build();

      server.start();
      System.out.println("FullNode gRPC server started on port 9090");

      ManagedChannel bootstrapChannel = ManagedChannelBuilder
              .forAddress("localhost", 50051)
              .usePlaintext()
              .build();

      BootstrapServiceGrpc.BootstrapServiceBlockingStub bootstrapStub =
              BootstrapServiceGrpc.newBlockingStub(bootstrapChannel);

      NodeInfo nodeInfo = NodeInfo.newBuilder()
              .setId(UUID.randomUUID().toString())
              .setAddress("localhost:9090")
              .build();

      bootstrapStub.registerNode(nodeInfo);
      bootstrapChannel.shutdown();

      server.awaitTermination();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void runLightNode() {
    try {
      var lightNode = new LightNodeService();

      ManagedChannel bootstrapChannel = ManagedChannelBuilder
              .forAddress("localhost", 50051)
              .usePlaintext()
              .build();

      BootstrapServiceGrpc.BootstrapServiceBlockingStub bootstrapStub =
              BootstrapServiceGrpc.newBlockingStub(bootstrapChannel);

      PeerList peerList = bootstrapStub.getPeerList(Empty.newBuilder().build());
      bootstrapChannel.shutdown();

      if (peerList.getPeersCount() == 0) {
        System.out.println("No peer available for sync.");
        return;
      }

      NodeInfo peer =
              peerList.getPeersList().size() > 1
                      ? selectBestPeer(peerList)
                      : peerList.getPeers(0);

      if (peer == null) {
        System.out.println("No peer available for sync.");
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

      System.out.println("LightNode sincronizando com o peer: " + peer.getId());

      var response = stub.getBlockHeaders(Empty.newBuilder().build());
      var mempool = stub.getMempool(Empty.newBuilder().build());

      lightNode.syncHeadersFromGrpc(response);
      lightNode.syncMemPool(mempool);

      lightNode.mineMempool(host, port, 4);

      Thread.currentThread().join();

    } catch (InterruptedException e) {
      e.printStackTrace();
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
        System.out.println("Peer indisponível: " + peer.getAddress());
      }
    }

    return bestPeer;
  }
}
