package org.veoow.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.veoow.model.Block;
import org.veoow.node.dto.BlockHeader;
import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
public class BlockchainDatabase implements AutoCloseable {
  private static final String LAST_BLOCK_KEY = "LAST_BLOCK_HASH";

  private final RocksDB db;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public BlockchainDatabase(String dbPath) throws RocksDBException {
    RocksDB.loadLibrary();
    Options options = new Options().setCreateIfMissing(true);
    db = RocksDB.open(options, dbPath);
  }

  public void saveBlock(Block block) throws Exception {
    byte[] key = block.getHash().getBytes(StandardCharsets.UTF_8);
    byte[] value = objectMapper.writeValueAsBytes(block);

    db.put(key, value);
    db.put("lastBlockHash".getBytes(), key);
  }

  public void putBlock(String blockHash, byte[] blockData) throws RocksDBException {
    db.put(blockHash.getBytes(), blockData);
  }

  public byte[] getBlock(String blockHash) throws RocksDBException {
    return db.get(blockHash.getBytes());
  }

  public Block getBlockByHash(String blockHash) throws Exception {
    byte[] data = db.get(blockHash.getBytes());
    if (data == null) return null;
    return objectMapper.readValue(data, Block.class);
  }

  public List<BlockHeader> getAllBlockHeaders() {
    List<BlockHeader> headers = new ArrayList<>();

    try (var iterator = db.newIterator()) {
      for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
        try {
          String json = new String(iterator.value(), StandardCharsets.UTF_8);
          System.out.println("🔍 Processando bloco JSON: " + json);

          Block block = objectMapper.readValue(json, Block.class);

          if (block.getHash() != null && block.getPreviousHash() != null) {
            headers.add(new BlockHeader(
                  block.getHash(),
                  block.getPreviousHash(),
                  block.getTimestamp(),
                  block.getNonce()
            ));
          }
        } catch (Exception e) {
          System.err.println("Erro ao processar bloco: " + e.getMessage());
        }
      }
    }

    return headers;
  }

  public int getBlockchainSize() {
    int count = 0;

    try (var iterator = db.newIterator()) {
      for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
        byte[] key = iterator.key();

        String keyStr = new String(key, StandardCharsets.UTF_8);
        if ("lastBlockHash".equals(keyStr)) {
          continue;
        }

        try {
          Block block = objectMapper.readValue(iterator.value(), Block.class);
          if (block.getHash() != null && block.getPreviousHash() != null) {
            count++;
          }
        } catch (Exception ignored) {}
      }
    }

    return count;
  }

  public List<Block> getAllBlocks() throws Exception {
    List<Block> blocks = new ArrayList<>();

    try (var iterator = db.newIterator()) {
      for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
        String key = new String(iterator.key(), StandardCharsets.UTF_8);
        if ("lastBlockHash".equals(key)) {
          continue;
        }

        try {
          Block block = objectMapper.readValue(iterator.value(), Block.class);
          if (block.getHash() != null && block.getPreviousHash() != null) {
            blocks.add(block);
          }
        } catch (Exception e) {
          System.err.println("Erro ao desserializar bloco: " + e.getMessage());
        }
      }
    }

    blocks.sort(Comparator.comparingLong(Block::getTimestamp));

    return blocks;
  }

  public void replaceChain(List<Block> newChain) throws Exception {
    if (newChain == null || newChain.isEmpty()) {
      throw new IllegalArgumentException("A nova cadeia não pode ser vazia");
    }

    try (WriteBatch batch = new WriteBatch()) {
      try (RocksIterator iterator = db.newIterator()) {
        for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
          batch.delete(iterator.key());
        }
      }
      db.write(new WriteOptions(), batch);
    }

    for (Block block : newChain) {
      saveBlock(block);
    }

    Block lastBlock = newChain.get(newChain.size() - 1);
    db.put("lastBlockHash".getBytes(), lastBlock.getHash().getBytes(StandardCharsets.UTF_8));

    System.out.println("Blockchain substituída com sucesso. Novo tamanho: " + newChain.size());
  }

  public Block getLastBlock() throws Exception {
    byte[] lastHash = db.get("lastBlockHash".getBytes());
    if (lastHash == null) return null;

    byte[] blockBytes = db.get(lastHash);
    return objectMapper.readValue(blockBytes, Block.class);
  }

  public void close() {
    db.close();
  }
}

