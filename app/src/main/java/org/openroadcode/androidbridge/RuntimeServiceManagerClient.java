package org.openroadcode.androidbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * HTTP client for an OpenRoadCode runtime service manager.
 *
 * The remote endpoint is expected to expose the same restricted service-manager
 * contract regardless of its process supervisor (for example runit or systemd).
 */
public final class RuntimeServiceManagerClient {
  private final String baseUrl;
  private final String targetLabel;
  private final String bearerToken;

  public RuntimeServiceManagerClient(String baseUrl, String targetLabel) {
    this(baseUrl, targetLabel, null);
  }

  public RuntimeServiceManagerClient(String baseUrl, String targetLabel, String bearerToken) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("Service manager base URL is required");
    }
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.targetLabel = targetLabel == null || targetLabel.isBlank() ? "runtime" : targetLabel;
    this.bearerToken = bearerToken == null || bearerToken.isBlank() ? null : bearerToken.trim();
  }

  public String baseUrl() {
    return baseUrl;
  }

  public String targetLabel() {
    return targetLabel;
  }

  /** Exchange a temporary service-manager PIN for this client's bearer credential. */
  public JSONObject pair(String pin, String clientName) throws Exception {
    if (pin == null || !pin.trim().matches("\\d{6}")) {
      throw new IllegalArgumentException("Pairing PIN must be exactly 6 digits");
    }
    if (clientName == null || clientName.isBlank()) {
      throw new IllegalArgumentException("Client name is required");
    }
    JSONObject body = new JSONObject();
    body.put("pin", pin.trim());
    body.put("client_name", clientName.trim());
    return request("POST", "/pair", body);
  }

  /** Start a browser-approved pairing session for this client. */
  public JSONObject startBrowserPairing(String clientName) throws Exception {
    if (clientName == null || clientName.isBlank()) {
      throw new IllegalArgumentException("Client name is required");
    }
    JSONObject body = new JSONObject();
    body.put("client_name", clientName.trim());
    return request("POST", "/pairing/browser/start", body);
  }

  /** Poll a browser pairing session until the administrator approves it. */
  public JSONObject browserPairingStatus(String sessionId, String pollToken) throws Exception {
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("Pairing session ID is required");
    }
    if (pollToken == null || pollToken.isBlank()) {
      throw new IllegalArgumentException("Pairing poll token is required");
    }
    return request(
        "GET",
        "/pairing/browser/status/" + sessionId.trim(),
        null,
        pollToken.trim());
  }

  public JSONObject getServices() throws Exception {
    return request("GET", "/services");
  }

  /** Read the selected computing unit's metrics and bounded performance history. */
  public JSONObject getPerformance() throws Exception {
    return request("GET", "/performance");
  }

  /** Register this Android Bridge as the selected runtime's shared bridge endpoint. */
  public JSONObject registerAndroidBridge() throws Exception {
    return request("POST", "/runtime/android-bridge");
  }

  public JSONObject startCoreStack() throws Exception {
    return request("POST", "/stack/core/start");
  }

  public JSONObject stopCoreStack() throws Exception {
    return request("POST", "/stack/core/stop");
  }

  public JSONObject startService(String service) throws Exception {
    return serviceAction(service, "start");
  }

  public JSONObject stopService(String service) throws Exception {
    return serviceAction(service, "stop");
  }

  public JSONObject restartService(String service) throws Exception {
    return serviceAction(service, "restart");
  }

  public JSONObject setServiceProfile(String service, String profile) throws Exception {
    validateService(service);
    if (!profile.matches("local|remote|simulated")) {
      throw new IllegalArgumentException("Unsupported runtime profile: " + profile);
    }
    return request("POST", "/services/" + service + "/profile/" + profile);
  }

  private JSONObject serviceAction(String service, String action) throws Exception {
    validateService(service);
    return request("POST", "/services/" + service + "/" + action);
  }

  private static void validateService(String service) {
    if (!service.matches("openroadcode-(message-broker|navigation|automotive|adsb)")) {
      throw new IllegalArgumentException("Unsupported OpenRoadCode service: " + service);
    }
  }

  private JSONObject request(String method, String path) throws Exception {
    return request(method, path, null);
  }

  private JSONObject request(String method, String path, JSONObject requestBody) throws Exception {
    return request(method, path, requestBody, null);
  }

  private JSONObject request(
      String method, String path, JSONObject requestBody, String pairingToken) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod(method);
      connection.setConnectTimeout(1000);
      connection.setReadTimeout(2000);
      connection.setUseCaches(false);
      if (bearerToken != null) {
        connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
      }
      if (pairingToken != null) {
        connection.setRequestProperty("X-OpenRoadCode-Pairing-Token", pairingToken);
      }
      if ("POST".equals(method)) {
        connection.setDoOutput(true);
        if (requestBody == null) {
          connection.setFixedLengthStreamingMode(0);
        } else {
          byte[] encoded = requestBody.toString().getBytes(StandardCharsets.UTF_8);
          connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
          connection.setFixedLengthStreamingMode(encoded.length);
          try (OutputStream output = connection.getOutputStream()) {
            output.write(encoded);
          }
        }
      }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) {
        if (status == 404 && "/performance".equals(path)) {
          throw new IllegalStateException(
              "Performance monitoring is not available on " + targetLabel
              + ". Update and restart its OpenRoadCode service manager.");
        }
        if (status == 404 && path.contains("/profile/")) {
          throw new IllegalStateException(
              "Runtime profile API is not available on " + targetLabel
              + ". Update and restart the OpenRoadCode service manager.");
        }
        if (status == 401 || status == 403) {
          throw new IllegalStateException("Pair " + targetLabel + " again in Configuration.");
        }
        String detail = targetLabel + " service request failed (HTTP " + status + ")";
        // Preserve actionable JSON errors from runtime actions, with a safe fallback
        // when an older server or gateway returns plain text or HTML.
        if (status >= 400) {
          try (InputStream input = connection.getErrorStream()) {
            if (input != null) detail = new JSONObject(
                new String(readBounded(input), StandardCharsets.UTF_8)).optString("error", detail);
          } catch (Exception ignored) { }
        }
        throw new IOException(detail);
      }
      try (InputStream input = connection.getInputStream()) {
        return new JSONObject(new String(readBounded(input), StandardCharsets.UTF_8));
      }
    } finally {
      connection.disconnect();
    }
  }

  static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

  static byte[] readBounded(InputStream input) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int count;
    while ((count = input.read(chunk)) != -1) {
      if (body.size() + count > MAX_RESPONSE_BYTES)
        throw new IOException("Service response exceeded size limit");
      body.write(chunk, 0, count);
    }
    return body.toByteArray();
  }

  private static String trimTrailingSlash(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }
}
