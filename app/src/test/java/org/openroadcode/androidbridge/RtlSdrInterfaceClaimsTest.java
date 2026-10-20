package org.openroadcode.androidbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class RtlSdrInterfaceClaimsTest {
    @Test public void oldStreamCleanupCannotReleaseReplacementClaim() {
        RtlSdrInterfaceClaims claims = new RtlSdrInterfaceClaims();
        Object oldConnection = new Object();
        Object replacement = new Object();
        claims.useConnection(oldConnection);
        claims.acquire(0); // control
        claims.acquire(0); // IQ stream
        claims.clear(); // terminal USB failure closes the old handle
        claims.useConnection(replacement);
        claims.acquire(0); // replacement IQ stream
        assertFalse(claims.release(oldConnection, 0));
        assertFalse(claims.release(oldConnection, 0));
        assertEquals(1, claims.count(0));
        assertTrue(claims.release(replacement, 0));
        assertFalse(claims.release(replacement, 0));
    }

    @Test public void sharedClientsReleaseAndroidInterfaceOnlyOnce() {
        RtlSdrInterfaceClaims claims = new RtlSdrInterfaceClaims();
        Object connection = new Object();
        claims.useConnection(connection);
        claims.acquire(0);
        claims.acquire(0);
        assertFalse(claims.release(connection, 0));
        assertEquals(1, claims.count(0));
        assertTrue(claims.release(connection, 0));
        assertFalse(claims.release(connection, 0));
    }

    @Test public void replacementAndInterfacesHaveIndependentCounts() {
        RtlSdrInterfaceClaims claims = new RtlSdrInterfaceClaims();
        Object oldConnection = new Object();
        Object replacement = new Object();
        claims.useConnection(oldConnection);
        claims.acquire(0);
        claims.useConnection(replacement);
        assertEquals(0, claims.count(0));
        claims.acquire(0);
        claims.acquire(1);
        claims.useConnection(replacement); // same connection preserves its claims
        assertTrue(claims.release(replacement, 1));
        assertEquals(1, claims.count(0));
        assertFalse(claims.release(oldConnection, 0));
        assertTrue(claims.release(replacement, 0));
    }
}
