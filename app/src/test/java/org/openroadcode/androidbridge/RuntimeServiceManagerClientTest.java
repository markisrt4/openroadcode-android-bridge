package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public final class RuntimeServiceManagerClientTest {
  @Test public void performanceUsesPairedTargetAndCredentials() throws Exception {
    try (Stub server = new Stub(200, "{\"version\":1,\"snapshot\":{}}", "")) {
      JSONObject result = new RuntimeServiceManagerClient(server.url(), "Test unit", "test-token").getPerformance();
      assertEquals(1, result.getInt("version"));
      assertTrue(server.request().startsWith("GET /performance "));
      assertTrue(server.request().contains("Authorization: Bearer test-token"));
    }
  }

  @Test public void missingPerformanceApiExplainsUpgradeEvenWithHtmlBody() throws Exception {
    try (Stub server = new Stub(404, "<html>Not found</html>", "")) {
      try {
        new RuntimeServiceManagerClient(server.url(), "Test unit").getPerformance();
        fail("Missing API accepted");
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage().contains("Update and restart"));
      }
    }
  }

  @Test public void authenticationFailureExplainsPairingWithoutParsingBody() throws Exception {
    try (Stub server = new Stub(401, "private detail", "")) {
      try {
        new RuntimeServiceManagerClient(server.url(), "Test unit", "test-token").getPerformance();
        fail("Unauthorized response accepted");
      } catch (IllegalStateException expected) {
        assertTrue(expected.getMessage().contains("Configuration"));
        assertFalse(expected.getMessage().contains("private detail"));
      }
    }
  }

  @Test public void performanceDoesNotFollowCredentialRedirects() throws Exception {
    try (Stub destination = new Stub(200, "{}", "");
         Stub redirect = new Stub(302, "", "Location: " + destination.url() + "\r\n")) {
      try {
        new RuntimeServiceManagerClient(redirect.url(), "Test unit", "test-token").getPerformance();
        fail("Redirect accepted");
      } catch (IOException expected) { }
      assertFalse(destination.received.await(200, TimeUnit.MILLISECONDS));
    }
  }

  @Test public void responseMemoryIsBounded() throws Exception {
    assertEquals(3, RuntimeServiceManagerClient.readBounded(new ByteArrayInputStream(new byte[3])).length);
    try {
      RuntimeServiceManagerClient.readBounded(new ByteArrayInputStream(new byte[RuntimeServiceManagerClient.MAX_RESPONSE_BYTES + 1]));
      fail("Oversized response accepted");
    } catch (IOException expected) { }
  }

  /** Minimal HTTP fixture; no Android runtime or external network required. */
  private static final class Stub implements AutoCloseable {
    final ServerSocket server = new ServerSocket(0);
    final CountDownLatch received = new CountDownLatch(1);
    final AtomicReference<String> request = new AtomicReference<>();
    final Thread worker;

    Stub(int status, String body, String extraHeaders) throws IOException {
      worker = new Thread(() -> {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(2000);
          BufferedReader reader = new BufferedReader(new InputStreamReader(
              socket.getInputStream(), StandardCharsets.UTF_8));
          StringBuilder headers = new StringBuilder();
          String line;
          while ((line = reader.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
          request.set(headers.toString());
          received.countDown();
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          socket.getOutputStream().write(("HTTP/1.1 " + status + " Response\r\n"
              + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n"
              + extraHeaders + "\r\n").getBytes(StandardCharsets.UTF_8));
          socket.getOutputStream().write(bytes);
        } catch (IOException ignored) { }
      });
      worker.start();
    }

    String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
    String request() throws InterruptedException {
      assertTrue(received.await(3, TimeUnit.SECONDS));
      return request.get();
    }
    @Override public void close() throws Exception { server.close(); worker.join(3000); }
  }
}
