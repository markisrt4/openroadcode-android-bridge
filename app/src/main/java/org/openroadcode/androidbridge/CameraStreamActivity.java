package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Collections;

public final class CameraStreamActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 1003;
    private static final int BG = Color.rgb(6, 16, 24);
    private static final int SURFACE = Color.rgb(11, 24, 33);
    private static final int BORDER = Color.rgb(36, 64, 79);
    private static final int TEXT = Color.rgb(243, 247, 249);
    private static final int MUTED = Color.rgb(147, 164, 174);
    private static final int BLUE = Color.rgb(22, 139, 209);
    private static final int GREEN = Color.rgb(132, 206, 31);
    private static final int RED = Color.rgb(241, 90, 22);

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(28), dp(22), dp(28));
        content.setBackgroundColor(BG);

        TextView title = text("OPEN ROAD CODE", 25, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        content.addView(title);

        TextView subtitle = text("CAMERA STREAM • v" + BuildConfig.VERSION_NAME, 12, BLUE);
        subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        subtitle.setLetterSpacing(0.12f);
        subtitle.setPadding(0, dp(3), 0, dp(24));
        content.addView(subtitle);

        TextView heading = text("REAR CAMERA", 19, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(heading);
        content.addView(text("H.264 • 1280×720 • 30 FPS • ~3 Mbps", 13, GREEN));

        status = text("●  Camera stopped", 14, MUTED);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        status.setBackground(rounded(SURFACE, BORDER, 10));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(18), 0, dp(16));
        content.addView(status, statusParams);

        String address = findLanAddress();
        TextView endpoint = text(address == null
                ? "Endpoint: waiting for network • port " + CameraStreamService.PORT
                : "Video:  http://" + address + ":" + CameraStreamService.PORT + "/video\n"
                    + "Status: http://" + address + ":" + CameraStreamService.PORT + "/status",
                13, MUTED);
        endpoint.setTypeface(Typeface.MONOSPACE);
        endpoint.setPadding(0, 0, 0, dp(18));
        content.addView(endpoint);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("START CAMERA", BLUE);
        Button stop = button("STOP", SURFACE);
        start.setOnClickListener(v -> startCamera());
        stop.setOnClickListener(v -> stopCamera());
        buttons.addView(start, buttonParams());
        buttons.addView(stop, buttonParams());
        content.addView(buttons);

        TextView hint = text("Test from another device with ffplay using the /video endpoint.", 12, MUTED);
        hint.setPadding(0, dp(18), 0, 0);
        content.addView(hint);

        setContentView(content);
    }

    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        startForegroundService(new Intent(this, CameraStreamService.class));
        status.setText("●  Camera starting…");
        status.setTextColor(BLUE);
    }

    private void stopCamera() {
        stopService(new Intent(this, CameraStreamService.class));
        status.setText("●  Camera stopped");
        status.setTextColor(MUTED);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            status.setText("●  Camera permission required");
            status.setTextColor(RED);
        }
    }

    private Button button(String label, int fill) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(fill, fill == SURFACE ? BORDER : fill, 9));
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private String findLanAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
