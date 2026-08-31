package org.openroadcode.androidbridge;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
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
    private final PreviewSurface preview;

    CameraPreviewView(Context context) {
        super(context);
        setOrientation(VERTICAL);

        preview = new PreviewSurface(context);
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
        if (selectedIndex >= 0) {
            selector.setSelection(selectedIndex, false);
            preview.configureForCamera(context, cameraIds.get(selectedIndex));
        }

        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean firstCallback = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= cameraIds.size()) return;
                String cameraId = cameraIds.get(position);
                preview.configureForCamera(context, cameraId);
                String previous = context.getSharedPreferences(CameraStreamService.PREFERENCES, Context.MODE_PRIVATE)
                        .getString(CameraStreamService.PREF_CAMERA_ID, "");
                context.getSharedPreferences(CameraStreamService.PREFERENCES, Context.MODE_PRIVATE).edit()
                        .putString(CameraStreamService.PREF_CAMERA_ID, cameraId).apply();
                if (firstCallback) { firstCallback = false; return; }
                if (!cameraId.equals(previous)) {
                    context.stopService(new Intent(context, CameraStreamService.class));
                    context.startForegroundService(new Intent(context, CameraStreamService.class));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
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
        private int aspectWidth = CameraStreamService.WIDTH;
        private int aspectHeight = CameraStreamService.HEIGHT;

        PreviewSurface(Context context) {
            super(context);
            getHolder().setFixedSize(aspectWidth, aspectHeight);
            getHolder().addCallback(this);
        }

        void configureForCamera(Context context, String cameraId) {
            try {
                CameraManager manager = context.getSystemService(CameraManager.class);
                if (manager == null) return;
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Size size = choosePreviewSize(characteristics);
                if (size == null) return;
                aspectWidth = size.getWidth();
                aspectHeight = size.getHeight();
                getHolder().setFixedSize(aspectWidth, aspectHeight);
                requestLayout();
            } catch (CameraAccessException ignored) { }
        }

        private Size choosePreviewSize(CameraCharacteristics characteristics) {
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return null;
            Size[] sizes = map.getOutputSizes(SurfaceHolder.class);
            if (sizes == null || sizes.length == 0) return null;

            Rect activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            double targetAspect = activeArray != null && activeArray.height() > 0
                    ? (double) activeArray.width() / activeArray.height()
                    : (double) CameraStreamService.WIDTH / CameraStreamService.HEIGHT;
            long targetPixels = (long) CameraStreamService.WIDTH * CameraStreamService.HEIGHT;

            Size best = null;
            double bestScore = Double.MAX_VALUE;
            for (Size size : sizes) {
                int width = size.getWidth();
                int height = size.getHeight();
                if (width <= 0 || height <= 0) continue;
                if (width > 1920 || height > 1920) continue;
                double aspect = (double) Math.max(width, height) / Math.min(width, height);
                double normalizedTargetAspect = Math.max(targetAspect, 1.0 / targetAspect);
                double aspectPenalty = Math.abs(aspect - normalizedTargetAspect) * 10.0;
                long pixels = (long) width * height;
                double sizePenalty = Math.abs(Math.log((double) pixels / targetPixels));
                double score = aspectPenalty + sizePenalty;
                if (score < bestScore) { bestScore = score; best = size; }
            }
            return best != null ? best : sizes[0];
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if (width <= 0 || aspectWidth <= 0 || aspectHeight <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                return;
            }
            double ratio = (double) Math.min(aspectWidth, aspectHeight) / Math.max(aspectWidth, aspectHeight);
            setMeasuredDimension(width, Math.max(1, (int) Math.round(width * ratio)));
        }

        @Override public void surfaceCreated(SurfaceHolder holder) { CameraStreamService.setPreviewSurface(holder.getSurface()); }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { CameraStreamService.setPreviewSurface(holder.getSurface()); }
        @Override public void surfaceDestroyed(SurfaceHolder holder) { CameraStreamService.clearPreviewSurface(holder.getSurface()); }
    }
}
