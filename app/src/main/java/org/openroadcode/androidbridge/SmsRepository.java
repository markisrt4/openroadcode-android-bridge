package org.openroadcode.androidbridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.SmsManager;
import android.provider.Telephony;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * SMS access boundary. Call only after explicit user consent and runtime permission grants.
 * Never log message bodies, recipients, or the results of provider queries.
 */
public final class SmsRepository {
  private final Context context;

  public SmsRepository(Context context) {
    this.context = context.getApplicationContext();
  }

  public boolean canRead() {
    return context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
  }

  public boolean canSend() {
    return context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
  }

  public JSONArray messages(long threadId, int limit) throws Exception {
    if (!canRead()) throw new SecurityException("SMS read permission required");
    if (threadId < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid SMS query");
    JSONArray result = new JSONArray();
    String[] columns = {
        Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE
    };
    String selection = threadId == 0 ? null : Telephony.Sms.THREAD_ID + "=?";
    String[] args = threadId == 0 ? null : new String[] {Long.toString(threadId)};
    try (Cursor cursor = context.getContentResolver().query(
        Telephony.Sms.CONTENT_URI, columns, selection, args, Telephony.Sms.DATE + " DESC")) {
      if (cursor == null) return result;
      while (cursor.moveToNext() && result.length() < limit) {
        JSONObject message = new JSONObject();
        message.put("id", cursor.getLong(0));
        message.put("thread_id", cursor.getLong(1));
        message.put("address", cursor.getString(2));
        message.put("body", cursor.getString(3));
        message.put("date", cursor.getLong(4));
        message.put("type", cursor.getInt(5));
        result.put(message);
      }
    }
    return result;
  }

  public JSONArray conversations(int limit) throws Exception {
    if (!canRead()) throw new SecurityException("SMS read permission required");
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid SMS query");
    JSONArray result = new JSONArray();
    // Group newest-first messages by thread, avoiding provider-specific conversation columns.
    java.util.HashSet<Long> seen = new java.util.HashSet<>();
    try (Cursor cursor = context.getContentResolver().query(
        Telephony.Sms.CONTENT_URI,
        new String[] {Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE},
        null, null, Telephony.Sms.DATE + " DESC")) {
      if (cursor == null) return result;
      while (cursor.moveToNext() && result.length() < limit) {
        long id = cursor.getLong(0);
        if (id <= 0 || !seen.add(id)) continue;
        JSONObject thread = new JSONObject();
        thread.put("thread_id", id);
        thread.put("address", cursor.getString(1));
        thread.put("date", cursor.getLong(2));
        result.put(thread);
      }
    }
    return result;
  }

  public void send(String address, String body) {
    if (!canSend()) throw new SecurityException("SMS send permission required");
    if (address == null || !address.matches("[+0-9() .-]{3,32}"))
      throw new IllegalArgumentException("Invalid recipient");
    if (body == null || body.isEmpty() || body.length() > 1600)
      throw new IllegalArgumentException("Invalid SMS body");
    SmsManager manager = context.getSystemService(SmsManager.class);
    if (manager == null) throw new IllegalStateException("SMS service unavailable");
    java.util.ArrayList<String> parts = manager.divideMessage(body);
    if (parts.size() == 1) manager.sendTextMessage(address, null, body, null, null);
    else manager.sendMultipartTextMessage(address, null, parts, null, null);
  }
}
