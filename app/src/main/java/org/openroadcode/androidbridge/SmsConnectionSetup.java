package org.openroadcode.androidbridge;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Small local setup transport. Never follows redirects or exposes tokens in errors. */
public final class SmsConnectionSetup {
  private static final String BASE = "http://127.0.0.1:8769";

  public static final class SetupFailure extends Exception {
    SetupFailure(String message) { super(message); }
  }

  public static final class Session {
    final String id;
    final String pollToken;
    final String approvalUrl;
    Session(String id, String pollToken, String approvalUrl) {
      this.id = id; this.pollToken = pollToken; this.approvalUrl = approvalUrl;
    }
    @Override public String toString() { return "Messaging setup session"; }
  }

  public static Session begin() throws Exception {
    JSONObject response = request("POST", "/sms/connect/start", null, new JSONObject());
    String id = response.getString("session_id");
    String poll = response.getString("poll_token");
    String approval = response.getString("approval_url");
    if (!id.matches("[A-Za-z0-9_-]{16,128}") || !poll.matches("[A-Za-z0-9_-]{32,256}")
        || !validApprovalUrl(id, approval)) throw new SetupFailure("Invalid setup response");
    return new Session(id, poll, approval);
  }

  static boolean validApprovalUrl(String id, String url) {
    return url.matches("http://127\\.0\\.0\\.1:8769/sms/connect/approve/" + id
        + "\\?token=[A-Za-z0-9_-]{32,256}");
  }

  public static String status(Session session) throws Exception {
    return request("GET", "/sms/connect/status/" + session.id, session.pollToken, null).getString("status");
  }

  public static void complete(Session session, String androidToken) throws Exception {
    JSONObject result = request("POST", "/sms/connect/complete/" + session.id, session.pollToken,
        new JSONObject().put("token", androidToken));
    if (!"connected".equals(result.optString("status"))) throw new SetupFailure("Setup not confirmed");
  }

  public static void cancel(Session session) {
    try { request("POST", "/sms/connect/cancel/" + session.id, session.pollToken, new JSONObject()); }
    catch (Exception ignored) { /* The bounded session expires if unreachable. */ }
  }

  private static JSONObject request(String method, String path, String bearer, JSONObject payload) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection(Proxy.NO_PROXY);
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setUseCaches(false);
      connection.setConnectTimeout(3000);
      connection.setReadTimeout(4000);
      connection.setRequestMethod(method);
      if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
      if (payload != null) {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(body.length);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
      }
      int status = connection.getResponseCode();
      if (status == 404) throw new SetupFailure("Update and restart OpenRoadCode in Termux to use Connect messaging.");
      if (status == 410) throw new SetupFailure("Setup expired. Tap Connect messaging again.");
      if (status == 502) throw new SetupFailure("Enable messages and grant SMS read permission, then try Connect messaging again.");
      if (status != 200) throw new SetupFailure("Messaging setup was not accepted. Try Connect messaging again.");
      try (InputStream input = connection.getInputStream()) {
        byte[] raw = StreamBytes.readUpTo(input, 4097);
        if (raw.length > 4096) throw new SetupFailure("Invalid setup response");
        return new JSONObject(new String(raw, StandardCharsets.UTF_8));
      }
    } finally { connection.disconnect(); }
  }
}
