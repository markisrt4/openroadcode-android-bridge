package org.openroadcode.androidbridge.config;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.Target;

public final class RuntimeServiceManagerSettingsTest {
  @Test public void migratesLegacyPairingOnceAndPreservesLocalTarget() {
    Store store = new Store();
    store.values.put("pi_base_url", "http://unit:8769/");
    store.values.put("pi_token", "old-token");
    RuntimeServiceManagerSettings settings = new RuntimeServiceManagerSettings(store.preferences);
    assertEquals(Target.TERMUX, settings.target());
    assertEquals("http://unit:8769", settings.piBaseUrl());
    assertEquals("old-token", settings.piToken());
    assertFalse(store.values.containsKey("pi_token"));
    assertEquals(1, new RuntimeServiceManagerSettings(store.preferences).devices().size());
  }

  @Test public void switchingUnitsChangesBothEndpointAndCredential() {
    RuntimeServiceManagerSettings settings = new RuntimeServiceManagerSettings(new Store().preferences);
    var first = settings.saveDevice("One", "http://one:8769", "client-1", "token-1");
    settings.saveDevice("Two", "http://two:8769/", "client-2", "token-2");
    assertEquals("http://two:8769", settings.piBaseUrl());
    assertEquals("token-2", settings.piToken());
    settings.setActiveDevice(first.deviceId());
    assertEquals(Target.REMOTE_PI, settings.target());
    assertEquals("http://one:8769", settings.piBaseUrl());
    assertEquals("token-1", settings.piToken());
    settings.setTarget(Target.TERMUX);
    assertEquals(first.deviceId(), settings.activeDevice().deviceId());
  }

  @Test public void repairingEndpointUpdatesExistingDeviceRatherThanDuplicating() {
    RuntimeServiceManagerSettings settings = new RuntimeServiceManagerSettings(new Store().preferences);
    var original = settings.saveDevice("One", "http://one:8769", "client-1", "token-1");
    var repaired = settings.saveDevice("Renamed", "http://one:8769/", "client-2", "token-2");
    assertEquals(original.deviceId(), repaired.deviceId());
    assertEquals(1, settings.devices().size());
    assertEquals("token-2", settings.piToken());
  }

  @Test public void deletingActiveUnitSelectsRemainingUnitThenFallsBackToTermux() {
    RuntimeServiceManagerSettings settings = new RuntimeServiceManagerSettings(new Store().preferences);
    var first = settings.saveDevice("One", "http://one:8769", "client-1", "token-1");
    var second = settings.saveDevice("Two", "http://two:8769", "client-2", "token-2");
    assertTrue(settings.forgetDevice(second.deviceId()));
    assertEquals(first.deviceId(), settings.activeDevice().deviceId());
    assertTrue(settings.forgetDevice(first.deviceId()));
    assertEquals(Target.TERMUX, settings.target());
    assertFalse(settings.hasRemotePiConfiguration());
  }

  @Test public void corruptStoredDevicesDoNotCrashOrInventPairing() {
    Store store = new Store();
    store.values.put("remote_devices", "broken JSON");
    RuntimeServiceManagerSettings settings = new RuntimeServiceManagerSettings(store.preferences);
    assertTrue(settings.devices().isEmpty());
    assertNull(settings.activeDevice());
  }

  /** Interface-only preference fixture; runs without an Android device. */
  private static final class Store {
    final Map<String, String> values = new HashMap<>();
    final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
        SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
        (proxy, method, args) -> switch (method.getName()) {
          case "getString" -> values.getOrDefault((String) args[0], (String) args[1]);
          case "edit" -> editor();
          default -> throw new UnsupportedOperationException(method.getName());
        });

    private SharedPreferences.Editor editor() {
      Map<String, String> pending = new HashMap<>();
      return (SharedPreferences.Editor) Proxy.newProxyInstance(
          SharedPreferences.Editor.class.getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class},
          (proxy, method, args) -> {
            switch (method.getName()) {
              case "putString": pending.put((String) args[0], (String) args[1]); return proxy;
              case "remove": pending.put((String) args[0], null); return proxy;
              case "apply":
                pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                return null;
              default: throw new UnsupportedOperationException(method.getName());
            }
          });
    }
  }
}
