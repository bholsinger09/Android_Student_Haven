package com.studenthaven.android.data;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SessionStoreTest {
    @Test public void sessionIsEncryptedPersistsAndCanBeRemoved() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String name = "session-test-" + UUID.randomUUID();
        SessionStore store = new SessionStore(context, name);
        try {
            assertNull(store.load());
            store.save("sensitive-bearer-token");
            assertEquals("sensitive-bearer-token", new SessionStore(context, name).load());
            String saved = context.getSharedPreferences(name, 0).getAll().toString();
            assertFalse(saved.contains("sensitive-bearer-token"));
            store.save("replacement-token");
            assertEquals("replacement-token", store.load());
            store.clear();
            assertNull(store.load());
        } finally {
            context.deleteSharedPreferences(name);
        }
    }

    @Test public void corruptedSessionFailsExplicitlyAndCanBeForgotten() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String name = "session-test-" + UUID.randomUUID();
        SessionStore store = new SessionStore(context, name);
        try {
            store.save("private");
            context.getSharedPreferences(name, 0).edit().putString("ciphertext", "broken").commit();
            try {
                store.load();
                fail("Expected corrupted-session failure");
            } catch (BackendException expected) {
                assertTrue(expected.getMessage().contains("Forget local sign-in"));
            }
            store.clear();
            assertNull(store.load());
        } finally {
            context.deleteSharedPreferences(name);
        }
    }

    @Test public void expiredSessionIsRemovedWithoutNetworkAccess() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String name = "session-test-" + UUID.randomUUID();
        SessionStore store = new SessionStore(context, name);
        try {
            store.save("{\"expires_at\":\"2020-01-01T00:00:00Z\"}");
            AuthRepository repository = new AuthRepository(new BackendClient(""), store);
            try {
                repository.restore();
                fail("Expected expired session");
            } catch (BackendException expected) {
                assertEquals(401, expected.status());
            }
            assertNull(store.load());
        } finally {
            context.deleteSharedPreferences(name);
        }
    }
}
