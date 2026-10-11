package org.openroadcode.androidbridge;

import android.content.Context;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Explicit SMS credential provisioning to a local Termux service manager.
 *
 * Call only after the user chooses provisioning in the SMS card. This class
 * deliberately does not use the ordinary paired runtime access token.
 */
public final class SmsCredentialProvisioner {
  private SmsCredentialProvisioner() {}

  public static void provisionLocal(Context context, String provisioningToken, int port)
      throws Exception {
    if (!SmsGatewaySettings.enabled(context)) {
      throw new IllegalStateException("Enable the SMS gateway before provisioning.");
    }
    if (provisioningToken == null || provisioningToken.trim().length() < 32) {
      throw new IllegalArgumentException("A separate provisioning token is required.");
    }
    if (port < 1024 || port > 65535) {
      throw new IllegalArgumentException("Invalid local service-manager port.");
    }
    // Only connect to this device. Never send the SMS bearer to a remote host
    // or to a caller-supplied URL.
    HttpURLConnection connection = (HttpURLConnection) new URL(
        "http://127.0.0.1:" + port + "/sms/provision").openConnection();
    try {
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(2000);
      connection.setReadTimeout(3000);
      connection.setUseCaches(false);
      connection.setRequestProperty("Authorization", "Bearer " + provisioningToken.trim());
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      JSONObject body = new JSONObject();
      body.put("token", SmsGatewaySettings.token(context));
      byte[] encoded = body.toString().getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(encoded.length);
      connection.setDoOutput(true);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(encoded);
      }
      int status = connection.getResponseCode();
      if (status != 200) {
        throw new IllegalStateException("SMS provisioning failed (HTTP " + status + ").");
      }
      try (InputStream input = connection.getInputStream()) {
        byte[] result = input.readNBytes(512);
        JSONObject response = new JSONObject(new String(result, StandardCharsets.UTF_8));
        if (!response.optBoolean("provisioned", false)) {
          throw new IllegalStateException("SMS provisioning was not confirmed.");
        }
      }
    } finally {
      connection.disconnect();
    }
  }
}
