package org.openroadcode.androidbridge;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Localhost-only PCM playback bridge.
 *
 * Accepts raw 48 kHz, stereo, signed 16-bit little-endian PCM on 127.0.0.1:8769
 * and feeds it to Android AudioTrack. Blocking AudioTrack writes deliberately
 * provide the playback clock and TCP backpressure to the producer.
 */
public final class PcmAudioOutputService extends Service {
  public static final int PORT = 8771;
  public static final String START = "org.openroadcode.audio_output.START";
  public static final String STOP = "org.openroadcode.audio_output.STOP";
  public static final int RATE = 48000;
  public static final int CHANNELS = 2;
  private static final int NOTIFICATION = 8771;
  private static final String CHANNEL_ID = "orc-audio-output";
  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
  private static volatile boolean clientConnected;
  private static volatile long bytesPlayed;
  private static volatile String lastError = "";

  private ServerSocket server;
  private Socket client;
  private Thread serverThread;

  public static boolean isRunning() { return RUNNING.get(); }
  public static boolean isClientConnected() { return clientConnected; }
  public static long bytesPlayed() { return bytesPlayed; }
  public static String lastError() { return lastError; }

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (RUNNING.get()) return START_STICKY;

    try {
      NotificationManager nm = getSystemService(NotificationManager.class);
      nm.createNotificationChannel(new NotificationChannel(
          CHANNEL_ID, "ORC audio output", NotificationManager.IMPORTANCE_LOW));
      Intent stop = new Intent(this, PcmAudioOutputService.class).setAction(STOP);
      PendingIntent pending = PendingIntent.getService(
          this, 0, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification notification = new Notification.Builder(this, CHANNEL_ID)
          .setSmallIcon(android.R.drawable.ic_media_play)
          .setContentTitle("OpenRoadCode audio output")
          .setContentText("Local PCM playback bridge active")
          .setOngoing(true)
          .addAction(android.R.drawable.ic_media_pause, "Stop", pending)
          .build();
      if (Build.VERSION.SDK_INT >= 29) {
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      } else {
        startForeground(NOTIFICATION, notification);
      }

      server = new ServerSocket();
      server.setReuseAddress(true);
      server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 1);
      RUNNING.set(true);
      clientConnected = false;
      bytesPlayed = 0;
      lastError = "";
      serverThread = new Thread(this::serveLoop, "orc-pcm-audio-output");
      serverThread.start();
    } catch (Exception ex) {
      lastError = ex.toString();
      android.util.Log.e("ORCAudioOutput", "Audio output start failed", ex);
      stopSelf();
    }
    return START_STICKY;
  }

  private void serveLoop() {
    while (RUNNING.get()) {
      try {
        Socket socket = server.accept();
        synchronized (this) {
          if (client != null) {
            socket.close();
            continue;
          }
          client = socket;
        }
        play(socket);
      } catch (IOException ex) {
        if (RUNNING.get()) {
          lastError = ex.toString();
          android.util.Log.w("ORCAudioOutput", "PCM accept failed", ex);
        }
      } finally {
        synchronized (this) {
          closeQuietly(client);
          client = null;
        }
        clientConnected = false;
      }
    }
  }

  private void play(Socket socket) {
    AudioTrack track = null;
    try {
      socket.setTcpNoDelay(true);
      int channelMask = AudioFormat.CHANNEL_OUT_STEREO;
      int minimum = AudioTrack.getMinBufferSize(RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("Unsupported AudioTrack format: " + minimum);
      int bufferBytes = Math.max(minimum, RATE * CHANNELS * 2 / 10);
      AudioFormat format = new AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(RATE)
          .setChannelMask(channelMask)
          .build();
      AudioAttributes attributes = new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build();
      track = new AudioTrack.Builder()
          .setAudioAttributes(attributes)
          .setAudioFormat(format)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .setBufferSizeInBytes(bufferBytes)
          .build();
      if (track.getState() != AudioTrack.STATE_INITIALIZED)
        throw new IOException("AudioTrack initialization failed");

      track.play();
      clientConnected = true;
      lastError = "";
      byte[] buffer = new byte[8192];
      InputStream input = socket.getInputStream();
      while (RUNNING.get()) {
        int count = input.read(buffer);
        if (count < 0) break;
        int offset = 0;
        while (offset < count && RUNNING.get()) {
          int written = track.write(buffer, offset, count - offset, AudioTrack.WRITE_BLOCKING);
          if (written < 0) throw new IOException("AudioTrack write failed: " + written);
          offset += written;
          bytesPlayed += written;
        }
      }
    } catch (IOException | IllegalStateException ex) {
      if (RUNNING.get()) {
        lastError = ex.toString();
        android.util.Log.w("ORCAudioOutput", "PCM playback client ended", ex);
      }
    } finally {
      clientConnected = false;
      if (track != null) {
        try { track.stop(); } catch (IllegalStateException ignored) {}
        track.release();
      }
    }
  }

  private static void closeQuietly(Closeable closeable) {
    if (closeable == null) return;
    try { closeable.close(); } catch (IOException ignored) {}
  }

  @Override public void onDestroy() {
    RUNNING.set(false);
    clientConnected = false;
    closeQuietly(client);
    closeQuietly(server);
    client = null;
    server = null;
    if (serverThread != null) serverThread.interrupt();
    serverThread = null;
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
