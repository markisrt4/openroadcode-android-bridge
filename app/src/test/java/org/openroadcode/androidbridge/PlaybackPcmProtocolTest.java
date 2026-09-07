package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PlaybackPcmProtocolTest {
  @Test public void headerUsesLittleEndianMonoPcm() {
    assertArrayEquals(new byte[]{'O','R','C','A',(byte)0x80,(byte)0xbb,0,0,1,0,0,0},
        PlaybackPcmProtocol.header(48000, 1));
  }

  @Test public void rejectsInvalidFormats() {
    for (int rate : new int[]{0, 7999, 192001}) {
      try { PlaybackPcmProtocol.header(rate, 1); fail("Expected invalid rate"); }
      catch (IllegalArgumentException expected) { }
    }
    try { PlaybackPcmProtocol.header(48000, 2); fail("Expected invalid channels"); }
    catch (IllegalArgumentException expected) { }
  }
}
