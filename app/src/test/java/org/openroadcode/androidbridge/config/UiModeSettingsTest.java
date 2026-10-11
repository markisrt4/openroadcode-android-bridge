package org.openroadcode.androidbridge.config;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class UiModeSettingsTest {
  @Test public void freshInstallAndUnrecognizedPreferencesUseSimpleMode() {
    Store store = new Store();
    assertFalse(new UiModeSettings(store.preferences).advanced());
    store.values.put("mode", "invalid");
    assertFalse(new UiModeSettings(store.preferences).advanced());
  }

  @Test public void modeSurvivesReloadAndCanReturnToSimple() {
    Store store = new Store();
    new UiModeSettings(store.preferences).setAdvanced(true);
    assertTrue(new UiModeSettings(store.preferences).advanced());
    new UiModeSettings(store.preferences).setAdvanced(false);
    assertFalse(new UiModeSettings(store.preferences).advanced());
  }

  @Test public void togglingDoesNotChangeServiceOrPairingSettings() {
    Store store = new Store();
    store.values.put("sensor_enabled", "true");
    store.values.put("target", "REMOTE_PI");
    store.values.put("sms_enabled", "false");
    new UiModeSettings(store.preferences).setAdvanced(true);
    new UiModeSettings(store.preferences).setAdvanced(false);
    assertEquals("true", store.values.get("sensor_enabled"));
    assertEquals("REMOTE_PI", store.values.get("target"));
    assertEquals("false", store.values.get("sms_enabled"));
    assertEquals(4, store.values.size());
  }

  private static final class Store {
    final Map<String, String> values = new HashMap<>();
    final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
        SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getString" -> values.getOrDefault((String) args[0], (String) args[1]);
          case "edit" -> Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
              new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, arguments) -> {
                if (operation.getName().equals("putString")) {
                  values.put((String) arguments[0], (String) arguments[1]); return editor;
                }
                if (operation.getName().equals("apply")) return null;
                throw new UnsupportedOperationException(operation.getName());
              });
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}
