package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.runtime.BridgeServiceManager;
import org.openroadcode.androidbridge.ui.UiTheme;

/** SMS permissions and explicit opt-in controls. Does not reveal credentials. */
public final class SmsGatewayCard {
  public static final int READ_PERMISSION_REQUEST = 1082;
  public static final int SEND_PERMISSION_REQUEST = 1083;
  public static final int CONTACTS_PERMISSION_REQUEST = 1084;
  private final Activity activity;
  private final BridgeServiceManager manager;
  private final LinearLayout root;
  private final TextView status;
  private final TextView permissionFeedback;
  private boolean enableAfterReadPermission;
  private boolean connectAfterReadPermission;
  private volatile boolean closed;
  private java.util.concurrent.Future<?> connectionTask;
  private java.util.concurrent.atomic.AtomicBoolean activeConnection;
  private final java.util.concurrent.ExecutorService connectionWorker =
      java.util.concurrent.Executors.newSingleThreadExecutor();
  private android.app.AlertDialog connectionDialog;

  public SmsGatewayCard(Activity activity, BridgeServiceManager manager) {
    this.activity = activity;
    this.manager = manager;
    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 14),
        UiTheme.dp(activity, 14), UiTheme.dp(activity, 14));
    root.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE, UiTheme.GREEN, 12));
    root.addView(UiTheme.text(activity, org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity) ? "SMS GATEWAY" : "Messages", 16, UiTheme.GREEN));
    root.addView(UiTheme.text(activity,
        org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity) ? "SMS only. Explicit permission and opt-in required. Local endpoint: 127.0.0.1:8773" : "Tap Connect messaging to set up or repair access through Termux on this phone. You will approve in your browser. No secret tokens to copy. Remote computers still need a secure messaging connection.",
        12, UiTheme.MUTED));
    status = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    root.addView(status);
    permissionFeedback = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    root.addView(permissionFeedback);
    root.addView(UiTheme.actionButton(activity, "Connect messaging", UiTheme.BLUE,
        v -> connectMessaging()));
    root.addView(UiTheme.actionButton(activity, SmsGatewayPresentation.enableLabel(org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity)), UiTheme.BLUE,
        v -> enable()));
    root.addView(UiTheme.actionButton(activity, "Allow contacts lookup", UiTheme.SURFACE_RAISED,
        v -> requestContactsPermission()));
    if (org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity)) root.addView(UiTheme.actionButton(activity, "Request SMS sending permission", UiTheme.SURFACE_RAISED,
        v -> requestSendPermission()));
    if (org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity)) root.addView(UiTheme.actionButton(activity, "Manual SMS provisioning (legacy)", UiTheme.SURFACE_RAISED,
        v -> provisionToTermux()));
    root.addView(UiTheme.actionButton(activity, "Open Android app settings", UiTheme.SURFACE_RAISED,
        v -> openAppSettings()));
    root.addView(UiTheme.actionButton(activity, org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity) ? "Disable SMS gateway" : "Turn off messages", UiTheme.SURFACE_RAISED,
        v -> {
          cancelConnection();
          connectAfterReadPermission = false;
          enableAfterReadPermission = false;
          manager.stopSmsGateway();
          refresh();
        }));
    refresh();
  }

  public View view() { return root; }

  public void refresh() {
    boolean read = activity.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    boolean send = activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    boolean contacts = activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    status.setTextColor(read && manager.smsEnabled() && SmsGatewayService.isListening() ? UiTheme.GREEN : UiTheme.MUTED);
    if (!org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity)) {
      status.setText(SmsGatewayPresentation.simpleStatus(
          manager.smsEnabled(), read, send, SmsGatewayService.isListening()));
      return;
    }
    status.setText("Mode: " + (read ? (send ? "read and send" : "read-only") : "permissions required")
        + "\nConfigured: " + (manager.smsEnabled() ? "enabled" : "disabled")
        + "  •  Read permission: " + (read ? "granted" : "missing")
        + "  •  Send permission: " + (send ? "granted" : "missing")
        + "\nContacts permission: " + (contacts ? "granted" : "missing")
        + "\nListener: " + (SmsGatewayService.isListening() ? "connected" : "not running")
        + ". Termux credential setup is separate.");
  }

  private void connectMessaging() {
    if (closed || (connectionTask != null && !connectionTask.isDone())) return;
    if (activity.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
      connectAfterReadPermission = true;
      activity.requestPermissions(new String[] {Manifest.permission.READ_SMS}, READ_PERMISSION_REQUEST);
      return;
    }
    try {
      manager.startSmsGateway();
    } catch (RuntimeException error) {
      permissionFeedback.setText("Could not enable phone message access. Try Connect messaging again.");
      return;
    }
    permissionFeedback.setText("Opening local messaging approval…");
    final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    activeConnection = cancelled;
    connectionDialog = new android.app.AlertDialog.Builder(activity)
        .setTitle("Connect messaging")
        .setMessage("Approve messaging in your browser, then return here. Setup sends no SMS. "
            + "If the browser does not open, cancel and update/restart OpenRoadCode in Termux.")
        .setNegativeButton("Cancel", (dialog, which) -> { cancelled.set(true); cancelConnection(); })
        .setOnCancelListener(dialog -> { cancelled.set(true); cancelConnection(); })
        .create();
    connectionDialog.show();
    connectionTask = connectionWorker.submit(() -> {
      SmsConnectionSetup.Session session = null;
      try {
        session = SmsConnectionSetup.begin();
        final SmsConnectionSetup.Session activeSession = session;
        activity.runOnUiThread(() -> {
          if (closed || cancelled.get()) return;
          try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(activeSession.approvalUrl)));
          } catch (RuntimeException error) {
            cancelled.set(true);
            finishConnection("No browser available. Install or enable a browser and try again.");
          }
        });
        long deadline = android.os.SystemClock.elapsedRealtime() + 180_000;
        boolean approved = false;
        while (!closed && !cancelled.get() && !Thread.currentThread().isInterrupted()
            && android.os.SystemClock.elapsedRealtime() < deadline) {
          String state = SmsConnectionSetup.status(session);
          if ("approved".equals(state)) { approved = true; break; }
          if (!"pending".equals(state)) throw new SmsConnectionSetup.SetupFailure("Setup cancelled or closed. Tap Connect messaging to retry.");
          Thread.sleep(1000);
        }
        if (!approved || closed || cancelled.get() || Thread.currentThread().isInterrupted()) {
          if (!closed && !cancelled.get()) activity.runOnUiThread(() -> {
            if (!closed && !cancelled.get()) finishConnection("Setup expired. Tap Connect messaging to retry.");
          });
          return;
        }
        SmsConnectionSetup.complete(session, SmsGatewaySettings.token(activity));
        activity.runOnUiThread(() -> {
          if (closed || cancelled.get()) return;
          // Verify the actual proxy with its private client credential via the
          // backend's verified completion, rather than treating saved enable as success.
          finishConnection("Messaging connected and verified with Termux on this phone. No SMS was sent. "
              + "A remote desktop still uses its separate SMS credential and secure connection.");
          refresh();
        });
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      } catch (Exception error) {
        if (!closed && !cancelled.get()) {
          // Only transport-generated fixed messages are safe to present.
          String message = error instanceof SmsConnectionSetup.SetupFailure ? error.getMessage()
              : "Cannot reach messaging setup. Update/restart OpenRoadCode in Termux on this phone, then try again.";
          activity.runOnUiThread(() -> { if (!closed && !cancelled.get()) finishConnection(message); });
        }
      } finally {
        if (session != null) SmsConnectionSetup.cancel(session);
      }
    });
  }

  private void finishConnection(String feedback) {
    activity.runOnUiThread(() -> {
      if (closed) return;
      permissionFeedback.setText(feedback);
      if (connectionDialog != null) { connectionDialog.dismiss(); connectionDialog = null; }
    });
  }

  private void cancelConnection() {
    if (activeConnection != null) activeConnection.set(true);
    if (connectionTask != null) connectionTask.cancel(true);
    if (!closed) finishConnection("Messaging setup cancelled. No SMS was sent.");
  }

  public void close() {
    closed = true;
    cancelConnection();
    if (connectionDialog != null) { connectionDialog.dismiss(); connectionDialog = null; }
    connectionWorker.shutdownNow();
  }

  private void provisionToTermux() {
    if (!manager.smsEnabled()) {
      permissionFeedback.setText("Tap " + SmsGatewayPresentation.enableLabel(
          org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity)) + " first.");
      return;
    }
    final android.widget.EditText tokenInput = new android.widget.EditText(activity);
    tokenInput.setHint("Separate provisioning token");
    tokenInput.setSingleLine(true);
    tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    new android.app.AlertDialog.Builder(activity)
        .setTitle("Provision SMS to local Termux")
        .setMessage("Transfers the SMS gateway credential only to 127.0.0.1:8769. "
            + "Requires the separate provisioning token configured in Termux.")
        .setView(tokenInput)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Provision", (dialog, which) -> {
          String secret = tokenInput.getText().toString();
          tokenInput.setText("");
          permissionFeedback.setText("Provisioning SMS credential...");
          new Thread(() -> {
            String message;
            try {
              SmsCredentialProvisioner.provisionLocal(activity.getApplicationContext(), secret, 8769);
              message = "SMS credential provisioned to local Termux.";
            } catch (Exception error) {
              // Never display credentials or exception messages that may contain them.
              message = "Provisioning failed. Check the Termux service manager and token.";
            }
            final String feedback = message;
            activity.runOnUiThread(() -> permissionFeedback.setText(feedback));
          }, "sms-credential-provision").start();
        })
        .show();
  }

  private void requestContactsPermission() {
    if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
      permissionFeedback.setText("Contacts access already granted.");
      refresh();
      return;
    }
    activity.requestPermissions(new String[] {Manifest.permission.READ_CONTACTS}, CONTACTS_PERMISSION_REQUEST);
  }

  private void requestSendPermission() {
    if (activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
      permissionFeedback.setText("SMS sending permission is already granted.");
      refresh();
      return;
    }
    boolean rationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS);
    permissionFeedback.setText(rationale
        ? "Android previously denied SMS sending. Requesting permission again..."
        : "Requesting SMS sending permission from Android. If no dialog appears, check app settings.");
    activity.requestPermissions(new String[] {Manifest.permission.SEND_SMS}, SEND_PERMISSION_REQUEST);
  }

  public void onPermissionResult(int requestCode) {
    refresh();
    if (requestCode == CONTACTS_PERMISSION_REQUEST) {
      permissionFeedback.setText(activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
          ? "Contacts access granted for read-only lookup."
          : "Contacts access denied. Manual phone number entry still works.");
    } else if (requestCode == SEND_PERMISSION_REQUEST) {
      boolean granted = activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
      boolean rationale = activity.shouldShowRequestPermissionRationale(Manifest.permission.SEND_SMS);
      permissionFeedback.setText(granted
          ? "SMS sending permission granted. No message was sent."
          : rationale
              ? "SMS sending was denied. Tap Request SMS sending permission to try again. Read-only messaging still works."
              : "SMS sending remains denied. If Android did not show a prompt, open Android app settings to check SMS access. Read-only messaging still works.");
    } else if (requestCode == READ_PERMISSION_REQUEST) {
      boolean granted = activity.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
      boolean connectRequested = connectAfterReadPermission;
      connectAfterReadPermission = false;
      boolean startRequested = enableAfterReadPermission;
      enableAfterReadPermission = false;
      if (granted && connectRequested) {
        connectMessaging();
      } else if (granted && startRequested) {
        enable();
      } else {
        permissionFeedback.setText(SmsGatewayPresentation.readPermissionFeedback(
            org.openroadcode.androidbridge.config.UiModeSettings.advanced(activity), granted));
      }
    }
  }

  private void openAppSettings() {
    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + activity.getPackageName()));
    activity.startActivity(intent);
  }

  private void enable() {
    boolean read = activity.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    if (!read) {
      enableAfterReadPermission = true;
      activity.requestPermissions(new String[] {Manifest.permission.READ_SMS}, READ_PERMISSION_REQUEST);
      return;
    }
    try {
      manager.startSmsGateway();
      permissionFeedback.setText("Message access requested. No SMS was sent.");
    } catch (RuntimeException error) {
      permissionFeedback.setText("Unable to start message access. Try enabling it again.");
    }
    refresh();
  }
}
