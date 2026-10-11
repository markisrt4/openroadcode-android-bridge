package org.openroadcode.androidbridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostControlRequestTest {
  @Test public void sharedListenerAcceptsHostAndSdrRoutes() throws Exception {
    for (String route : new String[]{"/launch/package", "/open/uri", "/rtl-tcp/start"}) {
      HostControlRequest request = read("POST " + route + " HTTP/1.1\r\nContent-Length: 3\r\n\r\na=b");
      assertEquals("POST " + route + " HTTP/1.1", request.requestLine);
      assertEquals("a=b", request.body);
    }
  }
  @Test public void contentLengthCountsBytesRatherThanCharacters() throws Exception {
    assertEquals("name=café", read("POST /open/uri HTTP/1.1\r\nContent-Length: 10\r\n\r\nname=café").body);
  }
  @Test public void incompleteBodiesAreRejected() throws Exception {
    reject("POST /rtl-tcp/start HTTP/1.1\r\nContent-Length: 4\r\n\r\na=b");
  }
  @Test public void unsafeLengthAndTransferEncodingAreRejected() throws Exception {
    for (String header : new String[]{"Content-Length: -1", "Content-Length: 9000",
        "Content-Length: nope", "Content-Length: 0\r\nContent-Length: 0", "Transfer-Encoding: chunked"})
      reject("POST /open/uri HTTP/1.1\r\n" + header + "\r\n\r\n");
  }
  @Test public void headerMemoryAndCountAreBounded() throws Exception {
    reject("GET /health HTTP/1.1\r\nX: " + "x".repeat(4096) + "\r\n\r\n");
    reject("GET /health HTTP/1.1\r\n" + "X: y\r\n".repeat(33) + "\r\n");
  }
  @Test public void boundedReadsPreserveTruncatedAndEmptyInputs() throws Exception {
    assertArrayEquals(new byte[]{1, 2}, StreamBytes.readUpTo(new ByteArrayInputStream(new byte[]{1, 2}), 4));
    assertArrayEquals(new byte[0], StreamBytes.readUpTo(new ByteArrayInputStream(new byte[]{1}), 0));
  }
  private HostControlRequest read(String value) throws IOException {
    return HostControlRequest.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
  }
  private void reject(String value) throws IOException {
    try { read(value); fail("Unsafe request accepted"); } catch (IOException expected) { }
  }
}
