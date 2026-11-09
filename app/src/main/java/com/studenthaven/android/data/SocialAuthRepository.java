package com.studenthaven.android.data;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import org.json.JSONException;
import org.json.JSONObject;

public final class SocialAuthRepository {
    public record Launch(String provider, String attemptId, String url) {}
    private final BackendClient client;
    private final AuthRepository auth;
    private final SessionStore pendingStore;

    public SocialAuthRepository(Context context, BackendClient client, AuthRepository auth) {
        this(client, auth, new SessionStore(context, "social-auth-pending"));
    }

    SocialAuthRepository(BackendClient client, AuthRepository auth, SessionStore pendingStore) {
        this.client = client;
        this.auth = auth;
        this.pendingStore = pendingStore;
    }

    public String pendingProvider() throws BackendException {
        JSONObject pending = pending();
        return pending == null ? null : pending.optString("provider");
    }

    public Launch start(String provider) throws BackendException {
        if (!provider.equals("google") && !provider.equals("microsoft")) {
            throw new BackendException("Unsupported sign-in provider.", 0);
        }
        if (auth.restore() != null) throw new BackendException("Sign out before switching accounts.", 0);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String verifier = encode(random);
        try {
            String challenge = encode(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            JSONObject response = client.request("POST", "auth/social/" + provider + "/start",
                    new JSONObject().put("challenge", challenge), null);
            String id = response.getString("attempt_id");
            if (!id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new JSONException("Invalid attempt ID");
            }
            String url = response.getString("authorization_url");
            Uri parsed = Uri.parse(url);
            String host = provider.equals("google") ? "accounts.google.com" : "login.microsoftonline.com";
            if (!"https".equals(parsed.getScheme()) || !host.equals(parsed.getHost())
                    || parsed.getUserInfo() != null || (parsed.getPort() != -1 && parsed.getPort() != 443)) {
                throw new JSONException("Invalid authorization URL");
            }
            pendingStore.save(new JSONObject().put("provider", provider).put("attempt_id", id)
                    .put("verifier", verifier).put("expires_at", Instant.now().plusSeconds(600).toString()).toString());
            return new Launch(provider, id, url);
        } catch (JSONException | NoSuchAlgorithmException exception) {
            throw new BackendException("Cannot prepare secure provider sign-in. Please try again.", exception);
        }
    }

    public AuthRepository.Account finish(String expectedAttempt) throws BackendException {
        JSONObject pending = pending();
        if (pending == null) throw new BackendException("No provider sign-in is pending. Please start again.", 0);
        try {
            if (expectedAttempt != null && !pending.getString("attempt_id").equals(expectedAttempt)) {
                throw new BackendException("This sign-in callback does not match your pending request.", 0);
            }
            JSONObject response = client.request("POST", "auth/social/exchange", new JSONObject()
                    .put("attempt_id", pending.getString("attempt_id")).put("verifier", pending.getString("verifier"))
                    .put("device_name", "Student Haven Android"), null);
            auth.acceptSocialSession(response);
            pendingStore.clear();
            return auth.restore();
        } catch (BackendException exception) {
            if (exception.status() == 400 || exception.status() == 404 || exception.status() == 410) pendingStore.clear();
            throw exception;
        } catch (JSONException exception) {
            throw new BackendException("Cannot read pending provider sign-in. Cancel and try again.", exception);
        }
    }

    public void cancel() throws BackendException { pendingStore.clear(); }

    private JSONObject pending() throws BackendException {
        String stored = pendingStore.load();
        if (stored == null) return null;
        try {
            JSONObject pending = new JSONObject(stored);
            if (!Instant.parse(pending.getString("expires_at")).isAfter(Instant.now())) {
                pendingStore.clear();
                throw new BackendException("Provider sign-in expired. Please start again.", 400);
            }
            return pending;
        } catch (JSONException | java.time.format.DateTimeParseException exception) {
            throw new BackendException("Cannot read pending sign-in. Cancel and try again.", exception);
        }
    }

    private String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
