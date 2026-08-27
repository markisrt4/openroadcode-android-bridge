package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1001;

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        status = new TextView(this);
        status.setText("OpenRoadCode Sensor Bridge\n\nStart the bridge, then from Termux run:\n\ncurl http://127.0.0.1:8766/imu\ncurl http://127.0.0.1:8766/location\ncurl http://127.0.0.1:8766/motion");
        layout.addView(status);

        Button start = new Button(this);
        start.setText("Start sensor bridge");
        start.setOnClickListener(v -> startBridge());
        layout.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop sensor bridge");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, SensorBridgeService.class));
            status.setText("Sensor bridge stopped.");
        });
        layout.addView(stop);

        setContentView(layout);
    }

    private void startBridge() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST
            );
            return;
        }
        startForegroundService(new Intent(this, SensorBridgeService.class));
        status.setText("Sensor bridge running on 127.0.0.1:8766\n\nYou may now background this app.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBridge();
        } else {
            status.setText("Location permission is required for the navigation bridge.");
        }
    }
}
