package org.openroadcode.androidbridge;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Client for the localhost-only OpenRoadCode service manager running in Termux. */
public final class TermuxServiceManagerClient {
    public static final String BASE_URL = "http://127.0.0.1:8768";

    public JSONObject getServices() throws Exception {
        return request("GET", "/services");
    }

    public JSONObject startCoreStack() throws Exception {
        return request("POST", "/stack/core/start");
    }

    public JSONObject stopCoreStack() throws Exception {
        return request("POST", "/stack/core/stop");
    }

    public JSONObject startService(String service) throws Exception {
        return serviceAction(service, "start");
    }

    public JSONObject stopService(String service) throws Exception {
        return serviceAction(service, "stop");
    }

    public JSONObject restartService(String service) throws Exception {
        return serviceAction(service, "restart");
    }

    private JSONObject serviceAction(String service, String action) throws Exception {
        if (!service.matches("openroadcode-(broker|navigation|automotive|adsb)")) {
            throw new IllegalArgumentException("Unsupported OpenRoadCode service: " + service);
        }
        return request("POST", "/services/" + service + "/" + action);
    }

    private JSONObject request(String method, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(750);
        connection.setReadTimeout(1500);
        connection.setUseCaches(false);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
        }
        try {
            int status = connection.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    status >= 200 && status < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream(),
                    StandardCharsets.UTF_8));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            reader.close();
            JSONObject response = new JSONObject(body.toString());
            if (status < 200 || status >= 300) {
                throw new IllegalStateException(response.optString("error", "Termux service request failed"));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }
}
