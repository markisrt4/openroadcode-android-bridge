package org.openroadcode.androidbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Localhost-only control plane for Android RTL-TCP provider startup.
 *
 * Termux cannot reliably launch another Android app through ActivityManager as
 * an ordinary app UID. The bridge is the Android-side boundary, so ORC asks
 * this service to launch the installed RTL-SDR driver using a normal Intent.
 */
public final class RtlTcpProviderControlService extends Service {
  public static final int PORT = 8772;
  private static final int NOTIFICATION = 8772;
  private static final String CHANNEL_ID = "orc-rtl-tcp-provider";
  private static final String DRIVER_PACKAGE = "marto.rtl_tcp_andro";
  private static final String DRIVER_ACTIVITY = "com.sdrtouch.rtlsdr.DeviceOpenActivity";
  private static final int DEFAULT_FREQUENCY_HZ = 104_300_000;
  private static final int DEFAULT_SAMPLE_RATE = 2_400_000;

  private volatile boolean running;
  private ServerSocket server;
  private Thread serverThread;

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (running) return START_STICKY;
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      nm.createNotificationChannel(new NotificationChannel(
          CHANNEL_ID, "ORC RTL-TCP provider", NotificationManager.IMPORTANCE_LOW));
      Notification notification = new Notification.Builder(this, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_openroadcode_notification)
          .setContentTitle("OpenRoadCode RTL-TCP control")
          .setContentText("Local Android SDR provider control active")
          .setOngoing(true)
          .build();
      if (Build.VERSION.SDK_INT >= 29) {
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      } else {
        startForeground(NOTIFICATION, notification);
      }

      server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"));
      running = true;
      serverThread = new Thread(this::serveLoop, "orc-rtl-tcp-control");
      serverThread.start();
    } catch (Exception ex) {
      android.util.Log.e("ORCRtlTcpControl", "RTL-TCP control start failed", ex);
      stopSelf();
    }
    return START_STICKY;
  }

  private void serveLoop() {
    while (running) {
      try (Socket client = server.accept()) {
        handle(client);
      } catch (IOException ex) {
        if (running) android.util.Log.w("ORCRtlTcpControl", "Control request failed", ex);
      }
    }
  }

  private void handle(Socket client) throws IOException {
    client.setSoTimeout(2000);
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
    String requestLine = reader.readLine();
    if (requestLine == null) return;

    int contentLength = 0;
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon > 0 && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
        try { contentLength = Integer.parseInt(line.substring(colon + 1).trim()); }
        catch (NumberFormatException ignored) { }
      }
    }

    if (!requestLine.startsWith("POST /rtl-tcp/start ")) {
      respond(client, 404, "{\"error\":\"not_found\"}");
      return;
    }

    char[] bodyChars = new char[Math.max(0, contentLength)];
    int offset = 0;
    while (offset < bodyChars.length) {
      int count = reader.read(bodyChars, offset, bodyChars.length - offset);
      if (count < 0) break;
      offset += count;
    }
    Map<String, String> form = parseForm(new String(bodyChars, 0, offset));
    try {
      int frequency = positiveInt(form.get("frequency_hz"), DEFAULT_FREQUENCY_HZ);
      int sampleRate = positiveInt(form.get("sample_rate"), DEFAULT_SAMPLE_RATE);
      int port = positiveInt(form.get("port"), 1234);
      String launchDetails = launchProvider(frequency, sampleRate, port);
      respond(client, 200, launchDetails);
    } catch (Exception ex) {
      String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      respond(client, 500, "{\"error\":\"" + jsonEscape(message) + "\"}");
    }
  }

  private String launchProvider(int frequencyHz, int sampleRate, int port) {
    String uri = "iqsrc://-a 127.0.0.1 -p " + port
        + " -f " + frequencyHz + " -s " + sampleRate + " -T 0";
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
    intent.setClassName(DRIVER_PACKAGE, DRIVER_ACTIVITY);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    ResolveInfo resolved = getPackageManager().resolveActivity(intent, 0);
    if (resolved == null || resolved.activityInfo == null) {
      String message = "RTL-TCP driver activity is not resolvable: "
          + DRIVER_PACKAGE + "/" + DRIVER_ACTIVITY;
      android.util.Log.e("ORCRtlTcpControl", message);
      throw new IllegalStateException(message);
    }

    ActivityInfo activity = resolved.activityInfo;
    String resolvedComponent = activity.packageName + "/" + activity.name;
    android.util.Log.i("ORCRtlTcpControl",
        "Resolved RTL-TCP provider activity: " + resolvedComponent
            + " exported=" + activity.exported + " enabled=" + activity.enabled);

    if (!activity.enabled) {
      throw new IllegalStateException("RTL-TCP driver activity is disabled: " + resolvedComponent);
    }
    if (!activity.exported && !getPackageName().equals(activity.packageName)) {
      throw new IllegalStateException("RTL-TCP driver activity is not exported: " + resolvedComponent);
    }

    startActivity(intent);
    android.util.Log.i("ORCRtlTcpControl", "Requested RTL-TCP provider: " + uri);
    return "{\"status\":\"launch_requested\","
        + "\"resolved_component\":\"" + jsonEscape(resolvedComponent) + "\","
        + "\"exported\":" + activity.exported + ","
        + "\"enabled\":" + activity.enabled + "}";
  }

  private static Map<String, String> parseForm(String body) {
    Map<String, String> values = new HashMap<>();
    for (String field : body.split("&")) {
      if (field.isEmpty()) continue;
      int equals = field.indexOf('=');
      String key = equals < 0 ? field : field.substring(0, equals);
      String value = equals < 0 ? "" : field.substring(equals + 1);
      values.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return values;
  }

  private static int positiveInt(String value, int fallback) {
    if (value == null || value.isEmpty()) return fallback;
    int parsed = Integer.parseInt(value);
    if (parsed <= 0) throw new IllegalArgumentException("Expected positive integer");
    return parsed;
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }

  private static void respond(Socket client, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : "Internal Server Error";
    String headers = "HTTP/1.1 " + status + " " + reason + "\r\n"
        + "Content-Type: application/json\r\n"
        + "Content-Length: " + bytes.length + "\r\n"
        + "Connection: close\r\n\r\n";
    OutputStream out = client.getOutputStream();
    out.write(headers.getBytes(StandardCharsets.US_ASCII));
    out.write(bytes);
    out.flush();
  }

  @Override public void onDestroy() {
    running = false;
    try { if (server != null) server.close(); } catch (IOException ignored) { }
    server = null;
    if (serverThread != null) serverThread.interrupt();
    serverThread = null;
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
