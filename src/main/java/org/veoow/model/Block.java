package org.veoow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.veoow.utils.HashGenerator;

import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Block {
  private String hash;
  private String previousHash;
  private List<Transaction> transactions;
  private long timestamp;
  private int nonce;
  private int difficulty;

  public Block(String previousHash, List<Transaction> transactions, int difficulty) {
    this.previousHash = previousHash;
    this.transactions = transactions;
    this.difficulty = difficulty;
    this.timestamp = System.currentTimeMillis();
    this.nonce = 0;
    this.hash = calculateHash();
  }

  public String calculateHash() {
    String data = transactions.toString() + timestamp + nonce;
    return HashGenerator.generateSha256Key(data, previousHash);
  }

  public void mineBlock() {
    String target = new String(new char[difficulty]).replace('\0', '0');

    while (!hash.substring(0, difficulty).equals(target)) {
      nonce++;
      hash = calculateHash();
    }

    System.out.println("⛏️ Bloco minerado com sucesso! Hash: " + hash);
  }

  public String getBlockHeader() {
    return hash + "-" + previousHash + "-" + timestamp;
  }
}
