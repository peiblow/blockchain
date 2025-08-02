package org.veoow.node;

import lombok.Getter;
import org.veoow.config.BlockchainDatabase;
import org.veoow.model.Block;
import org.veoow.model.Transaction;
import org.veoow.node.dto.BlockHeader;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@Getter
public class FullNodeService {
  private final BlockchainDatabase db;
  private final Queue<Transaction> mempool = new ConcurrentLinkedQueue<>();
  private final int difficulty = 4;

  public FullNodeService(BlockchainDatabase db) throws Exception {
    this.db = db;

    if(db.getLastBlock() == null) {
      Block genesisBlock = createGenesisBlock();
      genesisBlock.mineBlock();

      try {
        db.saveBlock(genesisBlock);
        System.out.println("Genesis block created!");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  public void addTransaction(Transaction tx) {
    mempool.add(tx);
    System.out.println("Transação adicionada à mempool: " + tx.getTransactionId());
  }

  public void mineNewBlock() throws Exception {
      List<Transaction> blockTxs = new ArrayList<>();
      while (!mempool.isEmpty() && blockTxs.size() < 5) {
        blockTxs.add(mempool.poll());
      }

      Block lastBlock = db.getLastBlock();
      Block newBlock = new Block(lastBlock.getHash(), blockTxs, difficulty);
      newBlock.mineBlock();

      if (isValidNewBlock(newBlock, lastBlock)) {
        db.saveBlock(newBlock);
        System.out.println("✅ Bloco adicionado à blockchain!");
      } else {
        System.out.println("❌ Bloco inválido.");
      }
  }

  public boolean isValidNewBlock(Block newBlock, Block lastBlock) {
    String target = "0".repeat(newBlock.getDifficulty());
    return newBlock.getHash().startsWith(target) &&
          newBlock.getPreviousHash().equals(lastBlock.getHash());
  }

  public Block getBlockByHash(String hash) throws Exception {
    return db.getBlockByHash(hash);
  }

  public List<BlockHeader> getBlockHeaders() throws Exception {
    return db.getAllBlockHeaders();
  }

  private Block createGenesisBlock() {
    return new Block("0", new ArrayList<>(), difficulty);
  }
}
