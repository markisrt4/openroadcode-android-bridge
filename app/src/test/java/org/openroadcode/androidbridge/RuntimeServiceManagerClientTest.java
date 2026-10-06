package org.openroadcode.androidbridge;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class RuntimeServiceManagerClientTest {
  private ServerSocket server;
  private Thread serverThread;
  private final AtomicReference<String> method = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();

  @Before
  public void startServer() throws Exception {
    server = new ServerSocket(0);
    serverThread = new Thread(() -> {
      try (Socket socket = server.accept();
           BufferedReader reader = new BufferedReader(new InputStreamReader(
               socket.getInputStream(), StandardCharsets.UTF_8))) {
        String[] request = reader.readLine().split(" ");
        method.set(request[0]);
        path.set(request[1]);
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
          if (line.regionMatches(true, 0, "Authorization:", 0, 14)) {
            authorization.set(line.substring(14).trim());
          }
        }
        byte[] response = "{\"status\":\"configured\"}".getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
            + "Content-Length: " + response.length + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(response);
        socket.getOutputStream().flush();
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    });
    serverThread.start();
  }

  @After
  public void stopServer() throws Exception {
    server.close();
  }

  @Test
  public void registerAndroidBridgeUsesAuthenticatedRuntimeEndpoint() throws Exception {
    RuntimeServiceManagerClient client = new RuntimeServiceManagerClient(
        "http://127.0.0.1:" + server.getLocalPort(),
        "Remote Linux",
        "client-token");

    assertEquals("configured", client.registerAndroidBridge().getString("status"));
    serverThread.join(1000);
    assertEquals("POST", method.get());
    assertEquals("/runtime/android-bridge", path.get());
    assertEquals("Bearer client-token", authorization.get());
  }
}
