package com.studenthaven.android.data;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.studenthaven.android.BuildConfig;
import com.studenthaven.android.BackendTestAccounts;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ClassSyncIntegrationTest {
    @Test public void twoOfflineDevicesRetryResolveConflictsDeleteAndIsolateAccounts() throws Exception {
        Assume.assumeTrue("Enable backendIntegration for live local Laravel tests",
                "true".equals(InstrumentationRegistry.getArguments().getString("backendIntegration")));
        Context context = ApplicationProvider.getApplicationContext();
        String suffix = UUID.randomUUID().toString();
        String dbFile = "class-sync-device-a-" + suffix;
        String password = "Test-" + suffix;
        SessionStore storeA = new SessionStore(context, "sync-a-" + suffix);
        SessionStore storeB = new SessionStore(context, "sync-b-" + suffix);
        SessionStore storeOther = new SessionStore(context, "sync-other-" + suffix);
        BackendClient client = new BackendClient(BuildConfig.BACKEND_URL);
        AuthRepository authA = new AuthRepository(client, storeA);
        AuthRepository authB = new AuthRepository(client, storeB);
        AuthRepository authOther = new AuthRepository(client, storeOther);
        ClassSyncDatabase a = null;
        ClassSyncDatabase b = null;
        AuthRepository.Account account = null;
        AuthRepository.Account other = null;
        try {
            BackendTestAccounts.awaitRegistrationSlot();
            account = authA.signIn(true, "Sync Tester", "sync-" + suffix + "@example.com", password, password);
            authB.signIn(false, "", account.email(), password, "");
            a = Room.databaseBuilder(context, ClassSyncDatabase.class, dbFile).build();
            b = Room.inMemoryDatabaseBuilder(context, ClassSyncDatabase.class).build();
            ClassSyncRepository deviceA = new ClassSyncRepository(a, authA);
            ClassSyncRepository deviceB = new ClassSyncRepository(b, authB);
            String owner = account.id();
            deviceA.save(owner, null, "Offline Biology", "BIO", "Professor", "Room 1");
            String id = deviceA.list(owner).get(0).id;
            assertEquals(0, authA.requestFor(owner, "GET", "courses", null).getJSONArray("data").length());
            try (java.net.ServerSocket unavailable = new java.net.ServerSocket(0)) {
                int port = unavailable.getLocalPort();
                unavailable.close();
                AuthRepository offline = new AuthRepository(
                        new BackendClient("http://127.0.0.1:" + port + "/api/v1/"), storeA);
                try {
                    new ClassSyncRepository(a, offline).synchronize(owner);
                    fail("Offline sync should report failure and retain the request");
                } catch (BackendException expected) {
                    assertEquals(0, expected.status());
                }
            }
            assertNotNull(a.syncDao().find(owner, id).attempt);
            deviceA.synchronize(owner);
            deviceB.synchronize(owner);
            assertEquals("Offline Biology", b.syncDao().find(owner, id).name);
            assertEquals(1, a.syncDao().find(owner, id).version);
            assertFalse(a.syncDao().find(owner, id).dirty);

            // Simulate server commit with a lost response, followed by process restart.
            deviceA.save(owner, null, "Lost acknowledgement", "", "", "");
            var pending = deviceA.list(owner).stream()
                    .filter(item -> item.name.equals("Lost acknowledgement")).findFirst().orElseThrow();
            pending.attempt = new JSONObject().put("mutation_id", UUID.randomUUID().toString())
                    .put("base_version", 0).put("deleted", false).put("name", pending.name)
                    .put("code", "").put("professor", "").put("location", "").toString();
            a.syncDao().put(pending);
            authA.requestFor(owner, "PUT", "sync/courses/" + pending.id, new JSONObject(pending.attempt));
            a.close();
            a = Room.databaseBuilder(context, ClassSyncDatabase.class, dbFile).build();
            deviceA = new ClassSyncRepository(a, authA);
            deviceA.synchronize(owner);
            assertEquals(1, a.syncDao().find(owner, pending.id).version);
            assertNull(a.syncDao().find(owner, pending.id).attempt);
            assertEquals(2, authA.requestFor(owner, "GET", "courses", null).getJSONArray("data").length());

            deviceA.save(owner, id, "Local Biology", "BIO", "Local professor", "Room A");
            deviceB.save(owner, id, "Remote Biology", "BIO", "Remote professor", "Room B");
            deviceB.synchronize(owner);
            deviceA.synchronize(owner);
            var conflict = a.syncDao().find(owner, id);
            assertEquals("Local Biology", conflict.name);
            assertEquals("Remote Biology", new JSONObject(conflict.conflict).getString("name"));
            assertTrue(conflict.dirty);
            deviceA.resolve(owner, id, true);
            deviceA.synchronize(owner);
            deviceB.synchronize(owner);
            assertEquals("Local Biology", b.syncDao().find(owner, id).name);
            assertEquals(3, b.syncDao().find(owner, id).version);
            deviceA.save(owner, id, "Edit versus delete", "BIO", "", "");
            deviceB.delete(owner, id);
            deviceB.synchronize(owner);
            deviceA.synchronize(owner);
            assertNotNull(a.syncDao().find(owner, id).conflict);
            assertFalse(new JSONObject(a.syncDao().find(owner, id).conflict).isNull("deleted_at"));
            deviceA.resolve(owner, id, false);
            assertTrue(a.syncDao().find(owner, id).deleted);
            deviceA.synchronize(owner);
            assertTrue(a.syncDao().find(owner, id).deleted);
            assertFalse(a.syncDao().find(owner, id).dirty);
            deviceB.synchronize(owner);
            assertTrue(b.syncDao().find(owner, id).deleted);

            BackendTestAccounts.awaitRegistrationSlot();
            other = authOther.signIn(true, "Other Account", "sync-other-" + suffix + "@example.com",
                    password, password);
            ClassSyncRepository switched = new ClassSyncRepository(a, authOther);
            assertTrue(switched.list(other.id()).isEmpty());
            switched.synchronize(other.id());
            assertTrue(switched.list(other.id()).isEmpty());
            deviceA.save(owner, null, "Private offline draft", "", "", "");
            try {
                switched.synchronize(owner);
                fail("Wrong account must never upload another account's draft");
            } catch (BackendException expected) {
                assertEquals(401, expected.status());
            }
            assertEquals(0, authOther.requestFor(other.id(), "GET", "courses", null).getJSONArray("data").length());
            deviceA.synchronize(owner);
            assertEquals(2, authA.requestFor(owner, "GET", "courses", null).getJSONArray("data").length());
        } finally {
            if (a != null) a.close();
            if (b != null) b.close();
            context.deleteDatabase(dbFile);
            if (account != null) authA.requestFor(account.id(), "DELETE", "me", new JSONObject().put("password", password));
            if (other != null) authOther.requestFor(other.id(), "DELETE", "me", new JSONObject().put("password", password));
            context.deleteSharedPreferences("sync-a-" + suffix);
            context.deleteSharedPreferences("sync-b-" + suffix);
            context.deleteSharedPreferences("sync-other-" + suffix);
        }
    }
}
