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
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;

/** Read-only Android network and USB inventory; no privileged shell access required. */
final class NetworkDiagnosticsService {
  private NetworkDiagnosticsService() {}

  static JSONObject snapshot(Context context) throws JSONException {
    JSONObject result = new JSONObject();
    result.put("networks", networks(context));
    result.put("usb_devices", usbDevices(context));
    result.put("system_managed_networks", systemManagedNetworks(context));
    result.put("usb_sysfs", usbSysfs());
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

  /** Network evidence is not proof of USB backing: Android may hide the physical bus. */
  private static JSONArray systemManagedNetworks(Context context) throws JSONException {
    JSONArray out = new JSONArray();
    ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    if (manager == null) return out;
    for (Network network : manager.getAllNetworks()) {
      NetworkCapabilities caps = manager.getNetworkCapabilities(network);
      if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
      JSONObject entry = new JSONObject();
      entry.put("transport", "ethernet");
      entry.put("usb_backing", "unconfirmed");
      entry.put("active", network.equals(manager.getActiveNetwork()));
      LinkProperties props = manager.getLinkProperties(network);
      if (props != null) {
        entry.put("interface", props.getInterfaceName());
        JSONArray addresses = new JSONArray();
        for (LinkAddress address : props.getLinkAddresses()) addresses.put(address.toString());
        entry.put("addresses", addresses);
      }
      out.put(entry);
    }
    return out;
  }

  private static String readFirstLine(File file) throws java.io.IOException {
    try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
      String line = reader.readLine();
      if (line == null) throw new java.io.IOException("Empty sysfs attribute");
      return line.trim();
    }
  }

  /** Best-effort sysfs probe; never assume unreadable paths imply absent hardware. */
  private static JSONObject usbSysfs() throws JSONException {
    JSONObject result = new JSONObject();
    JSONArray devices = new JSONArray();
    File root = new File("/sys/bus/usb/devices");
    File[] entries = root.listFiles();
    if (entries == null) {
      result.put("status", "unavailable_or_restricted");
      result.put("devices", devices);
      return result;
    }
    int readable = 0;
    for (File entry : entries) {
      File vendor = new File(entry, "idVendor");
      File product = new File(entry, "idProduct");
      if (!vendor.isFile() || !product.isFile()) continue;
      try {
        String vid = readFirstLine(vendor);
        String pid = readFirstLine(product);
        JSONObject item = new JSONObject();
        item.put("sysfs_name", entry.getName());
        item.put("vendor_id_hex", vid);
        item.put("product_id_hex", pid);
        File driver = new File(entry, "driver");
        try {
          if (driver.getCanonicalPath().contains("/drivers/"))
            item.put("driver", driver.getCanonicalFile().getName());
        } catch (Exception ignored) { }
        devices.put(item);
        readable++;
      } catch (Exception ignored) { }
    }
    result.put("status", readable > 0 ? "partial_or_available" : "no_readable_device_metadata");
    result.put("devices", devices);
    return result;
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
