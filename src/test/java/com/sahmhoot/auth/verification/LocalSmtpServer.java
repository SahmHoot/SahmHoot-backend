package com.sahmhoot.auth.verification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 테스트 전용 loopback 수신기. 외부로 메일을 중계하지 않는다. */
final class LocalSmtpServer implements AutoCloseable {
  private final ServerSocket server;
  private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
    var thread = new Thread(task, "smtp-test-receiver");
    thread.setDaemon(true);
    return thread;
  });
  private final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
  private final AtomicBoolean rejectNext = new AtomicBoolean();

  LocalSmtpServer() {
    try {
      server = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot start test SMTP receiver", exception);
    }
    worker.submit(() -> {
      while (!server.isClosed()) {
        try (Socket socket = server.accept()) {
          receive(socket);
        } catch (SocketException exception) {
          if (!server.isClosed()) throw exception;
        }
      }
      return null;
    });
  }

  int port() { return server.getLocalPort(); }
  void rejectNextMessage() { rejectNext.set(true); }

  MimeMessage take() throws Exception {
    byte[] bytes = received.poll(7, TimeUnit.SECONDS);
    if (bytes == null) throw new AssertionError("SMTP message was not received");
    return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes));
  }

  private void receive(Socket socket) throws Exception {
    socket.setSoTimeout(5000);
    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    reply(writer, "220 localhost test SMTP");
    String line;
    while ((line = reader.readLine()) != null) {
      String command = line.toUpperCase(Locale.ROOT);
      if (command.startsWith("EHLO ") || command.startsWith("HELO ")
          || command.startsWith("MAIL FROM:") || command.startsWith("RCPT TO:")
          || command.equals("RSET") || command.equals("NOOP")) {
        reply(writer, "250 OK");
        continue;
      }
      if (command.equals("DATA")) {
        reply(writer, "354 End with a single dot");
        var data = new StringBuilder();
        while ((line = reader.readLine()) != null && !line.equals(".")) {
          data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
        }
        if (rejectNext.getAndSet(false)) {
          reply(writer, "451 Temporary failure");
          continue;
        }
        received.add(data.toString().getBytes(StandardCharsets.UTF_8));
        reply(writer, "250 Queued");
        continue;
      }
      if (command.equals("QUIT")) {
        reply(writer, "221 Bye");
        return;
      }
      reply(writer, "502 Unsupported command");
    }
  }

  private void reply(BufferedWriter writer, String value) throws Exception {
    writer.write(value + "\r\n");
    writer.flush();
  }

  @Override public void close() throws Exception {
    server.close();
    worker.shutdownNow();
    if (!worker.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("SMTP receiver did not stop");
  }
}
