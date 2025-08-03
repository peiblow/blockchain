package org.veoow;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.veoow.config.BlockchainDatabase;
import org.veoow.grpc.BlockchainServiceGrpc;
import org.veoow.grpc.Empty;
import org.veoow.node.FullNodeService;
import org.veoow.node.LightNodeService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

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
      server.awaitTermination();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void runLightNode() {
    try {
      var lightNode = new LightNodeService();

      ManagedChannel channel = ManagedChannelBuilder
              .forAddress("localhost", 9090)
              .defaultLoadBalancingPolicy("round_robin")
              .usePlaintext()
              .build();

      BlockchainServiceGrpc.BlockchainServiceBlockingStub stub = BlockchainServiceGrpc.newBlockingStub(channel);

      System.out.println("LightNode running and waiting synchronization...");

      var response = stub.getBlockHeaders(Empty.newBuilder().build());
      var mempool = stub.getMempool(Empty.newBuilder().build());

      lightNode.syncHeadersFromGrpc(response);
      lightNode.syncMemPool(mempool);

      Thread.currentThread().join();

    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

}
