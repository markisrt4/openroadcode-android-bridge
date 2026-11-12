package org.openroadcode.androidbridge;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Displays USB detection without opening or claiming the device. */
final class RadioCard {
    private final OpenRoadCodeBridgeApplication application;
    private final LinearLayout root;
    private final TextView status;
    private final TextView device;

    RadioCard(Activity activity) {
        application = (OpenRoadCodeBridgeApplication) activity.getApplication();
        root = UiTheme.card(activity);
        root.addView(UiTheme.text(activity,
            "SDR playback and USB access run directly in Termux through libSDRForge. "
            + "Use the Termux RTL-SDR setup script to request USB permission.", 12, UiTheme.MUTED));
        status = UiTheme.text(activity, "", 14, UiTheme.TEXT);
        root.addView(status);
        device = UiTheme.text(activity, "", 11, UiTheme.MUTED);
        root.addView(device);
    }
    View view() { return root; }
    void refresh() {
        RtlSdrUsbManager.State state = application.getRtlSdrUsbManager().refresh();
        status.setText(state.message);
        status.setTextColor(state.device != null ? UiTheme.GREEN : UiTheme.MUTED);
        device.setText(state.device == null ? "Attach an RTL-SDR USB receiver."
            : state.friendlyName() + " • " + state.deviceLabel());
    }
}
