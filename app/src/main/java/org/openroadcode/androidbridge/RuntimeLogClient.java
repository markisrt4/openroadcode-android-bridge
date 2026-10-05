package org.openroadcode.androidbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Cancellable, bounded log-page client using the runtime's existing credentials. */
final class RuntimeLogClient {
  static final int MAX_RESPONSE_BYTES = 384 * 1024;
  private final String baseUrl;
  private final String token;
  private HttpURLConnection connection;
  private boolean closed;

  RuntimeLogClient(String baseUrl, String token) {
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.token = token;
  }

  JSONObject read(String cursor, String level, String component) throws Exception {
    String query = "/logs?level=" + encode(level) + "&component=" + encode(component)
        + "&cursor=" + encode(cursor);
    HttpURLConnection request = (HttpURLConnection) new URL(baseUrl + query).openConnection();
    synchronized (this) {
      if (closed) throw new IOException("Log viewer closed");
      connection = request;
    }
    try {
      request.setRequestMethod("GET");
      request.setConnectTimeout(1500);
      request.setReadTimeout(2500);
      request.setUseCaches(false);
      // Never forward a credential through a server redirect.
      request.setInstanceFollowRedirects(false);
      if (token != null && !token.isBlank())
        request.setRequestProperty("Authorization", "Bearer " + token);
      int status = request.getResponseCode();
      if (status == 401 || status == 403)
        throw new LogAccessException("Pair this runtime again from the Runtime screen.");
      if (status == 404)
        throw new LogAccessException("Update and restart the ORC service manager to view logs.");
      if (status == 400)
        throw new LogAccessException("Check the severity and component filter.");
      if (status != 200) throw new IOException("Log request failed");
      try (InputStream input = request.getInputStream()) {
        return new JSONObject(new String(readBounded(input), StandardCharsets.UTF_8));
      }
    } finally {
      request.disconnect();
      synchronized (this) {
        if (connection == request) connection = null;
      }
    }
  }

  synchronized void close() {
    closed = true;
    if (connection != null) connection.disconnect();
    connection = null;
  }

  static byte[] readBounded(InputStream input) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int count;
    while ((count = input.read(chunk)) != -1) {
      if (body.size() + count > MAX_RESPONSE_BYTES)
        throw new IOException("Log response exceeded size limit");
      body.write(chunk, 0, count);
    }
    return body.toByteArray();
  }

  private static String encode(String value) throws Exception {
    return URLEncoder.encode(value, "UTF-8");
  }

  static final class LogAccessException extends IOException {
    LogAccessException(String message) { super(message); }
  }
}
