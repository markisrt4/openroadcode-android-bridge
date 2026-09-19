package org.openroadcode.androidbridge.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Persisted target configuration for OpenRoadCode runtime service managers. */
public final class RuntimeServiceManagerSettings {
  private static final String PREFS = "runtime_service_manager";
  private static final String KEY_TARGET = "target";
  private static final String KEY_ACTIVE_DEVICE_ID = "active_device_id";
  private static final String KEY_DEVICES = "remote_devices";

  // Legacy single-device keys retained only for one-time migration.
  private static final String KEY_PI_BASE_URL = "pi_base_url";
  private static final String KEY_PI_TOKEN = "pi_token";

  public enum Target {
    TERMUX,
    REMOTE_PI
  }

  public record RuntimeDevice(
      String deviceId,
      String name,
      String baseUrl,
      String clientId,
      String accessToken) {}

  private final SharedPreferences preferences;

  public RuntimeServiceManagerSettings(Context context) {
    preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    migrateLegacyRemote();
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

  public List<RuntimeDevice> devices() {
    List<RuntimeDevice> result = new ArrayList<>();
    String encoded = preferences.getString(KEY_DEVICES, "[]");
    try {
      JSONArray array = new JSONArray(encoded == null ? "[]" : encoded);
      for (int i = 0; i < array.length(); i++) {
        JSONObject item = array.optJSONObject(i);
        if (item == null) continue;
        String deviceId = item.optString("device_id", "").trim();
        String baseUrl = normalizeBaseUrl(item.optString("base_url", ""));
        String token = item.optString("access_token", "").trim();
        if (deviceId.isBlank() || baseUrl.isBlank() || token.isBlank()) continue;
        result.add(new RuntimeDevice(
            deviceId,
            item.optString("name", "Remote Linux").trim(),
            baseUrl,
            item.optString("client_id", "").trim(),
            token));
      }
    } catch (JSONException ignored) {
      // Treat corrupt persisted configuration as empty rather than crashing the UI.
    }
    return result;
  }

  public RuntimeDevice activeDevice() {
    String activeId = preferences.getString(KEY_ACTIVE_DEVICE_ID, "");
    for (RuntimeDevice device : devices()) {
      if (device.deviceId().equals(activeId)) return device;
    }
    return null;
  }

  public RuntimeDevice saveDevice(
      String name, String baseUrl, String clientId, String accessToken) {
    String normalizedUrl = normalizeBaseUrl(baseUrl);
    String token = accessToken == null ? "" : accessToken.trim();
    if (normalizedUrl.isBlank() || token.isBlank()) {
      throw new IllegalArgumentException("Remote endpoint and access token are required");
    }

    List<RuntimeDevice> devices = devices();
    RuntimeDevice existing = null;
    for (RuntimeDevice device : devices) {
      if (device.baseUrl().equals(normalizedUrl)) {
        existing = device;
        break;
      }
    }

    RuntimeDevice saved = new RuntimeDevice(
        existing == null ? UUID.randomUUID().toString() : existing.deviceId(),
        name == null || name.isBlank() ? "Remote Linux" : name.trim(),
        normalizedUrl,
        clientId == null ? "" : clientId.trim(),
        token);

    if (existing != null) devices.remove(existing);
    devices.add(saved);
    persistDevices(devices);
    setActiveDevice(saved.deviceId());
    return saved;
  }

  public void setActiveDevice(String deviceId) {
    preferences.edit()
        .putString(KEY_ACTIVE_DEVICE_ID, deviceId == null ? "" : deviceId)
        .putString(KEY_TARGET, Target.REMOTE_PI.name())
        .apply();
  }

  public boolean renameDevice(String deviceId, String name) {
    String normalizedName = name == null ? "" : name.trim();
    if (normalizedName.isBlank()) return false;
    List<RuntimeDevice> devices = devices();
    for (int i = 0; i < devices.size(); i++) {
      RuntimeDevice device = devices.get(i);
      if (!device.deviceId().equals(deviceId)) continue;
      devices.set(i, new RuntimeDevice(
          device.deviceId(), normalizedName, device.baseUrl(), device.clientId(), device.accessToken()));
      persistDevices(devices);
      return true;
    }
    return false;
  }

  public boolean updateDevice(String deviceId, String name, String baseUrl) {
    String normalizedName = name == null ? "" : name.trim();
    String normalizedUrl = normalizeBaseUrl(baseUrl);
    if (normalizedName.isBlank() || normalizedUrl.isBlank()) return false;
    List<RuntimeDevice> devices = devices();
    for (int i = 0; i < devices.size(); i++) {
      RuntimeDevice device = devices.get(i);
      if (!device.deviceId().equals(deviceId)) continue;
      devices.set(i, new RuntimeDevice(
          device.deviceId(), normalizedName, normalizedUrl, device.clientId(), device.accessToken()));
      persistDevices(devices);
      return true;
    }
    return false;
  }

  public boolean forgetDevice(String deviceId) {
    List<RuntimeDevice> devices = devices();
    boolean removed = devices.removeIf(device -> device.deviceId().equals(deviceId));
    if (!removed) return false;

    persistDevices(devices);
    RuntimeDevice active = activeDevice();
    if (active == null) {
      SharedPreferences.Editor editor = preferences.edit();
      if (devices.isEmpty()) {
        editor.remove(KEY_ACTIVE_DEVICE_ID).putString(KEY_TARGET, Target.TERMUX.name());
      } else {
        editor.putString(KEY_ACTIVE_DEVICE_ID, devices.get(0).deviceId());
      }
      editor.apply();
    }
    return true;
  }

  public boolean hasRemotePiConfiguration() {
    return activeDevice() != null;
  }

  // Compatibility accessors for existing callers while the UI moves to devices.
  public String piBaseUrl() {
    RuntimeDevice device = activeDevice();
    return device == null ? "" : device.baseUrl();
  }

  public String piToken() {
    RuntimeDevice device = activeDevice();
    return device == null ? "" : device.accessToken();
  }

  private void migrateLegacyRemote() {
    if (!devices().isEmpty()) return;
    String baseUrl = normalizeBaseUrl(preferences.getString(KEY_PI_BASE_URL, ""));
    String token = preferences.getString(KEY_PI_TOKEN, "");
    if (baseUrl.isBlank() || token == null || token.isBlank()) return;

    Target legacyTarget = target();
    RuntimeDevice migrated = saveDevice(
        "Remote Linux", baseUrl, "", token);
    SharedPreferences.Editor editor = preferences.edit()
        .remove(KEY_PI_BASE_URL)
        .remove(KEY_PI_TOKEN);
    if (legacyTarget != Target.REMOTE_PI) {
      editor.putString(KEY_TARGET, Target.TERMUX.name());
    } else {
      editor.putString(KEY_ACTIVE_DEVICE_ID, migrated.deviceId());
    }
    editor.apply();
  }

  private void persistDevices(List<RuntimeDevice> devices) {
    JSONArray array = new JSONArray();
    for (RuntimeDevice device : devices) {
      JSONObject item = new JSONObject();
      try {
        item.put("device_id", device.deviceId());
        item.put("name", device.name());
        item.put("base_url", device.baseUrl());
        item.put("client_id", device.clientId());
        item.put("access_token", device.accessToken());
      } catch (JSONException e) {
        throw new IllegalStateException("Failed to encode runtime device", e);
      }
      array.put(item);
    }
    preferences.edit().putString(KEY_DEVICES, array.toString()).apply();
  }

  private static String normalizeBaseUrl(String value) {
    if (value == null) return "";
    String result = value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
