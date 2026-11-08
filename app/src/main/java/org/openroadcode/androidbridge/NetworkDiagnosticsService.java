package org.openroadcode.androidbridge;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Map;

/** Read-only Android network and USB inventory; no privileged shell access required. */
final class NetworkDiagnosticsService {
  private NetworkDiagnosticsService() {}

  static JSONObject snapshot(Context context) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("networks", networks(context));
    result.put("usb_devices", usbDevices(context));
    return result;
  }

  private static JSONArray networks(Context context) throws JSONException {
    JSONArray out = new JSONArray();
    ConnectivityManager manager =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (manager == null) return out;
    Network active = manager.getActiveNetwork();
    for (Network network : manager.getAllNetworks()) {
      JSONObject entry = new JSONObject();
      entry.put("active", network.equals(active));
      NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
      JSONArray transports = new JSONArray();
      if (capabilities != null) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.put("ethernet");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.put("vpn");
        entry.put("internet_capability", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        entry.put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
      }
      entry.put("transports", transports);
      LinkProperties properties = manager.getLinkProperties(network);
      if (properties != null) {
        entry.put("interface", properties.getInterfaceName());
        JSONArray addresses = new JSONArray();
        for (LinkAddress address : properties.getLinkAddresses()) addresses.put(address.toString());
        entry.put("addresses", addresses);
        JSONArray dns = new JSONArray();
        for (java.net.InetAddress address : properties.getDnsServers()) dns.put(address.getHostAddress());
        entry.put("dns", dns);
      }
      out.put(entry);
    }
    return out;
  }

  private static JSONArray usbDevices(Context context) throws JSONException {
    JSONArray out = new JSONArray();
    UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    if (manager == null) return out;
    for (Map.Entry<String, UsbDevice> item : manager.getDeviceList().entrySet()) {
      UsbDevice device = item.getValue();
      JSONObject entry = new JSONObject();
      entry.put("device_name", device.getDeviceName());
      entry.put("vendor_id", device.getVendorId());
      entry.put("product_id", device.getProductId());
      entry.put("device_class", device.getDeviceClass());
      entry.put("device_subclass", device.getDeviceSubclass());
      entry.put("interface_count", device.getInterfaceCount());
      entry.put("permission_granted", manager.hasPermission(device));
      out.put(entry);
    }
    return out;
  }
}
