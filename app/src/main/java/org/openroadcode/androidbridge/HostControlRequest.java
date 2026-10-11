package org.openroadcode.androidbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Byte-counted, bounded HTTP requests shared by host actions and SDR launch control. */
final class HostControlRequest {
  final String requestLine, body;
  private HostControlRequest(String requestLine, String body) {
    this.requestLine = requestLine;
    this.body = body;
  }

  static HostControlRequest read(InputStream input) throws IOException {
    String request = line(input);
    if (request == null) return null;
    int length = 0;
    boolean sawLength = false;
    int headerBytes = request.length();
    for (int count = 0; count < 32; count++) {
      String header = line(input);
      if (header == null) throw new IOException("Incomplete headers");
      headerBytes += header.length();
      if (headerBytes > 8192) throw new IOException("Headers too large");
      if (header.isEmpty()) {
        byte[] body = StreamBytes.readUpTo(input, length);
        if (body.length != length) throw new IOException("Incomplete body");
        return new HostControlRequest(request, new String(body, StandardCharsets.UTF_8));
      }
      int colon = header.indexOf(':');
      if (colon <= 0) throw new IOException("Invalid header");
      String name = header.substring(0, colon).trim();
      if (name.equalsIgnoreCase("Transfer-Encoding")) throw new IOException("Unsupported transfer encoding");
      if (name.equalsIgnoreCase("Content-Length")) {
        if (sawLength) throw new IOException("Duplicate body length");
        sawLength = true;
        try { length = Integer.parseInt(header.substring(colon + 1).trim()); }
        catch (NumberFormatException error) { throw new IOException("Invalid body length"); }
        if (length < 0 || length > 8192) throw new IOException("Invalid body length");
      }
    }
    throw new IOException("Too many headers");
  }

  private static String line(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int value;
    while ((value = input.read()) >= 0) {
      if (value == '\n') return bytes.toString("US-ASCII").replace("\r", "");
      if (bytes.size() >= 4096) throw new IOException("Header line too large");
      bytes.write(value);
    }
    if (bytes.size() == 0) return null;
    throw new IOException("Incomplete header line");
  }
}
