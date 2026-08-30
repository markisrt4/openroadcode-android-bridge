package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CameraStreamService extends Service {
    public static final int PORT = 8767;
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    public static final int FPS = 30;
    public static final int BITRATE_BPS = 3_000_000;

    private static final String TAG = "OrcCameraStream";
    private static final String CHANNEL_ID = "openroadcode_camera_stream";
    private static final int NOTIFICATION_ID = 4103;
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object clientLock = new Object();

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private Thread encoderDrainThread;
    private Thread serverThread;
    private ServerSocket serverSocket;
    private Socket videoClient;
    private OutputStream videoOutput;
    private volatile String state = "stopped";
    private volatile String errorMessage = "";
    private volatile String cameraId = "";
    private volatile long encodedFrames = 0;
    private volatile long connectedClients = 0;
    private volatile byte[] codecConfig = new byte[0];

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Starting rear camera stream"));
        if (running.compareAndSet(false, true)) {
            state = "starting";
            errorMessage = "";
            startServer();
            startCameraPipeline();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        state = "stopped";
        closeVideoClient();
        closeServer();
        stopCameraPipeline();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startCameraPipeline() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("Camera permission not granted");
            return;
        }

        cameraThread = new HandlerThread("orc-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        try {
            configureEncoder();
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cameraId = findRearCamera(manager);
            if (cameraId == null) {
                fail("No rear-facing camera found");
                return;
            }
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Exception e) {
            fail("Unable to start camera: " + message(e));
        }
    }

    private void configureEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();

        encoderDrainThread = new Thread(this::drainEncoder, "orc-h264-drain");
        encoderDrainThread.start();
    }

    private String findRearCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCaptureSession(camera);
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
            fail("Camera disconnected");
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            fail("Camera error " + error);
        }
    };

    private void createCaptureSession(CameraDevice camera) {
        try {
            camera.createCaptureSession(Collections.singletonList(encoderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (!running.get()) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder request = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD);
                                request.addTarget(encoderSurface);
                                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(FPS, FPS));
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                                state = "streaming";
                                updateNotification("Rear camera streaming • 720p30 • port " + PORT);
                            } catch (CameraAccessException e) {
                                fail("Unable to start capture: " + message(e));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fail("Camera capture session configuration failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            fail("Unable to configure camera: " + message(e));
        }
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running.get() && encoder != null) {
            try {
                int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    captureCodecConfig(encoder.getOutputFormat());
                    continue;
                }
                if (index < 0) {
                    continue;
                }

                ByteBuffer buffer = encoder.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    byte[] encoded = new byte[info.size];
                    buffer.get(encoded);
                    byte[] annexB = toAnnexB(encoded);

                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codecConfig = annexB;
                    } else {
                        encodedFrames++;
                        writeVideoFrame(annexB);
                    }
                }
                encoder.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } catch (IllegalStateException e) {
                if (running.get()) {
                    fail("Encoder stopped unexpectedly: " + message(e));
                }
                break;
            }
        }
    }

    private void captureCodecConfig(MediaFormat format) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        appendCodecBuffer(output, format.getByteBuffer("csd-0"));
        appendCodecBuffer(output, format.getByteBuffer("csd-1"));
        if (output.size() > 0) {
            codecConfig = output.toByteArray();
        }
    }

    private void appendCodecBuffer(ByteArrayOutputStream output, ByteBuffer buffer) {
        if (buffer == null) return;
        ByteBuffer duplicate = buffer.duplicate();
        byte[] data = new byte[duplicate.remaining()];
        duplicate.get(data);
        byte[] annexB = toAnnexB(data);
        output.write(annexB, 0, annexB.length);
    }

    private byte[] toAnnexB(byte[] input) {
        if (input.length < 4 || startsWithStartCode(input)) {
            return input;
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length + 32);
        int offset = 0;
        while (offset + 4 <= input.length) {
            int length = ((input[offset] & 0xff) << 24)
                    | ((input[offset + 1] & 0xff) << 16)
                    | ((input[offset + 2] & 0xff) << 8)
                    | (input[offset + 3] & 0xff);
            offset += 4;
            if (length <= 0 || offset + length > input.length) {
                return input;
            }
            output.write(0);
            output.write(0);
            output.write(0);
            output.write(1);
            output.write(input, offset, length);
            offset += length;
        }
        return offset == input.length ? output.toByteArray() : input;
    }

    private boolean startsWithStartCode(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0
                && ((data[2] == 1) || (data[2] == 0 && data[3] == 1));
    }

    private void writeVideoFrame(byte[] frame) {
        synchronized (clientLock) {
            if (videoOutput == null) return;
            try {
                videoOutput.write(frame);
                videoOutput.flush();
            } catch (IOException e) {
                closeVideoClientLocked();
            }
        }
    }

    private void startServer() {
        serverThread = new Thread(() -> {
            try (ServerSocket socket = new ServerSocket()) {
                serverSocket = socket;
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", PORT));
                while (running.get()) {
                    Socket client = socket.accept();
                    handleClient(client);
                }
            } catch (IOException e) {
                if (running.get()) {
                    fail("Video server error: " + message(e));
                }
            }
        }, "orc-camera-http");
        serverThread.start();
    }

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Drain request headers.
            }

            if (requestLine.startsWith("GET /status ")) {
                writeStatus(client);
            } else if (requestLine.startsWith("GET /video ")) {
                attachVideoClient(client);
            } else {
                writeNotFound(client);
            }
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    private void attachVideoClient(Socket client) throws IOException {
        OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: video/h264\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();

        synchronized (clientLock) {
            closeVideoClientLocked();
            videoClient = client;
            videoOutput = output;
            connectedClients++;
            if (codecConfig.length > 0) {
                videoOutput.write(codecConfig);
                videoOutput.flush();
            }
        }
    }

    private void writeStatus(Socket client) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("state", state);
            json.put("camera_id", cameraId);
            json.put("width", WIDTH);
            json.put("height", HEIGHT);
            json.put("fps", FPS);
            json.put("bitrate_bps", BITRATE_BPS);
            json.put("codec", "h264");
            json.put("encoded_frames", encodedFrames);
            json.put("client_connected", videoOutput != null);
            json.put("client_connections", connectedClients);
            json.put("error", errorMessage);
        } catch (Exception ignored) { }
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
        client.close();
    }

    private void writeNotFound(Socket client) throws IOException {
        byte[] body = "Not found\n".getBytes(StandardCharsets.UTF_8);
        OutputStream output = client.getOutputStream();
        output.write(("HTTP/1.1 404 Not Found\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
        client.close();
    }

    private void fail(String message) {
        Log.e(TAG, message);
        state = "error";
        errorMessage = message;
        updateNotification(message);
    }

    private void stopCameraPipeline() {
        if (captureSession != null) {
            try { captureSession.stopRepeating(); } catch (Exception ignored) { }
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (encoder != null) {
            try { encoder.signalEndOfInputStream(); } catch (Exception ignored) { }
            try { encoder.stop(); } catch (Exception ignored) { }
            try { encoder.release(); } catch (Exception ignored) { }
            encoder = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void closeServer() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { }
            serverSocket = null;
        }
    }

    private void closeVideoClient() {
        synchronized (clientLock) {
            closeVideoClientLocked();
        }
    }

    private void closeVideoClientLocked() {
        if (videoOutput != null) {
            try { videoOutput.close(); } catch (IOException ignored) { }
            videoOutput = null;
        }
        if (videoClient != null) {
            closeQuietly(videoClient);
            videoClient = null;
        }
    }

    private void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Camera stream", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenRoadCode camera stream")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
