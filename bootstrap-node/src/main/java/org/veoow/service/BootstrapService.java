package org.veoow.service;

import io.grpc.stub.StreamObserver;
import org.veoow.grpc.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class BootstrapService extends BootstrapServiceGrpc.BootstrapServiceImplBase {
    private final List<NodeInfo> peers = new CopyOnWriteArrayList<>();

    public void registerNode(NodeInfo request, StreamObserver<RegisterResponse> responseObserver) {
        boolean alreadyRegistered = peers.stream()
                .anyMatch(p -> p.getId().equals(request.getId()) || p.getAddress().equals(request.getAddress()));

        if (!alreadyRegistered) {
            peers.add(request);
            System.out.println("🟢 New node registered: " + request.getAddress());
        } else {
            System.out.println("⚠️ Node already registered: " + request.getAddress());
        }

        RegisterResponse response = RegisterResponse.newBuilder()
                .setSuccess(true)
                .setMessage("Node registered with success.")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    public void getPeerList(Empty request, StreamObserver<PeerList> responseObserver) {
        PeerList response = PeerList.newBuilder()
                .addAllPeers(peers)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
