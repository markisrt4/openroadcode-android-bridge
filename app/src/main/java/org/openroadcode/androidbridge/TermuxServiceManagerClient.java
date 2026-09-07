package org.openroadcode.androidbridge;

import org.json.JSONObject;

/** Backwards-compatible localhost adapter for the Termux runtime service manager. */
public final class TermuxServiceManagerClient {
  public static final String BASE_URL = "http://127.0.0.1:8768";

  private final RuntimeServiceManagerClient delegate =
      new RuntimeServiceManagerClient(BASE_URL, "Termux");

  public JSONObject getServices() throws Exception {
    return delegate.getServices();
  }

  public JSONObject startCoreStack() throws Exception {
    return delegate.startCoreStack();
  }

  public JSONObject stopCoreStack() throws Exception {
    return delegate.stopCoreStack();
  }

  public JSONObject startService(String service) throws Exception {
    return delegate.startService(service);
  }

  public JSONObject stopService(String service) throws Exception {
    return delegate.stopService(service);
  }

  public JSONObject restartService(String service) throws Exception {
    return delegate.restartService(service);
  }
}
