package org.veoow.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.veoow.utils.HashGenerator;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
  private String transactionId;
  private String sender;
  private String receiver;
  private BigDecimal amount;
  private byte[] signature;

  public Transaction(String sender, String receiver, BigDecimal amount, String sig) {
    this.sender = sender;
    this.receiver = receiver;
    this.amount = amount;
    this.transactionId = HashGenerator.generateSha256Key(sender + amount + System.currentTimeMillis(), receiver);
    this.signature = Base64.getDecoder().decode(sig);
  }

  @Override
  public String toString() {
    return sender + "->" + receiver + ": " + amount;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Transaction that = (Transaction) o;
    return Objects.equals(transactionId, that.transactionId) &&
          Objects.equals(sender, that.sender) &&
          Objects.equals(receiver, that.receiver) &&
          Objects.equals(amount, that.amount) &&
          Arrays.equals(signature, that.signature);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(transactionId, sender, receiver, amount);
    result = 31 * result + Arrays.hashCode(signature);
    return result;
  }
}
