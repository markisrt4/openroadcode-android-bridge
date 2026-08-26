package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView status = new TextView(this);
        status.setText("OpenRoadCode Sensor Bridge\n\nStart the bridge, then from Termux run:\n\ncurl http://127.0.0.1:8766/imu");
        layout.addView(status);

        Button start = new Button(this);
        start.setText("Start sensor bridge");
        start.setOnClickListener(v -> {
            Intent intent = new Intent(this, SensorBridgeService.class);
            startForegroundService(intent);
            status.setText("Sensor bridge running on 127.0.0.1:8766\n\nYou may now background this app.");
        });
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
}
