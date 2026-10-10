package org.openroadcode.androidbridge;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Explicitly started, loopback-only, bearer-authenticated SMS HTTP endpoint. */
public final class SmsGatewayService extends Service {
  public static final int PORT = 8772;
  public static final String START = "org.openroadcode.sms.START";
  public static final String STOP = "org.openroadcode.sms.STOP";
  private static final int MAX_BODY = 16384;
  private final AtomicBoolean running = new AtomicBoolean();
  private ServerSocket server;

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null || STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (!START.equals(intent.getAction()) || !SmsGatewaySettings.enabled(this)) return START_NOT_STICKY;
    if (running.compareAndSet(false, true)) {
      new Thread(this::acceptLoop, "orc-sms-gateway").start();
    }
    return START_NOT_STICKY;
  }

  private void acceptLoop() {
    try (ServerSocket listener = new ServerSocket()) {
      server = listener;
      listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
      while (running.get()) {
        Socket client = listener.accept();
        new Thread(() -> handle(client), "orc-sms-request").start();
      }
    } catch (IOException error) {
      android.util.Log.e("ORCSms", "SMS gateway listener stopped", error);
    } finally {
      running.set(false);
      stopSelf();
    }
  }

  private void handle(Socket client) {
    try (Socket socket = client) {
      socket.setSoTimeout(3000);
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      String first = line(in);
      if (first == null) return;
      String[] request = first.split(" ");
      if (request.length != 3 || !request[2].startsWith("HTTP/1.")) {
        respond(out, 400, new JSONObject().put("error", "invalid request")); return;
      }
      String authorization = null;
      int length = 0;
      for (int i = 0; i < 64; i++) {
        String header = line(in);
        if (header == null) return;
        if (header.isEmpty()) break;
        int separator = header.indexOf(':');
        if (separator <= 0) { respond(out, 400, new JSONObject().put("error", "invalid header")); return; }
        String name = header.substring(0, separator).trim();
        String value = header.substring(separator + 1).trim();
        if (name.equalsIgnoreCase("Authorization")) {
          if (authorization != null) { respond(out, 400, new JSONObject().put("error", "duplicate auth")); return; }
          authorization = value;
        }
        if (name.equalsIgnoreCase("Content-Length")) {
          try { length = Integer.parseInt(value); }
          catch (NumberFormatException e) { length = -1; }
        }
      }
      if (!SmsGatewaySettings.enabled(this)) {
        respond(out, 503, new JSONObject().put("error", "SMS disabled")); return;
      }
      String expected = "Bearer " + SmsGatewaySettings.token(this);
      if (authorization == null || !MessageDigest.isEqual(
          authorization.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
        respond(out, 401, new JSONObject().put("error", "unauthorized")); return;
      }
      if (length < 0 || length > MAX_BODY) {
        respond(out, 400, new JSONObject().put("error", "invalid body length")); return;
      }
      byte[] body = in.readNBytes(length);
      if (body.length != length) { respond(out, 400, new JSONObject().put("error", "incomplete body")); return; }
      String[] target = request[1].split("\\?", 2);
      String path = target[0];
      int limit = 50;
      long threadId = 0;
      if (target.length > 1) {
        for (String param : target[1].split("&")) {
          String[] pair = param.split("=", 2);
          if (pair.length != 2) continue;
          if (pair[0].equals("limit")) limit = Integer.parseInt(pair[1]);
          if (pair[0].equals("thread_id")) threadId = Long.parseLong(pair[1]);
        }
      }
      SmsRepository sms = new SmsRepository(this);
      JSONObject result = new JSONObject();
      if (request[0].equals("GET") && path.equals("/sms/conversations"))
        result.put("conversations", sms.conversations(limit));
      else if (request[0].equals("GET") && path.equals("/sms/messages"))
        result.put("messages", sms.messages(threadId, limit));
      else if (request[0].equals("POST") && path.equals("/sms/send")) {
        JSONObject payload = new JSONObject(new String(body, StandardCharsets.UTF_8));
        sms.send(payload.getString("address"), payload.getString("body"));
        result.put("status", "submitted");
      } else {
        respond(out, 404, new JSONObject().put("error", "not found")); return;
      }
      respond(out, 200, result);
    } catch (SecurityException error) {
      // Never log private SMS data or bearer tokens.
      android.util.Log.w("ORCSms", "SMS permission denied");
    } catch (Exception error) {
      android.util.Log.w("ORCSms", "SMS request failed: " + error.getClass().getSimpleName());
    }
  }

  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    while (bytes.size() < 4096) {
      int next = in.read();
      if (next < 0) return null;
      if (next == '\n') {
        byte[] value = bytes.toByteArray();
        int size = value.length;
        if (size > 0 && value[size - 1] == '\r') size--;
        return new String(value, 0, size, StandardCharsets.US_ASCII);
      }
      bytes.write(next);
    }
    throw new IOException("HTTP header line too long");
  }

  private static void respond(OutputStream out, int code, JSONObject payload) throws IOException {
    byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
    out.write(("HTTP/1.1 " + code + " Response\r\nContent-Type: application/json\r\n"
        + "Cache-Control: no-store\r\nContent-Length: " + body.length
        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  @Override public void onDestroy() {
    running.set(false);
    try { if (server != null) server.close(); } catch (IOException ignored) {}
    super.onDestroy();
  }
}
