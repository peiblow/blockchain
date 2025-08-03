package org.veoow;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.veoow.service.BootstrapService;

public class Main {
    public static void main(String[] args) {
        try {
            int port = 50051;
            Server server = ServerBuilder.forPort(port)
                    .addService(new BootstrapService())
                    .build();

            server.start();
            System.out.println("🚀 Bootstrap node rodando na porta " + port);
            server.awaitTermination();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}