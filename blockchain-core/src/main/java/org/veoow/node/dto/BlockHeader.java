package org.veoow.node.dto;

public record BlockHeader(
      String hash,
      String previousHash,
      long timestamp,
      int nonce
) {}
