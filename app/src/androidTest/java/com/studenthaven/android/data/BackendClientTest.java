package com.studenthaven.android.data;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BackendClientTest {
    @Test public void decodesProfileAndReportsValidationAuthenticationAndProtocolErrors() throws Exception {
        assertEquals("Student", response(200, "{\"data\":{\"name\":\"Student\"}}").getJSONObject("data").getString("name"));
        assertEquals(0, response(204, "").length());
        expectFailure(422, "{\"errors\":{\"email\":[\"Email already registered.\"]}}", "Email already registered.");
        expectFailure(401, "{}", "Your session expired");
        expectFailure(429, "{}", "Too many attempts");
        expectFailure(500, "{}", "HTTP 500");
        expectFailure(200, "<html>Not JSON</html>", "unexpected response");
        expectFailure(302, "", "HTTP 302");
    }

    @Test public void unavailableServerAndUnconfiguredBuildAreExplicitErrors() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0)) { port = server.getLocalPort(); }
        try {
            new BackendClient("http://127.0.0.1:" + port + "/").request("GET", "me", null, null);
            fail("Expected connection failure");
        } catch (BackendException expected) {
            assertTrue(expected.getMessage().contains("Cannot reach"));
        }
        try {
            new BackendClient("").request("GET", "me", null, null);
            fail("Expected configuration failure");
        } catch (BackendException expected) {
            assertTrue(expected.getMessage().contains("No backend"));
        }
    }

    private void expectFailure(int status, String body, String message) throws Exception {
        try {
            response(status, body);
            fail("Expected HTTP failure");
        } catch (BackendException expected) {
            assertEquals(status, expected.status());
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }

    private org.json.JSONObject response(int status, String body) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(10000);
            Future<?> reply = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) {}
                    byte[] content = body.getBytes(StandardCharsets.UTF_8);
                    String header = "HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + content.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(content);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
            try {
                return new BackendClient("http://127.0.0.1:" + server.getLocalPort() + "/")
                        .request("GET", "me", null, null);
            } finally {
                reply.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
