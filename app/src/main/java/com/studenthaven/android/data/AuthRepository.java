package com.studenthaven.android.data;

import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;

public final class AuthRepository {
    public record Account(String name, String email, String expiresAt) {}
    private final BackendClient client;
    private final SessionStore store;

    public AuthRepository(BackendClient client, SessionStore store) {
        this.client = client;
        this.store = store;
    }

    public Account restore() throws BackendException {
        JSONObject session = session();
        return session == null ? null : account(session);
    }

    public Account signIn(boolean register, String name, String email, String password,
                          String confirmation) throws BackendException {
        try {
            JSONObject body = new JSONObject().put("email", email.trim()).put("password", password)
                    .put("device_name", "Student Haven Android");
            if (register) body.put("name", name.trim()).put("password_confirmation", confirmation);
            JSONObject response = client.request("POST", register ? "auth/register" : "auth/login", body, null);
            Account account = account(response);
            response.getString("token");
            store.save(response.toString());
            return account;
        } catch (JSONException exception) {
            throw new BackendException("The backend returned an invalid sign-in response.", exception);
        }
    }

    public Account check() throws BackendException {
        JSONObject session = session();
        if (session == null) {
            try {
                client.request("GET", "me", null, null);
            } catch (BackendException exception) {
                if (exception.status() == 401) return null;
                throw exception;
            }
            throw new BackendException("The backend did not require authentication as expected.", 0);
        }
        try {
            JSONObject profile = client.request("GET", "me", null, session.getString("token"));
            session.put("data", profile.getJSONObject("data"));
            Account account = account(session);
            store.save(session.toString());
            return account;
        } catch (BackendException exception) {
            if (exception.status() == 401) store.clear();
            throw exception;
        } catch (JSONException exception) {
            throw new BackendException("The backend returned an invalid profile response.", exception);
        }
    }

    public void logout() throws BackendException {
        JSONObject session = session();
        if (session == null) return;
        try {
            client.request("POST", "auth/logout", null, session.getString("token"));
            store.clear();
        } catch (BackendException exception) {
            if (exception.status() == 401) store.clear();
            throw exception;
        } catch (JSONException exception) {
            throw new BackendException("Cannot read the saved session. Use Forget local sign-in.", exception);
        }
    }

    public void forget() throws BackendException { store.clear(); }

    private JSONObject session() throws BackendException {
        String value = store.load();
        if (value == null) return null;
        try {
            JSONObject session = new JSONObject(value);
            if (Instant.parse(session.getString("expires_at")).isBefore(Instant.now())) {
                store.clear();
                throw new BackendException("Your session expired. Please sign in again.", 401);
            }
            return session;
        } catch (JSONException | java.time.format.DateTimeParseException exception) {
            throw new BackendException("Cannot read the saved session. Use Forget local sign-in.", exception);
        }
    }

    private Account account(JSONObject session) throws BackendException {
        try {
            JSONObject data = session.getJSONObject("data");
            return new Account(data.getString("name"), data.getString("email"), session.getString("expires_at"));
        } catch (JSONException exception) {
            throw new BackendException("The backend returned an incomplete account response.", exception);
        }
    }
}
