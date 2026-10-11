package org.openroadcode.androidbridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONObject;

/** Track actual default-network validation without making internet requests. */
final class NetworkStateMonitor {
    private final ConnectivityManager manager;
    private Network current;
    private boolean available;
    private boolean validated;
    private String transport = "none";
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            synchronized (NetworkStateMonitor.this) {
                current = network;
                validated = false;
                transport = "unknown";
            }
        }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            synchronized (NetworkStateMonitor.this) {
                if (network.equals(current)) update(capabilities);
            }
        }
        @Override public void onLost(Network network) {
            synchronized (NetworkStateMonitor.this) {
                if (network.equals(current)) {
                    current = null;
                    validated = false;
                    transport = "none";
                }
            }
        }
    };

    NetworkStateMonitor(Context context) {
        manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        try {
            synchronized (this) {
                current = manager.getActiveNetwork();
                update(current == null ? null : manager.getNetworkCapabilities(current));
                manager.registerDefaultNetworkCallback(callback);
                available = true;
            }
        } catch (RuntimeException ignored) {
            available = false;
        }
    }

    private void update(NetworkCapabilities capabilities) {
        validated = capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        transport = capabilities == null ? "none"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                : capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn" : "other";
    }

    synchronized String json() {
        JSONObject result = new JSONObject();
        try {
            result.put("available", available);
            result.put("connected", current != null);
            result.put("validated", validated);
            result.put("transport", transport);
            result.put("timestamp_ms", System.currentTimeMillis());
        } catch (org.json.JSONException ignored) { }
        return result.toString();
    }

    void close() {
        if (manager != null && available) {
            try { manager.unregisterNetworkCallback(callback); }
            catch (RuntimeException ignored) { }
        }
        synchronized (this) { available = false; }
    }
}
