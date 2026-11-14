package org.openroadcode.androidbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Loopback-only SDRForge REST diagnostics; never manages USB ownership. */
final class SdrForgeControlClient {
  static final String BASE_URL = "http://127.0.0.1:5570";
  private static final int MAX_BYTES = 65536;

  JSONObject health() throws Exception { return request("GET", "/v1/health"); }
  JSONObject receiver() throws Exception { return request("GET", "/v1/receiver"); }
  JSONObject start() throws Exception { return request("POST", "/v1/receiver/start"); }
  JSONObject stop() throws Exception { return request("POST", "/v1/receiver/stop"); }

  JSONObject tune(int frequencyHz) throws Exception {
    if (frequencyHz < 87500000 || frequencyHz > 108000000)
      throw new IllegalArgumentException("FM frequency must be 87.5–108 MHz");
    JSONObject body = new JSONObject();
    body.put("frequency_hz", frequencyHz);
    return request("POST", "/v1/receiver/frequency", body);
  }

  private JSONObject request(String method, String path) throws Exception {
    return request(method, path, null);
  }

  private JSONObject request(String method, String path, JSONObject body) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
    try {
      connection.setRequestMethod(method);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(1200);
      connection.setReadTimeout(6000);
      connection.setUseCaches(false);
      if ("POST".equals(method)) {
        connection.setDoOutput(true);
        byte[] data = body == null ? new byte[0] : body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(data.length);
        try (java.io.OutputStream output = connection.getOutputStream()) {
          output.write(data);
        }
      }
      int code = connection.getResponseCode();
      InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String text = stream == null ? "" : readBounded(stream);
      JSONObject result = text.isEmpty() ? new JSONObject() : new JSONObject(text);
      if (code < 200 || code >= 300)
        throw new IOException("HTTP " + code + ": " + result.optString("error", "Service unavailable"));
      return result;
    } finally {
      connection.disconnect();
    }
  }

  private static String readBounded(InputStream stream) throws IOException {
    try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[4096];
      int count;
      while ((count = input.read(bytes)) != -1) {
        if (output.size() + count > MAX_BYTES)
          throw new IOException("SDRForge response exceeds size limit");
        output.write(bytes, 0, count);
      }
      return output.toString(StandardCharsets.UTF_8.name());
    }
  }
}
