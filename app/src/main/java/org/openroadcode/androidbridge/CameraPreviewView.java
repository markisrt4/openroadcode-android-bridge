package org.openroadcode.androidbridge;

import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Lightweight in-app preview surface for the camera stream.
 *
 * The view never opens the camera itself. CameraStreamService remains the sole
 * Camera2 owner and adds/removes this surface from its existing capture session.
 */
final class CameraPreviewView extends SurfaceView implements SurfaceHolder.Callback {
    private static final int PREVIEW_WIDTH = 16;
    private static final int PREVIEW_HEIGHT = 9;

    CameraPreviewView(Context context) {
        super(context);
        getHolder().setFixedSize(CameraStreamService.WIDTH, CameraStreamService.HEIGHT);
        getHolder().addCallback(this);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int height = width * PREVIEW_HEIGHT / PREVIEW_WIDTH;
        setMeasuredDimension(width, height);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        CameraStreamService.setPreviewSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        CameraStreamService.setPreviewSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        CameraStreamService.clearPreviewSurface(holder.getSurface());
    }
}
