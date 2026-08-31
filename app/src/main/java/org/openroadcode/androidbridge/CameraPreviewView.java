package org.openroadcode.androidbridge;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Camera preview plus a selector populated from the cameras Android exposes. */
final class CameraPreviewView extends LinearLayout {
    private static final int TEXT = Color.rgb(243, 247, 249);
    private static final int MUTED = Color.rgb(184, 194, 200);
    private static final int SURFACE_RAISED = Color.rgb(16, 34, 46);

    private final List<String> cameraIds = new ArrayList<>();
    private boolean initializingSelection = true;

    CameraPreviewView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        PreviewSurface preview = new PreviewSurface(context);
        addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(context);
        label.setText("CAMERA");
        label.setTextColor(MUTED);
        label.setTextSize(11);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setLetterSpacing(.10f);
        label.setPadding(dp(2), dp(10), 0, dp(4));
        addView(label);

        Spinner selector = new Spinner(context);
        selector.setBackgroundColor(SURFACE_RAISED);
        selector.setPadding(dp(10), 0, dp(10), 0);
        addView(selector, new LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        populateCameraSelector(context, selector);
    }

    private void populateCameraSelector(Context context, Spinner selector) {
        List<String> labels = new ArrayList<>();
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            if (manager != null) {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics c = manager.getCameraCharacteristics(id);
                    cameraIds.add(id);
                    labels.add(cameraLabel(id, c));
                }
            }
        } catch (CameraAccessException ignored) { }

        if (labels.isEmpty()) labels.add("No cameras available");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT); view.setTextSize(13); view.setGravity(Gravity.CENTER_VERTICAL); return view;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(TEXT); view.setBackgroundColor(SURFACE_RAISED); view.setPadding(dp(12), dp(12), dp(12), dp(12)); return view;
            }
        };
        selector.setAdapter(adapter);

        String selected = context.getSharedPreferences(CameraStreamService.PREFERENCES, Context.MODE_PRIVATE)
                .getString(CameraStreamService.PREF_CAMERA_ID, "");
        int selectedIndex = cameraIds.indexOf(selected);
        if (selectedIndex < 0) selectedIndex = firstRearCamera(context);
        if (selectedIndex >= 0) selector.setSelection(selectedIndex, false);
        final int initialIndex = selectedIndex;

        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= cameraIds.size()) return;
                String cameraId = cameraIds.get(position);
                context.getSharedPreferences(CameraStreamService.PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putString(CameraStreamService.PREF_CAMERA_ID, cameraId).apply();
                if (initializingSelection) { initializingSelection = false; return; }
                if (CameraStreamService.isActive()) {
                    context.stopService(new Intent(context, CameraStreamService.class));
                    context.startForegroundService(new Intent(context, CameraStreamService.class));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        initializingSelection = false;
    }

    private int firstRearCamera(Context context) {
        try {
            CameraManager manager = context.getSystemService(CameraManager.class);
            if (manager == null) return cameraIds.isEmpty() ? -1 : 0;
            for (int i = 0; i < cameraIds.size(); i++) {
                Integer facing = manager.getCameraCharacteristics(cameraIds.get(i)).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return i;
            }
        } catch (CameraAccessException ignored) { }
        return cameraIds.isEmpty() ? -1 : 0;
    }

    private String cameraLabel(String id, CameraCharacteristics c) {
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        String side = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "Front"
                : facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "Rear" : "External";
        float[] focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        String focal = focalLengths != null && focalLengths.length > 0
                ? String.format(Locale.US, " • %.1f mm", focalLengths[0]) : "";
        return side + focal + " • ID " + id;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private static final class PreviewSurface extends SurfaceView implements SurfaceHolder.Callback {
        PreviewSurface(Context context) {
            super(context);
            getHolder().setFixedSize(CameraStreamService.WIDTH, CameraStreamService.HEIGHT);
            getHolder().addCallback(this);
        }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width <= 0) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return; }
            setMeasuredDimension(width, width * 9 / 16);
        }
        @Override public void surfaceCreated(SurfaceHolder holder) { CameraStreamService.setPreviewSurface(holder.getSurface()); }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { CameraStreamService.setPreviewSurface(holder.getSurface()); }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { CameraStreamService.clearPreviewSurface(holder.getSurface()); }
    }
}
