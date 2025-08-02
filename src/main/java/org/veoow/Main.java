package org.veoow;

import org.veoow.config.BlockchainDatabase;
import org.veoow.model.Transaction;
import org.veoow.node.FullNodeService;
import org.veoow.node.LightNodeService;

import java.math.BigDecimal;

public class Main {
  public static void main(String[] args) {
    if (args.length == 0) {
      System.out.println("Por favor, informe o tipo do node: full ou light");
      System.exit(1);
    }

    String nodeType = args[0].toLowerCase();

    switch (nodeType) {
      case "full" -> runFullNode();
      case "light" -> runLightNode();
      default -> {
        System.out.println("Tipo de node inválido! Use 'full' ou 'light'");
        System.exit(1);
      }
    }
  }

  private static void runFullNode() {
    try (var db = new BlockchainDatabase("data/blockchain")) {
      var fullNode = new FullNodeService(db);

      // Criar transações simuladas
      for (int i = 1; i <= 10; i++) {
        var tx = new Transaction("userA", "userB", BigDecimal.valueOf(i * 10));
        fullNode.addTransaction(tx);
      }

      // Minerar 2 blocos com as transações
      fullNode.mineNewBlock();
      fullNode.mineNewBlock();

      System.out.println("FullNode rodando e minerando blocos...");
      // Aqui poderia ficar a lógica de escuta ou interação da rede

      // Por exemplo, aguardar eventos, etc. (não encerra automaticamente)
      Thread.sleep(10_000); // só para simular espera
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private static void runLightNode() {
    try {
      var lightNode = new LightNodeService();

      // Aqui no exemplo simples você precisaria passar os headers para sincronizar
      // Como o light node não tem DB, você pode simular recebendo headers de um FullNode
      // Para isso, precisaria de um mecanismo de comunicação (exemplo abaixo)

      System.out.println("LightNode rodando e aguardando sincronização...");
      // Simula esperar headers do full node (ou chamar via API, socket, etc)
      Thread.sleep(10_000);

      // Exemplo fictício: lightNode.syncHeadersFromFullNode(headersRecebidos);
      // lightNode.printHeaders();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
