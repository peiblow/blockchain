package org.veoow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.veoow.utils.HashGenerator;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class Transaction {
  private String transactionId;
  private String sender;
  private String receiver;
  private BigDecimal amount;
  private byte[] signature;

  public Transaction(String sender, String receiver, BigDecimal amount) {
    this.sender = sender;
    this.receiver = receiver;
    this.amount = amount;
    this.transactionId = HashGenerator.generateSha256Key(sender + amount, receiver);
  }

  @Override
  public String toString() {
    return sender + "->" + receiver + ": " + amount;
  }
}
