package org.openroadcode.androidbridge;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Loopback-only read-only diagnostics server, independent of navigation/sensor sharing. */
final class NetworkDiagnosticsHttpServer {
    static final int PORT = 18766;
    private static final String TAG = "ORC-NetworkDiagnostics";
    private final Context context;
    private volatile boolean running;
    private ServerSocket socket;
    private Thread thread;

    NetworkDiagnosticsHttpServer(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::serve, "orc-network-diagnostics");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private void serve() {
        try (ServerSocket listener = new ServerSocket()) {
            socket = listener;
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
            Log.i(TAG, "Listening on 127.0.0.1:" + PORT);
            while (running) {
                try (Socket client = listener.accept()) {
                    client.setSoTimeout(2000);
                    handle(client);
                } catch (IOException | RuntimeException error) {
                    if (running) Log.w(TAG, "Request failed", error);
                }
            }
        } catch (IOException error) {
            if (running) Log.e(TAG, "Cannot bind diagnostics port " + PORT, error);
        } finally {
            socket = null;
            running = false;
        }
    }

    private void handle(Socket client) throws IOException {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
        String request = input.readLine();
        if (request == null) return;
        String[] parts = request.split(" ");
        boolean get = parts.length == 3 && "GET".equals(parts[0]);
        int status = 404;
        String payload = "{\"error\":\"not_found\"}";
        if (get && "/health".equals(parts[1])) {
            status = 200;
            payload = "{\"status\":\"ok\",\"service\":\"network_diagnostics\"}";
        } else if (get && "/network/diagnostics".equals(parts[1])) {
            try {
                payload = NetworkDiagnosticsService.snapshot(context).toString();
                status = 200;
            } catch (Exception error) {
                status = 500;
                payload = "{\"error\":\"diagnostics_unavailable\"}";
                Log.w(TAG, "Snapshot failed", error);
            }
        }
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error")
                + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream output = client.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
