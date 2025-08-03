package org.veoow.node;

import lombok.Getter;
import org.veoow.grpc.BlockHeaders;
import org.veoow.grpc.MempoolResponse;
import org.veoow.model.Transaction;
import org.veoow.node.dto.BlockHeader;

import java.math.BigDecimal;
import java.util.Base64;
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
              String fakeSig = Base64.getEncoder().encodeToString("fake-signature".getBytes());
              byte[] signatureBytes = Base64.getDecoder().decode(fakeSig);
              return new Transaction(
                      pbTransaction.getTransactionId(),
                      pbTransaction.getFrom(),
                      pbTransaction.getTo(),
                      BigDecimal.valueOf(pbTransaction.getAmount()),
                      signatureBytes
              );
            }).toList();

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
}
