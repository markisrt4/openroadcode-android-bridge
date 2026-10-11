package org.openroadcode.androidbridge.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Presentation preference only: changing modes never changes service configuration. */
public final class UiModeSettings {
  private final SharedPreferences preferences;

  public UiModeSettings(Context context) {
    this(context.getSharedPreferences("companion_ui", Context.MODE_PRIVATE));
  }

  UiModeSettings(SharedPreferences preferences) { this.preferences = preferences; }

  public boolean advanced() { return "advanced".equals(preferences.getString("mode", "simple")); }

  public void setAdvanced(boolean advanced) {
    preferences.edit().putString("mode", advanced ? "advanced" : "simple").apply();
  }

  public static boolean advanced(Context context) { return new UiModeSettings(context).advanced(); }
}
