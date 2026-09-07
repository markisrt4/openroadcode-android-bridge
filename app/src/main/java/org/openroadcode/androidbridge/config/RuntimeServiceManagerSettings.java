package org.openroadcode.androidbridge.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted target configuration for the OpenRoadCode runtime service manager. */
public final class RuntimeServiceManagerSettings {
  private static final String PREFS = "runtime_service_manager";
  private static final String KEY_TARGET = "target";
  private static final String KEY_PI_BASE_URL = "pi_base_url";
  private static final String KEY_PI_TOKEN = "pi_token";

  public enum Target {
    TERMUX,
    REMOTE_PI
  }

  private final SharedPreferences preferences;

  public RuntimeServiceManagerSettings(Context context) {
    preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public Target target() {
    String value = preferences.getString(KEY_TARGET, Target.TERMUX.name());
    try {
      return Target.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return Target.TERMUX;
    }
  }

  public void setTarget(Target target) {
    preferences.edit().putString(KEY_TARGET, target.name()).apply();
  }

  public String piBaseUrl() {
    return preferences.getString(KEY_PI_BASE_URL, "");
  }

  public void setPiBaseUrl(String baseUrl) {
    preferences.edit().putString(KEY_PI_BASE_URL, normalizeBaseUrl(baseUrl)).apply();
  }

  public String piToken() {
    return preferences.getString(KEY_PI_TOKEN, "");
  }

  public void setPiToken(String token) {
    preferences.edit().putString(KEY_PI_TOKEN, token == null ? "" : token.trim()).apply();
  }

  public boolean hasRemotePiConfiguration() {
    return !piBaseUrl().isBlank() && !piToken().isBlank();
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) {
      return "";
    }
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
