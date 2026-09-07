package org.openroadcode.androidbridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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

  public JSONObject getServices() throws Exception {
    return request("GET", "/services");
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

  private JSONObject serviceAction(String service, String action) throws Exception {
    if (!service.matches("openroadcode-(message-broker|navigation|automotive|adsb)")) {
      throw new IllegalArgumentException("Unsupported OpenRoadCode service: " + service);
    }
    return request("POST", "/services/" + service + "/" + action);
  }

  private JSONObject request(String method, String path) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(1000);
    connection.setReadTimeout(2000);
    connection.setUseCaches(false);
    if (bearerToken != null) {
      connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
    }
    if ("POST".equals(method)) {
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(0);
    }
    try {
      int status = connection.getResponseCode();
      BufferedReader reader = new BufferedReader(new InputStreamReader(
          status >= 200 && status < 300
              ? connection.getInputStream()
              : connection.getErrorStream(),
          StandardCharsets.UTF_8));
      StringBuilder body = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        body.append(line);
      }
      reader.close();
      JSONObject response = new JSONObject(body.toString());
      if (status < 200 || status >= 300) {
        throw new IllegalStateException(response.optString(
            "error", targetLabel + " service request failed"));
      }
      return response;
    } finally {
      connection.disconnect();
    }
  }

  private static String trimTrailingSlash(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(0, end);
  }
}
