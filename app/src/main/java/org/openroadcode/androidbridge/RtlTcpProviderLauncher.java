package org.openroadcode.androidbridge;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.Map;

/** SDR launch handling shares the host-action listener; it does not bind a second socket. */
final class RtlTcpProviderLauncher {
  private static final String DRIVER_PACKAGE = "marto.rtl_tcp_andro";
  private static final String DRIVER_ACTIVITY = "com.sdrtouch.rtlsdr.DeviceOpenActivity";
  private static final int DEFAULT_FREQUENCY_HZ = 104_300_000;
  private static final int DEFAULT_SAMPLE_RATE = 2_400_000;
  static final class ForegroundRequiredException extends RuntimeException {
    ForegroundRequiredException(String message) { super(message); }
  }
  static String startProvider(android.content.Context context, Map<String, String> form) {
    return launchProvider(context, positiveInt(form.get("frequency_hz"), DEFAULT_FREQUENCY_HZ),
        positiveInt(form.get("sample_rate"), DEFAULT_SAMPLE_RATE), positiveInt(form.get("port"), 1234));
  }
  private static String launchProvider(android.content.Context context, int frequencyHz, int sampleRate, int port) {
    String uri = "iqsrc://-a 127.0.0.1 -p " + port
        + " -f " + frequencyHz + " -s " + sampleRate + " -T 0";
    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
    intent.setClassName(DRIVER_PACKAGE, DRIVER_ACTIVITY);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, 0);
    if (resolved == null || resolved.activityInfo == null) {
      String message = "RTL-TCP driver activity is not resolvable: "
          + DRIVER_PACKAGE + "/" + DRIVER_ACTIVITY;
      android.util.Log.e("ORCRtlTcpControl", message);
      throw new IllegalStateException(message);
    }

    ActivityInfo activity = resolved.activityInfo;
    String resolvedComponent = activity.packageName + "/" + activity.name;
    android.util.Log.i("ORCRtlTcpControl",
        "Resolved RTL-TCP provider activity: " + resolvedComponent
            + " exported=" + activity.exported + " enabled=" + activity.enabled);

    if (!activity.enabled) {
      throw new IllegalStateException("RTL-TCP driver activity is disabled: " + resolvedComponent);
    }
    if (!activity.exported && !context.getPackageName().equals(activity.packageName)) {
      throw new IllegalStateException("RTL-TCP driver activity is not exported: " + resolvedComponent);
    }

    if (!MainActivity.launchRtlTcpProvider(uri, DRIVER_PACKAGE, DRIVER_ACTIVITY)) {
      android.util.Log.w("ORCRtlTcpControl",
          "RTL-TCP launch deferred because the bridge activity is not visible");
      throw new ForegroundRequiredException(
          "ORC Companion must be visible to launch the Android RTL-TCP provider");
    }

    android.util.Log.i("ORCRtlTcpControl", "Requested RTL-TCP provider from visible activity: " + uri);
    return "{\"status\":\"launch_requested\","
        + "\"launch_context\":\"visible_activity\","
        + "\"resolved_component\":\"" + jsonEscape(resolvedComponent) + "\","
        + "\"exported\":" + activity.exported + ","
        + "\"enabled\":" + activity.enabled + "}";
  }

  private static int positiveInt(String value, int fallback) {
    if (value == null || value.isEmpty()) return fallback;
    int parsed = Integer.parseInt(value);
    if (parsed <= 0) throw new IllegalArgumentException("Expected positive integer");
    return parsed;
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }

}
