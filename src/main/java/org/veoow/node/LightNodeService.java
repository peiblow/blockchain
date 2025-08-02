package org.veoow.node;

import lombok.Getter;
import org.veoow.node.dto.BlockHeader;

import java.util.List;

@Getter
public class LightNodeService {

  private List<BlockHeader> knownBlockHeaders;

  public void syncHeadersFromFullNode(List<BlockHeader> headers) {
    this.knownBlockHeaders = headers;
    System.out.println("🔄 Light Node sincronizou headers.");
  }

  public void printHeaders() {
    System.out.println("📦 Headers conhecidos pelo Light Node:");
    for (BlockHeader header : knownBlockHeaders) {
      System.out.println(header);
    }
  }
}
