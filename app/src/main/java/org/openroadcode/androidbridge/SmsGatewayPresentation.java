package org.openroadcode.androidbridge;

/** Mode-aware SMS wording based on both saved intent and observed listener state. */
public final class SmsGatewayPresentation {
  private SmsGatewayPresentation() {}

  public static String enableLabel(boolean advanced) {
    return advanced ? "Enable SMS gateway" : "Enable messages";
  }

  public static String simpleStatus(boolean enabled, boolean read, boolean send, boolean listening) {
    if (!read) return "Messages need Android permission. Tap Enable messages.";
    if (!enabled) return "Messages turned off. Tap Enable messages.";
    if (!listening) return "Messages enabled, but the listener is not running. Tap Enable messages to retry.";
    return "Messages are running" + (send ? "" : " · Read only");
  }

  public static String readPermissionFeedback(boolean advanced, boolean granted) {
    return granted ? "SMS reading permission granted. Tap " + enableLabel(advanced) + " to start."
        : "Android did not grant SMS reading. Check app settings.";
  }
}
