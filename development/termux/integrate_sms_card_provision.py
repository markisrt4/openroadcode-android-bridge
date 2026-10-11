#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Integrate explicit local SMS provisioning action into the existing card."""
from pathlib import Path

path = Path("app/src/main/java/org/openroadcode/androidbridge/SmsGatewayCard.java")
source = path.read_text()
needle = '    root.addView(UiTheme.actionButton(activity, "Open Android app settings", UiTheme.SURFACE_RAISED,'
addition = '''    root.addView(UiTheme.actionButton(activity, "Provision SMS to Termux", UiTheme.SURFACE_RAISED,
        v -> provisionToTermux()));
'''
if addition not in source:
    if source.count(needle) != 1:
        raise SystemExit("SMS card insertion point changed; refusing to edit")
    source = source.replace(needle, addition + needle, 1)

needle = "  private void requestSendPermission() {"
addition = '''  private void provisionToTermux() {
    if (!manager.smsEnabled()) {
      permissionFeedback.setText("Enable SMS gateway first.");
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

'''
if addition not in source:
    if source.count(needle) != 1:
        raise SystemExit("SMS card method insertion point changed; refusing to edit")
    source = source.replace(needle, addition + needle, 1)
path.write_text(source)
print("SMS card provisioning action integrated.")
