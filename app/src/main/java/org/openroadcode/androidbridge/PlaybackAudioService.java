package org.openroadcode.androidbridge;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Explicitly consented playback capture. Never records the microphone or stores audio. */
public final class PlaybackAudioService extends Service {
  public static final int PORT = 8768;
  public static final String START = "org.openroadcode.audio.START";
  public static final String STOP = "org.openroadcode.audio.STOP";
  public static final String RESULT = "projection_result";
  public static final String DATA = "projection_data";
  private static final int RATE = 48000, SAMPLES = 1024, NOTIFICATION = 8768;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final Object lock = new Object();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private MediaProjection projection;
  private AudioRecord recorder;
  private ServerSocket server;
  private Socket client;
  private Thread captureThread, serverThread;
  private String error = "";
  private long frames;
  private volatile byte[] latest;
  private long sequence;
  private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
    @Override public void onStop() { stopSelf(); }
  };

  @Override public android.os.IBinder onBind(Intent intent) { return null; }
  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_NOT_STICKY;
    if (STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
    if (!START.equals(intent.getAction()) || running.get()) return START_NOT_STICKY;
    if (Build.VERSION.SDK_INT < 29) { stopSelf(); return START_NOT_STICKY; }
    Intent data = intent.getParcelableExtra(DATA);
    if (intent.getIntExtra(RESULT, Activity.RESULT_CANCELED) != Activity.RESULT_OK || data == null) {
      stopSelf(); return START_NOT_STICKY;
    }
    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      nm.createNotificationChannel(new NotificationChannel("orc-playback", "ORC playback capture", NotificationManager.IMPORTANCE_LOW));
      Intent stop = new Intent(this, PlaybackAudioService.class).setAction(STOP);
      PendingIntent pending = PendingIntent.getService(this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification notification = new Notification.Builder(this, "orc-playback")
          .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("OpenRoadCode audio capture")
          .setContentText("Playback capture active").setOngoing(true)
          .addAction(android.R.drawable.ic_media_pause, "Stop capture", pending).build();
      startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
      MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
      projection = manager.getMediaProjection(Activity.RESULT_OK, data);
      if (projection == null) throw new IllegalStateException("MediaProjection unavailable");
      projection.registerCallback(projectionCallback, handler);
      AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
          .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
          .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
      AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
      int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      recorder = new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum, SAMPLES * 8))
          .setAudioPlaybackCaptureConfig(config).build();
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord initialization failed");
      recorder.startRecording();
      running.set(true);
      error = ""; frames = 0; sequence = 0; latest = null;
      server = new ServerSocket();
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
      captureThread = new Thread(this::captureLoop, "orc-playback-pcm");
      serverThread = new Thread(this::serveLoop, "orc-playback-http");
      captureThread.start(); serverThread.start();
    } catch (Exception ex) {
      error = ex.toString(); android.util.Log.e("ORCPlayback", "Capture start failed", ex); stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void captureLoop() {
    byte[] buffer = new byte[SAMPLES * 2];
    try {
      while (running.get()) {
        int count = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
        if (count < 0) throw new IOException("AudioRecord read error " + count);
        if (count == 0) continue;
        synchronized (lock) {
          latest = java.util.Arrays.copyOf(buffer, count);
          frames += count / 2; sequence++;
          lock.notifyAll();
        }
      }
    } catch (Exception ex) {
      if (running.get()) { error = ex.toString(); android.util.Log.e("ORCPlayback", "Capture failed", ex); stopSelf(); }
    }
  }

  private void serveLoop() {
    while (running.get()) {
      try {
        Socket socket = server.accept();
        socket.setSoTimeout(3000);
        new Thread(() -> serve(socket), "orc-playback-client").start();
      } catch (IOException ex) { if (running.get()) android.util.Log.w("ORCPlayback", "HTTP accept failed", ex); }
    }
  }

  private void serve(Socket socket) {
    try (Socket connection = socket) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
      String request = reader.readLine();
      if (request == null) return;
      while (true) { String line = reader.readLine(); if (line == null || line.isEmpty()) break; }
      String path = request.split(" ")[1];
      OutputStream out = connection.getOutputStream();
      if (path.equals("/status")) {
        JSONObject status = new JSONObject();
        status.put("running", running.get()).put("source", "android-playback").put("sample_rate_hz", RATE)
            .put("channels", 1).put("format", "s16le").put("frames", frames).put("error", error);
        respond(out, 200, "application/json", status.toString().getBytes(StandardCharsets.UTF_8));
      } else if (path.equals("/stream")) {
        synchronized (lock) {
          if (client != null) { respond(out, 409, "text/plain", "A client is already connected".getBytes(StandardCharsets.UTF_8)); return; }
          client = connection;
        }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        // ORCA v1: 4-byte magic, little-endian sample rate and channel count, then raw s16le PCM.
        out.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put(new byte[]{'O','R','C','A'}).putInt(RATE).putInt(1).array());
        long seen = 0;
        while (running.get()) {
          byte[] block;
          synchronized (lock) {
            while (running.get() && sequence == seen) lock.wait(1000);
            if (!running.get()) break;
            seen = sequence; block = latest;
          }
          if (block != null) { out.write(block); out.flush(); }
        }
      } else respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {
      // Disconnecting a client must not terminate the capture service.
    } finally {
      synchronized (lock) { if (client == socket) client = null; }
    }
  }

  private static void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
    out.write(("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\nContent-Type: " + type +
        "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(body); out.flush();
  }

  @Override public void onDestroy() {
    running.set(false);
    synchronized (lock) { lock.notifyAll(); }
    try { if (server != null) server.close(); } catch (IOException ignored) {}
    try { if (client != null) client.close(); } catch (IOException ignored) {}
    if (recorder != null) { try { recorder.stop(); } catch (IllegalStateException ignored) {} recorder.release(); recorder = null; }
    if (projection != null) { projection.unregisterCallback(projectionCallback); projection.stop(); projection = null; }
    server = null; client = null;
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
