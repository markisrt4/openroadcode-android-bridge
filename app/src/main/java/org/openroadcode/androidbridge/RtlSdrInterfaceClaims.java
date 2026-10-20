package org.openroadcode.androidbridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared USB interface counts scoped to one UsbDeviceConnection identity.
 * Callers hold the proxy's USB lock across accounting and Android USB calls.
 * A retired client's cleanup must never release a replacement handle's claim.
 */
final class RtlSdrInterfaceClaims {
    private Object connection;
    private final Map<Integer, Integer> counts = new HashMap<>();

    void useConnection(Object next) {
        if (connection != next) {
            counts.clear();
            connection = next;
        }
    }

    void clear() {
        counts.clear();
        connection = null;
    }

    int count(int interfaceId) {
        return counts.getOrDefault(interfaceId, 0);
    }

    void acquire(int interfaceId) {
        counts.put(interfaceId, count(interfaceId) + 1);
    }

    /** True only when this release should call Android releaseInterface(). */
    boolean release(Object owner, int interfaceId) {
        if (owner != connection) return false;
        int count = count(interfaceId);
        if (count == 0) return false;
        if (count == 1) {
            counts.remove(interfaceId);
            return true;
        }
        counts.put(interfaceId, count - 1);
        return false;
    }
}
