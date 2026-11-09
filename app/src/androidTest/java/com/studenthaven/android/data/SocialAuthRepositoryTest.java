package com.studenthaven.android.data;

import static org.junit.Assert.*;
import android.content.Context;
import android.util.Base64;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SocialAuthRepositoryTest {
    private static final String ATTEMPT = "0472d138-6ba7-4133-a02d-c903228e019e";
    private final Context context = ApplicationProvider.getApplicationContext();
    private SessionStore session;
    private SessionStore pending;

    @Before public void setUp() throws Exception {
        session = new SessionStore(context, "social-protocol-session-test");
        pending = new SessionStore(context, "social-protocol-pending-test");
        session.clear();
        pending.clear();
    }

    @After public void tearDown() throws Exception {
        session.clear();
        pending.clear();
    }

    @Test public void proofAndAccountSurviveRestartAndUnexpectedCallbackCannotExchange() throws Exception {
        try (Replies replies = new Replies(new Reply(200, launch("https://accounts.google.com/o/oauth2/auth")),
                new Reply(200, token()))) {
            BackendClient client = replies.client();
            AuthRepository auth = new AuthRepository(client, session);
            SocialAuthRepository social = new SocialAuthRepository(client, auth, pending);
            var launch = social.start("google");
            assertEquals(ATTEMPT, launch.attemptId());
            assertEquals("google", social.pendingProvider());
            String saved = pending.load();
            String verifier = new JSONObject(saved).getString("verifier");
            assertEquals(43, verifier.length());
            String encrypted = context.getSharedPreferences("social-protocol-pending-test", Context.MODE_PRIVATE)
                    .getString("ciphertext", "");
            assertFalse(encrypted.contains(verifier));
            assertFalse(encrypted.contains(ATTEMPT));
            SocialAuthRepository restored = new SocialAuthRepository(client,
                    new AuthRepository(client, new SessionStore(context, "social-protocol-session-test")),
                    new SessionStore(context, "social-protocol-pending-test"));
            try {
                restored.finish("ffffffff-ffff-ffff-ffff-ffffffffffff");
                fail("Unexpected callback must not exchange");
            } catch (BackendException expected) { assertTrue(expected.getMessage().contains("does not match")); }
            assertEquals(1, replies.requests.size());
            assertEquals("social-17", restored.finish(ATTEMPT).id());
            assertEquals("Provider Student", auth.restore().name());
            assertNull(pending.load());
            JSONObject start = replies.requests.get(0).body();
            JSONObject exchange = replies.requests.get(1).body();
            assertEquals("POST /auth/social/google/start HTTP/1.1", replies.requests.get(0).line());
            assertEquals(verifier, exchange.getString("verifier"));
            assertEquals(ATTEMPT, exchange.getString("attempt_id"));
            assertEquals(Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), start.getString("challenge"));
            assertFalse(start.has("verifier"));
            try { restored.finish(ATTEMPT); fail("A consumed attempt cannot exchange again"); }
            catch (BackendException expected) { assertTrue(expected.getMessage().contains("No provider sign-in")); }
        }
    }

    @Test public void browserStillPendingRetainsProofForExplicitRetry() throws Exception {
        try (Replies replies = new Replies(new Reply(200, launch("https://login.microsoftonline.com/common/oauth2/v2.0/authorize")),
                new Reply(409, "{\"code\":\"authorization_pending\",\"message\":\"Finish in your browser.\"}"),
                new Reply(200, token()))) {
            SocialAuthRepository social = repository(replies.client());
            social.start("microsoft");
            String saved = pending.load();
            try { social.finish(null); fail("Still pending"); }
            catch (BackendException expected) {
                assertEquals(409, expected.status());
                assertEquals("Finish in your browser.", expected.getMessage());
            }
            assertEquals(saved, pending.load());
            assertEquals("social-17", social.finish(null).id());
            assertNull(social.pendingProvider());
        }
    }

    @Test public void failedOrConsumedExchangeClearsAttemptAndAllowsNewSignIn() throws Exception {
        try (Replies replies = new Replies(new Reply(200, launch("https://accounts.google.com/o/oauth2/auth")),
                new Reply(400, "{\"message\":\"Sign-in was cancelled. Please try again.\"}"))) {
            SocialAuthRepository social = repository(replies.client());
            social.start("google");
            try { social.finish(ATTEMPT); fail("Cancelled"); }
            catch (BackendException expected) { assertEquals(400, expected.status()); }
            assertNull(pending.load());
            assertNull(session.load());
        }
    }

    @Test public void rejectsUntrustedBrowserUrlWithoutSavingProof() throws Exception {
        for (String url : List.of("http://accounts.google.com/login", "https://evil.example/login",
                "https://accounts.google.com.evil.example/login", "https://attacker@accounts.google.com/login")) {
            try (Replies replies = new Replies(new Reply(200, launch(url)))) {
                try { repository(replies.client()).start("google"); fail("Reject untrusted URL"); }
                catch (BackendException expected) { assertTrue(expected.getMessage().contains("secure provider")); }
                assertNull(pending.load());
            }
        }
    }

    @Test public void missingConfigurationIsExplicitAndCancellationRemovesProof() throws Exception {
        try (Replies replies = new Replies(new Reply(503, "{\"message\":\"Google sign-in is not configured yet.\"}"))) {
            try { repository(replies.client()).start("google"); fail("Not configured"); }
            catch (BackendException expected) { assertTrue(expected.getMessage().contains("not configured yet")); }
            assertNull(pending.load());
        }
        try (Replies replies = new Replies(new Reply(200, launch("https://accounts.google.com/o/oauth2/auth")))) {
            SocialAuthRepository social = repository(replies.client());
            social.start("google");
            social.cancel();
            assertNull(pending.load());
            assertNull(session.load());
        }
    }

    @Test public void networkFailureRetainsPendingProofButExpiryClearsIt() throws Exception {
        BackendClient client;
        try (Replies replies = new Replies(new Reply(200, launch("https://accounts.google.com/o/oauth2/auth")))) {
            client = replies.client();
            repository(client).start("google");
        }
        SocialAuthRepository social = repository(client);
        String saved = pending.load();
        try { social.finish(ATTEMPT); fail("Server is stopped"); }
        catch (BackendException expected) { assertTrue(expected.getMessage().contains("Cannot reach")); }
        assertEquals(saved, pending.load());
        pending.save(new JSONObject(saved).put("expires_at", "2020-01-01T00:00:00Z").toString());
        try { social.finish(ATTEMPT); fail("Expired"); }
        catch (BackendException expected) { assertTrue(expected.getMessage().contains("expired")); }
        assertNull(pending.load());
    }

    private SocialAuthRepository repository(BackendClient client) {
        return new SocialAuthRepository(client, new AuthRepository(client, session), pending);
    }

    private String launch(String url) throws Exception {
        return new JSONObject().put("attempt_id", ATTEMPT).put("authorization_url", url).toString();
    }

    private String token() throws Exception {
        return new JSONObject().put("token", "instrumentation-only").put("token_type", "Bearer")
                .put("expires_at", Instant.now().plusSeconds(3600).toString())
                .put("data", new JSONObject().put("id", "social-17").put("name", "Provider Student")
                        .put("email", "student@example.com")).toString();
    }

    private record Reply(int status, String body) {}
    private record Request(String line, JSONObject body) {}

    private static final class Replies implements AutoCloseable {
        final List<Request> requests = new CopyOnWriteArrayList<>();
        final ServerSocket server;
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final java.util.concurrent.Future<?> future;

        Replies(Reply... replies) throws Exception {
            server = new ServerSocket(0);
            server.setSoTimeout(10000);
            future = executor.submit(() -> {
                for (Reply reply : replies) {
                    try (var socket = server.accept()) {
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String first = input.readLine();
                        int length = 0;
                        String header;
                        while ((header = input.readLine()) != null && !header.isEmpty()) {
                            if (header.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                                length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                            }
                        }
                        char[] body = new char[length];
                        int read = 0;
                        while (read < length) {
                            int count = input.read(body, read, length - read);
                            if (count < 0) throw new java.io.EOFException("Truncated request");
                            read += count;
                        }
                        requests.add(new Request(first, new JSONObject(new String(body))));
                        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 " + reply.status() + " Test\r\n"
                                + "Content-Type: application/json\r\nContent-Length: " + bytes.length
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(bytes);
                    } catch (Exception exception) { throw new RuntimeException(exception); }
                }
            });
        }

        BackendClient client() { return new BackendClient("http://127.0.0.1:" + server.getLocalPort() + "/"); }

        @Override public void close() throws Exception {
            try { future.get(15, TimeUnit.SECONDS); }
            finally { server.close(); executor.shutdownNow(); }
        }
    }
}
