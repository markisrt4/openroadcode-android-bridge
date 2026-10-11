package org.openroadcode.androidbridge;

import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;

/** Bounded reads compatible with every supported Android API level. */
final class StreamBytes {
  static byte[] readUpTo(InputStream input, int limit) throws IOException {
    if (limit < 0 || limit > 16384) throw new IOException("Invalid read length");
    byte[] result = new byte[limit];
    int offset = 0;
    while (offset < limit) {
      int count = input.read(result, offset, limit - offset);
      if (count < 0) break;
      if (count == 0) {
        int value = input.read();
        if (value < 0) break;
        result[offset++] = (byte) value;
      } else offset += count;
    }
    return offset == limit ? result : Arrays.copyOf(result, offset);
  }
}
