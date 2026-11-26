package com.sahmhoot.questionimage.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.StorageOptions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GcsJsonTransportTest {
  @Test
  void officialSdkUsesJsonPathsWithoutXmlOnTheClasspath() throws Exception {
    UUID key = UUID.randomUUID();
    byte[] content = {1, 2};
    byte[] metadata = ("""
        {"bucket":"test-bucket","name":"question-images/%s","size":"2","generation":"42"}
        """.formatted(key)).getBytes(StandardCharsets.UTF_8);
    var calls = Collections.synchronizedList(new ArrayList<String>());
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        String method = exchange.getRequestMethod();
        calls.add(method + " " + query);
        exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
        if (method.equals("DELETE")) {
          exchange.sendResponseHeaders(204, -1);
          return;
        }
        boolean download = query != null && query.contains("alt=media");
        byte[] output = download ? content : metadata;
        exchange.getResponseHeaders().set("Content-Type", download ? "application/octet-stream" : "application/json");
        exchange.sendResponseHeaders(200, output.length);
        exchange.getResponseBody().write(output);
      }
    });
    server.start();
    try {
      var sdk = StorageOptions.newBuilder().setProjectId("example-project")
          .setHost("http://127.0.0.1:" + server.getAddress().getPort())
          .setCredentials(NoCredentials.getInstance()).build().getService();
      var storage = new GcsImageStorage(sdk, "test-bucket");
      storage.save(key, "image/png", content);
      assertThat(storage.read(key)).isEqualTo(content);
      storage.delete(key);
      assertThat(List.copyOf(calls)).anyMatch(call -> call.startsWith("POST") && call.contains("ifGenerationMatch=0"))
          .anyMatch(call -> call.startsWith("GET") && call.contains("alt=media") && call.contains("generation=42"))
          .anyMatch(call -> call.startsWith("DELETE") && call.contains("ifGenerationMatch=42"));
    } finally {
      server.stop(0);
    }
  }
}
