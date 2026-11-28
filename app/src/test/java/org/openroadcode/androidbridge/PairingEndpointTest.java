// SPDX-License-Identifier: MIT
package org.openroadcode.androidbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class PairingEndpointTest {
  @Test public void joinsAddressWithDefaultPort() {
    int port = PairingEndpoint.DEFAULT_PORT;
    assertEquals("http://192.168.8.164:8769", PairingEndpoint.baseUrl("192.168.8.164", port));
    assertEquals("http://carpi.local:8769", PairingEndpoint.baseUrl("http://carpi.local:8769", port));
  }

  @Test public void preservesHttpsIpv6AndProxyPathWithCustomPort() {
    int port = PairingEndpoint.parsePort("443");
    assertEquals("https://[fd00::1]:443/orc", PairingEndpoint.baseUrl("https://[fd00::1]/orc", port));
  }

  @Test public void rejectsBadPortsAndConflictingUrlPort() {
    for (String port : new String[]{"", "abc", "0", "65536"}) {
      assertThrows(IllegalArgumentException.class, () -> PairingEndpoint.parsePort(port));
    }
    assertThrows(IllegalArgumentException.class, () -> PairingEndpoint.baseUrl("http://pi:9000", 8769));
    assertThrows(IllegalArgumentException.class, () -> PairingEndpoint.baseUrl("ftp://pi", 8769));
  }
}
