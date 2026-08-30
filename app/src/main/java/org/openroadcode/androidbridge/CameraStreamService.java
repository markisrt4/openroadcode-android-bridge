package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CameraStreamService extends Service {
    public static final int PORT = 8767;
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    public static final int FPS = 30;
    public static final int BITRATE_BPS = 3_000_000;
    public static final String PREFERENCES = "camera_stream";
    public static final String PREF_INTERFACE = "interface";
    public static final String INTERFACE_LOCALHOST = "localhost";
    public static final String INTERFACE_WIFI = "wifi";
    public static final String INTERFACE_CELLULAR = "cellular";

    private static final String TAG = "OrcCameraStream";
    private static final String CHANNEL_ID = "openroadcode_camera_stream";
    private static final int NOTIFICATION_ID = 4103;
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final Object PREVIEW_LOCK = new Object();
    private static volatile CameraStreamService activeInstance;
    private static Surface requestedPreviewSurface;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object clientLock = new Object();
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private Surface previewSurface;
    private Thread encoderDrainThread;
    private Thread serverThread;
    private ServerSocket serverSocket;
    private Socket videoClient;
    private OutputStream videoOutput;
    private MpegTsMuxer videoMuxer;
    private volatile String state = "stopped";
    private volatile String errorMessage = "";
    private volatile String cameraId = "";
    private volatile long encodedFrames;
    private volatile long connectedClients;
    private volatile byte[] codecConfig = new byte[0];
    private volatile String interfaceMode = INTERFACE_WIFI;
    private volatile String bindAddress = "";

    public static void setPreviewSurface(Surface surface) {
        synchronized (PREVIEW_LOCK) {
            requestedPreviewSurface = surface != null && surface.isValid() ? surface : null;
            CameraStreamService service = activeInstance;
            if (service != null) service.onPreviewSurfaceChanged();
        }
    }

    public static void clearPreviewSurface(Surface surface) {
        synchronized (PREVIEW_LOCK) {
            if (requestedPreviewSurface == surface) requestedPreviewSurface = null;
            CameraStreamService service = activeInstance;
            if (service != null) service.onPreviewSurfaceChanged();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        synchronized (PREVIEW_LOCK) { activeInstance = this; }
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Starting rear camera stream"));
        if (running.compareAndSet(false, true)) {
            state = "starting"; errorMessage = ""; encodedFrames = 0;
            interfaceMode = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getString(PREF_INTERFACE, INTERFACE_WIFI);
            startServer(); startCameraPipeline();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        synchronized (PREVIEW_LOCK) { if (activeInstance == this) activeInstance = null; }
        running.set(false); state = "stopped"; closeVideoClient(); closeServer(); stopCameraPipeline();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void onPreviewSurfaceChanged() {
        Handler handler = cameraHandler;
        if (handler == null) return;
        handler.post(() -> {
            Surface desired;
            synchronized (PREVIEW_LOCK) { desired = requestedPreviewSurface; }
            if (previewSurface == desired) return;
            previewSurface = desired;
            if (cameraDevice != null && encoderSurface != null && running.get()) createCaptureSession(cameraDevice);
        });
    }

    private void startCameraPipeline() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { fail("Camera permission not granted"); return; }
        cameraThread = new HandlerThread("orc-camera"); cameraThread.start(); cameraHandler = new Handler(cameraThread.getLooper());
        synchronized (PREVIEW_LOCK) { previewSurface = requestedPreviewSurface; }
        try {
            configureEncoder(); CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraId = findRearCamera(manager); if (cameraId == null) { fail("No rear-facing camera found"); return; }
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Exception e) { fail("Unable to start camera: " + message(e)); }
    }

    private void configureEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS); format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        encoder = MediaCodec.createEncoderByType(MIME_TYPE); encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface(); encoder.start();
        encoderDrainThread = new Thread(this::drainEncoder, "orc-h264-drain"); encoderDrainThread.start();
    }

    private String findRearCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id); Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice camera) { cameraDevice = camera; createCaptureSession(camera); }
        @Override public void onDisconnected(CameraDevice camera) { camera.close(); cameraDevice = null; fail("Camera disconnected"); }
        @Override public void onError(CameraDevice camera, int error) { camera.close(); cameraDevice = null; fail("Camera error " + error); }
    };

    private void createCaptureSession(CameraDevice camera) {
        CameraCaptureSession previous = captureSession;
        captureSession = null;
        if (previous != null) { try { previous.stopRepeating(); } catch (Exception ignored) { } previous.close(); }
        List<Surface> outputs = new ArrayList<>();
        outputs.add(encoderSurface);
        Surface currentPreview = previewSurface;
        if (currentPreview != null && currentPreview.isValid()) outputs.add(currentPreview);
        try {
            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if (!running.get()) { session.close(); return; }
                    captureSession = session;
                    try {
                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        request.addTarget(encoderSurface);
                        Surface activePreview = previewSurface;
                        if (activePreview != null && activePreview.isValid()) request.addTarget(activePreview);
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(FPS, FPS));
                        session.setRepeatingRequest(request.build(), null, cameraHandler); state = "streaming";
                        updateNotification("Rear camera streaming • MPEG-TS • " + interfaceMode + " • port " + PORT);
                    } catch (CameraAccessException | IllegalArgumentException e) { fail("Unable to start capture: " + message(e)); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) { fail("Camera capture session configuration failed"); }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException e) { fail("Unable to configure camera: " + message(e)); }
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get() && encoder != null) {
            try {
                int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { captureCodecConfig(encoder.getOutputFormat()); continue; }
                if (index < 0) continue;
                ByteBuffer buffer = encoder.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset); buffer.limit(info.offset + info.size); byte[] encoded = new byte[info.size]; buffer.get(encoded);
                    byte[] annexB = toAnnexB(encoded);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) codecConfig = annexB;
                    else {
                        encodedFrames++;
                        boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        writeVideoFrame(annexB, info.presentationTimeUs, keyFrame);
                    }
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            } catch (IllegalStateException e) { if (running.get()) fail("Encoder stopped unexpectedly: " + message(e)); break; }
        }
    }

    private void captureCodecConfig(MediaFormat format) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); appendCodecBuffer(output, format.getByteBuffer("csd-0"));
        appendCodecBuffer(output, format.getByteBuffer("csd-1")); if (output.size() > 0) codecConfig = output.toByteArray();
    }
    private void appendCodecBuffer(ByteArrayOutputStream output, ByteBuffer buffer) {
        if (buffer == null) return; ByteBuffer duplicate = buffer.duplicate(); byte[] data = new byte[duplicate.remaining()]; duplicate.get(data);
        byte[] annexB = toAnnexB(data); output.write(annexB, 0, annexB.length);
    }
    private byte[] toAnnexB(byte[] input) {
        if (input.length < 4 || startsWithStartCode(input)) return input;
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length + 32); int offset = 0;
        while (offset + 4 <= input.length) {
            int length = ((input[offset] & 0xff) << 24) | ((input[offset + 1] & 0xff) << 16)
                    | ((input[offset + 2] & 0xff) << 8) | (input[offset + 3] & 0xff); offset += 4;
            if (length <= 0 || offset + length > input.length) return input;
            output.write(0); output.write(0); output.write(0); output.write(1); output.write(input, offset, length); offset += length;
        }
        return offset == input.length ? output.toByteArray() : input;
    }
    private boolean startsWithStartCode(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0 && ((data[2] == 1) || (data[2] == 0 && data[3] == 1));
    }

    private void writeVideoFrame(byte[] frame, long presentationTimeUs, boolean keyFrame) {
        synchronized (clientLock) {
            if (videoOutput == null || videoMuxer == null) return;
            try {
                byte[] payload = frame;
                if (keyFrame && codecConfig.length > 0) {
                    payload = new byte[codecConfig.length + frame.length];
                    System.arraycopy(codecConfig, 0, payload, 0, codecConfig.length);
                    System.arraycopy(frame, 0, payload, codecConfig.length, frame.length);
                }
                videoMuxer.writeVideo(videoOutput, payload, presentationTimeUs, keyFrame);
                videoOutput.flush();
            } catch (IOException e) { closeVideoClientLocked(); }
        }
    }

    private void startServer() {
        serverThread = new Thread(() -> {
            try (ServerSocket socket = new ServerSocket()) {
                serverSocket = socket; socket.setReuseAddress(true); InetAddress address = resolveBindAddress(interfaceMode);
                if (address == null) throw new IOException(interfaceMode + " network is not available");
                bindAddress = address.getHostAddress(); socket.bind(new InetSocketAddress(address, PORT));
                while (running.get()) handleClient(socket.accept());
            } catch (IOException e) { if (running.get()) fail("Video server error: " + message(e)); }
        }, "orc-camera-http"); serverThread.start();
    }

    private InetAddress resolveBindAddress(String mode) {
        if (INTERFACE_LOCALHOST.equals(mode)) { try { return InetAddress.getByName("127.0.0.1"); } catch (IOException e) { return null; } }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class); if (cm == null) return null;
        int transport = INTERFACE_CELLULAR.equals(mode) ? NetworkCapabilities.TRANSPORT_CELLULAR : NetworkCapabilities.TRANSPORT_WIFI;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network); if (caps == null || !caps.hasTransport(transport)) continue;
            LinkProperties props = cm.getLinkProperties(network); if (props == null) continue;
            for (LinkAddress link : props.getLinkAddresses()) {
                InetAddress address = link.getAddress(); if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address;
            }
        }
        return null;
    }

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true); BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine(); if (requestLine == null) { client.close(); return; }
            String line; while ((line = reader.readLine()) != null && !line.isEmpty()) { }
            if (requestLine.startsWith("GET /status ")) writeStatus(client); else if (requestLine.startsWith("GET /video ")) attachVideoClient(client); else writeNotFound(client);
        } catch (IOException e) { closeQuietly(client); }
    }

    private void attachVideoClient(Socket client) throws IOException {
        OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush();
        synchronized (clientLock) {
            closeVideoClientLocked(); videoClient = client; videoOutput = output; videoMuxer = new MpegTsMuxer(); connectedClients++;
            videoMuxer.writeHeaders(videoOutput); videoOutput.flush();
        }
        requestSyncFrame();
    }

    private void requestSyncFrame() {
        MediaCodec activeEncoder = encoder; if (activeEncoder == null) return;
        try { Bundle p = new Bundle(); p.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); activeEncoder.setParameters(p); }
        catch (IllegalStateException e) { Log.w(TAG, "Unable to request sync frame for new viewer", e); }
    }

    private void writeStatus(Socket client) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("state", state); json.put("camera_id", cameraId); json.put("width", WIDTH); json.put("height", HEIGHT);
            json.put("fps", FPS); json.put("bitrate_bps", BITRATE_BPS); json.put("codec", "h264"); json.put("container", "mpegts");
            json.put("encoded_frames", encodedFrames); json.put("client_connected", videoOutput != null); json.put("client_connections", connectedClients);
            json.put("interface", interfaceMode); json.put("bind_address", bindAddress); json.put("preview_attached", previewSurface != null && previewSurface.isValid());
            json.put("error", errorMessage);
        } catch (Exception ignored) { }
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8); OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body); output.flush(); client.close();
    }
    private void writeNotFound(Socket client) throws IOException {
        byte[] body = "Not found\n".getBytes(StandardCharsets.UTF_8); OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush(); client.close();
    }
    private void fail(String message) { Log.e(TAG, message); state = "error"; errorMessage = message; updateNotification(message); }
    private void stopCameraPipeline() {
        if (captureSession != null) { try { captureSession.stopRepeating(); } catch (Exception ignored) { } captureSession.close(); captureSession = null; }
        if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
        if (encoder != null) { try { encoder.signalEndOfInputStream(); } catch (Exception ignored) { } try { encoder.stop(); } catch (Exception ignored) { } try { encoder.release(); } catch (Exception ignored) { } encoder = null; }
        if (encoderSurface != null) { encoderSurface.release(); encoderSurface = null; }
        previewSurface = null;
        if (cameraThread != null) { cameraThread.quitSafely(); cameraThread = null; cameraHandler = null; }
    }
    private void closeServer() { if (serverSocket != null) { try { serverSocket.close(); } catch (IOException ignored) { } serverSocket = null; } }
    private void closeVideoClient() { synchronized (clientLock) { closeVideoClientLocked(); } }
    private void closeVideoClientLocked() {
        videoMuxer = null;
        if (videoOutput != null) { try { videoOutput.close(); } catch (IOException ignored) { } videoOutput = null; }
        if (videoClient != null) { closeQuietly(videoClient); videoClient = null; }
    }
    private void closeQuietly(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Camera stream", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class); if (manager != null) manager.createNotificationChannel(channel);
    }
    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID).setContentTitle("OpenRoadCode camera stream").setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build();
    }
    private void updateNotification(String text) { NotificationManager manager = getSystemService(NotificationManager.class); if (manager != null) manager.notify(NOTIFICATION_ID, notification(text)); }
    private String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
