package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Handles explicit, app-private SMS sent-result callbacks. */
public final class SmsSendResultReceiver extends BroadcastReceiver {
  public static final String ACTION = "org.openroadcode.androidbridge.SMS_SENT_RESULT";

  @Override public void onReceive(Context context, Intent intent) {
    if (intent == null || !ACTION.equals(intent.getAction())) return;
    String id = intent.getStringExtra("request_id");
    if (id == null || !id.matches("[A-Za-z0-9_-]{16,128}")) return;
    int part = intent.getIntExtra("part", -1);
    int count = intent.getIntExtra("count", -1);
    if (count < 1 || count > 20 || part < 0 || part >= count) return;
    synchronized (SmsSendResultReceiver.class) {
      SharedPreferences prefs = context.getSharedPreferences("orc_sms_send_requests", Context.MODE_PRIVATE);
      if (!prefs.contains(id)) return;
      String key = id + ".part." + part;
      if (prefs.contains(key)) return;
      boolean ok = getResultCode() == Activity.RESULT_OK;
      SharedPreferences.Editor editor = prefs.edit().putBoolean(key, ok);
      int completed = 0;
      boolean allOk = true;
      for (int i = 0; i < count; i++) {
        if (i == part) {
          completed++;
          allOk &= ok;
        } else if (prefs.contains(id + ".part." + i)) {
          completed++;
          allOk &= prefs.getBoolean(id + ".part." + i, false);
        }
      }
      if (completed == count) editor.putString(id + ".status", allOk ? "sent" : "failed");
      editor.apply();
    }
  }
}
