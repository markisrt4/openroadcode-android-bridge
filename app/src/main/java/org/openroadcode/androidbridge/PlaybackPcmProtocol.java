package org.openroadcode.androidbridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** ORCA v1 wire format shared with OpenRoadCode's PCM capture adapter. */
public final class PlaybackPcmProtocol {
  private PlaybackPcmProtocol() {}

  /** Return magic, sample rate, and channel count as a 12-byte little-endian header. */
  public static byte[] header(int sampleRate, int channels) {
    if (sampleRate < 8000 || sampleRate > 192000 || channels != 1)
      throw new IllegalArgumentException("Unsupported PCM format");
    return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        .put(new byte[]{'O', 'R', 'C', 'A'}).putInt(sampleRate).putInt(channels).array();
  }
}
