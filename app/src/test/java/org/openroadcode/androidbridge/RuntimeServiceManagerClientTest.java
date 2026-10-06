package org.openroadcode.androidbridge;

import static org.junit.Assert.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class RuntimeServiceManagerClientTest {
  private HttpServer server;
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();

  @Before
  public void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      method.set(exchange.getRequestMethod());
      path.set(exchange.getRequestURI().getPath());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] response = "{\"status\":\"configured\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
  }

  @After
  public void stopServer() {
    server.stop(0);
  }

  @Test
  public void registerAndroidBridgeUsesAuthenticatedRuntimeEndpoint() throws Exception {
    RuntimeServiceManagerClient client = new RuntimeServiceManagerClient(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "Remote Linux",
        "client-token");

    assertEquals("configured", client.registerAndroidBridge().getString("status"));
    assertEquals("POST", method.get());
    assertEquals("/runtime/android-bridge", path.get());
    assertEquals("Bearer client-token", authorization.get());
  }
}
