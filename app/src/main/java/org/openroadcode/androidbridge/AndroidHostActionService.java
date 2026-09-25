package org.openroadcode.androidbridge;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Localhost-only Android host actions for OpenRoadCode.
 *
 * This deliberately lives outside the sensor bridge. Sensors report state;
 * host actions ask Android to do something on ORC's behalf.
 */
public final class AndroidHostActionService extends Service {
    public static final int PORT = 8770;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread serverThread;

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        serverThread = new Thread(this::runServer, "orc-host-actions");
        serverThread.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void runServer() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
            while (running) {
                try (Socket client = serverSocket.accept()) {
                    serveClient(client);
                } catch (IOException ignored) {
                    if (!running) return;
                }
            }
        } catch (IOException ignored) {
            // A later app start will retry by recreating the service.
        }
    }

    private void serveClient(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                client.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null) return;

        int contentLength = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); }
                catch (NumberFormatException ignored) { contentLength = 0; }
            }
        }

        char[] bodyChars = new char[Math.max(0, contentLength)];
        int offset = 0;
        while (offset < bodyChars.length) {
            int count = reader.read(bodyChars, offset, bodyChars.length - offset);
            if (count < 0) break;
            offset += count;
        }
        String body = new String(bodyChars, 0, offset);

        if ("GET /health HTTP/1.1".equals(requestLine)) {
            respond(client, 200, "{\"status\":\"ok\"}");
            return;
        }
        if ("POST /launch/package HTTP/1.1".equals(requestLine)) {
            String packageName = formValues(body).get("package");
            if (packageName == null || packageName.trim().isEmpty()) {
                respond(client, 400, "{\"error\":\"package is required\"}");
                return;
            }
            Map<String, String> values = formValues(body);
            launchPackage(client, packageName.trim(), values);
            return;
        }
        if ("POST /open/uri HTTP/1.1".equals(requestLine)) {
            String uri = formValues(body).get("uri");
            if (uri == null || uri.trim().isEmpty()) {
                respond(client, 400, "{\"error\":\"uri is required\"}");
                return;
            }
            openUri(client, uri.trim());
            return;
        }
        respond(client, 404, "{\"error\":\"not found\"}");
    }

    private void launchPackage(
            Socket client, String packageName, Map<String, String> values) throws IOException {
        PackageManager packageManager = getPackageManager();
        Intent packageIntent = packageManager.getLaunchIntentForPackage(packageName);
        if (packageIntent == null) {
            respond(client, 404, "{\"error\":\"package not installed or not visible\"}");
            return;
        }

        boolean freeformSupported = packageManager.hasSystemFeature(
                PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT);
        Rect bounds;
        try {
            bounds = launchBounds(values);
        } catch (IllegalArgumentException exception) {
            respond(client, 400, "{\"error\":\"" + jsonEscape(exception.getMessage()) + "\"}");
            return;
        }

        Intent hostIntent = new Intent(this, MainActivity.class);
        hostIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        hostIntent.putExtra("orc_host_action_package", packageName);
        if (bounds != null) {
            hostIntent.putExtra("orc_host_action_x", bounds.left);
            hostIntent.putExtra("orc_host_action_y", bounds.top);
            hostIntent.putExtra("orc_host_action_width", bounds.width());
            hostIntent.putExtra("orc_host_action_height", bounds.height());
        }

        try {
            startActivity(hostIntent);
            String requestedBounds = bounds == null ? "null"
                    : "{\"x\":" + bounds.left
                    + ",\"y\":" + bounds.top
                    + ",\"width\":" + bounds.width()
                    + ",\"height\":" + bounds.height() + "}";
            respond(client, 200,
                    "{\"status\":\"host_activity_requested\",\"freeform_supported\":"
                    + freeformSupported + ",\"requested_bounds\":" + requestedBounds + "}");
        } catch (RuntimeException exception) {
            respond(client, 500,
                    "{\"error\":\"host activity launch failed\",\"exception\":\""
                    + jsonEscape(exception.getClass().getSimpleName()) + "\"}");
        }
    }

    private static Rect launchBounds(Map<String, String> values) {
        String xValue = values.get("x");
        String yValue = values.get("y");
        String widthValue = values.get("width");
        String heightValue = values.get("height");
        boolean any = xValue != null || yValue != null || widthValue != null || heightValue != null;
        if (!any) return null;
        if (xValue == null || yValue == null || widthValue == null || heightValue == null)
            throw new IllegalArgumentException("x, y, width, and height must be provided together");

        try {
            int x = Integer.parseInt(xValue);
            int y = Integer.parseInt(yValue);
            int width = Integer.parseInt(widthValue);
            int height = Integer.parseInt(heightValue);
            if (x < 0 || y < 0 || width <= 0 || height <= 0)
                throw new IllegalArgumentException(
                        "x and y must be non-negative; width and height must be positive");
            return new Rect(x, y, x + width, y + height);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("launch bounds must be integers");
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void openUri(Socket client, String uri) throws IOException {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            respond(client, 200, "{\"status\":\"opened\"}");
        } catch (RuntimeException exception) {
            respond(client, 500, "{\"error\":\"open failed\"}");
        }
    }

    private static Map<String, String> formValues(String body) {
        Map<String, String> values = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static void respond(Socket client, int status, String body) throws IOException {
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 404 ? "Not Found" : "Internal Server Error";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = client.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(payload);
        output.flush();
    }

    @Override public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (serverThread != null) serverThread.interrupt();
        super.onDestroy();
    }
}
