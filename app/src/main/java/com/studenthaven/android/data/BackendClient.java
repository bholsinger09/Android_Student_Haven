package com.studenthaven.android.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class BackendClient {
    private final String baseUrl;

    public BackendClient(String baseUrl) { this.baseUrl = baseUrl; }

    public JSONObject request(String method, String path, JSONObject body, String token)
            throws BackendException {
        if (baseUrl.isEmpty()) throw new BackendException("No backend is configured for this build.", 0);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (var output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            if (status == 204) return new JSONObject();
            String text;
            try (InputStream input = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream()) {
                text = read(input);
            }
            JSONObject json;
            try {
                json = new JSONObject(text);
            } catch (JSONException exception) {
                throw new BackendException("The backend returned an unexpected response (HTTP "
                        + status + "). Check the local server.", status);
            }
            if (status < 200 || status >= 300) {
                if (path.startsWith("auth/social/") && (status == 400 || status == 409 || status == 503)) {
                    throw new BackendException(json.optString("message", "Social sign-in failed. Please try again."),
                            status, json);
                }
                if (status == 409) throw new BackendException("parent_deleted".equals(json.optString("code"))
                        ? "The parent class was deleted. Your note is preserved as a local recovery draft."
                        : json.optJSONObject("conflict") == null
                        ? "Synchronization was rejected (HTTP 409). The saved request is unchanged. Check the backend configuration."
                        : "This record changed on another device. Choose which version to keep.", status, json);
                if (status == 401) throw new BackendException("Your session expired. Please sign in again.", status);
                if (status == 429) throw new BackendException("Too many attempts. Please wait a minute and try again.", status);
                if (status == 422) {
                    JSONObject errors = json.optJSONObject("errors");
                    if (errors != null) {
                        StringBuilder message = new StringBuilder();
                        var keys = errors.keys();
                        while (keys.hasNext()) {
                            JSONArray messages = errors.optJSONArray(keys.next());
                            if (messages != null && messages.length() > 0) {
                                if (message.length() > 0) message.append('\n');
                                message.append(messages.optString(0));
                            }
                        }
                        if (message.length() > 0) throw new BackendException(message.toString(), status);
                    }
                }
                throw new BackendException("Backend request failed (HTTP " + status + "). Please try again.", status);
            }
            return json;
        } catch (IOException exception) {
            throw new BackendException("Cannot reach the local backend. Start Laravel on port 8088 "
                    + "and run adb reverse tcp:8088 tcp:8088, then try again.", exception);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String read(InputStream input) throws IOException {
        if (input == null) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 4194304) throw new IOException("Backend response too large.");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
