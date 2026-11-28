// SPDX-License-Identifier: MIT
package org.openroadcode.androidbridge;

import java.net.URI;
import java.net.URISyntaxException;

/** Assemble the remote service manager URL from the pairing dialog fields. */
final class PairingEndpoint {
  static final int DEFAULT_PORT = 8769;

  private PairingEndpoint() {}

  static int parsePort(String text) {
    try {
      int port = Integer.parseInt(text.trim());
      if (port >= 1 && port <= 65535) return port;
    } catch (NumberFormatException ignored) { }
    throw new IllegalArgumentException("Enter a port from 1 to 65535");
  }

  static String baseUrl(String text, int port) {
    String address = text.trim();
    URI uri;
    try {
      uri = new URI(address.contains("://") ? address : "http://" + address);
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("Enter a valid IP address, hostname, or HTTP(S) URL");
    }
    if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
      throw new IllegalArgumentException("Enter a valid IP address, hostname, or HTTP(S) URL");
    }
    if (uri.getPort() != -1 && uri.getPort() != port) {
      throw new IllegalArgumentException("Put the desired port in the Port field and remove it from the URL");
    }
    return uri.getScheme() + "://" + uri.getHost() + ":" + port + uri.getRawPath();
  }
}
