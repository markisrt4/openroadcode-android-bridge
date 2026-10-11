package org.openroadcode.androidbridge;

import android.content.Context;
import java.security.SecureRandom;
import java.util.Base64;

/** Explicit local SMS opt-in and app-private bearer credential. */
public final class SmsGatewaySettings {
  private static final String FILE = "sms_gateway";
  private static final String ENABLED = "enabled";
  private static final String TOKEN = "token";

  private SmsGatewaySettings() {}

  public static boolean enabled(Context context) {
    return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
  }

  public static void setEnabled(Context context, boolean enabled) {
    context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply();
  }

  public static String token(Context context) {
    android.content.SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    String existing = prefs.getString(TOKEN, null);
    if (existing != null && !existing.isEmpty()) return existing;
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    // Only generate when explicitly enabling, not merely when checking status.
    prefs.edit().putString(TOKEN, generated).commit();
    return generated;
  }
}
