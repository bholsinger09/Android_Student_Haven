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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NoteSyncIntegrationTest {
    private Context context;
    private String suffix, password, file, owner, source, target;
    private AuthRepository authA, authB;
    private ClassSyncDatabase a, b;
    private ClassSyncRepository deviceA, deviceB;

    @Before public void setup() throws Exception {
        Assume.assumeTrue("Enable backendIntegration for live note tests",
                "true".equals(InstrumentationRegistry.getArguments().getString("backendIntegration")));
        context = ApplicationProvider.getApplicationContext();
        suffix = UUID.randomUUID().toString();
        password = "Test-" + suffix;
        file = "note-device-a-" + suffix;
        authA = new AuthRepository(new BackendClient(BuildConfig.BACKEND_URL), new SessionStore(context, "note-a-" + suffix));
        authB = new AuthRepository(new BackendClient(BuildConfig.BACKEND_URL), new SessionStore(context, "note-b-" + suffix));
        BackendTestAccounts.awaitRegistrationSlot();
        var account = authA.signIn(true, "Notes Tester", "notes-" + suffix + "@example.com", password, password);
        owner = account.id();
        authB.signIn(false, "", account.email(), password, "");
        a = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build();
        b = Room.inMemoryDatabaseBuilder(context, ClassSyncDatabase.class).build();
        deviceA = new ClassSyncRepository(a, authA);
        deviceB = new ClassSyncRepository(b, authB);
        deviceA.save(owner, null, "Source", "", "", "");
        deviceA.save(owner, null, "Target", "", "", "");
        source = deviceA.list(owner).stream().filter(c -> c.name.equals("Source")).findFirst().orElseThrow().id;
        target = deviceA.list(owner).stream().filter(c -> c.name.equals("Target")).findFirst().orElseThrow().id;
        deviceA.synchronize(owner);
        deviceB.synchronize(owner);
    }

    @After public void cleanup() throws Exception {
        if (a != null) a.close();
        if (b != null) b.close();
        if (file != null) context.deleteDatabase(file);
        if (owner != null) authA.requestFor(owner, "DELETE", "me", new JSONObject().put("password", password));
        if (suffix != null) {
            context.deleteSharedPreferences("note-a-" + suffix);
            context.deleteSharedPreferences("note-b-" + suffix);
        }
    }

    private String note(String title) {
        deviceA.notes().save(owner, source, null, title, "Initial content", "biology\nexam");
        return deviceA.notes().list(owner).stream().filter(n -> n.title.equals(title)).findFirst().orElseThrow().id;
    }

    @Test public void twoOfflineDevicesPreserveBothConflictVersionsAndApplyExplicitChoices() throws Exception {
        String id = note("Offline note");
        assertEquals(0, authA.requestFor(owner, "GET", "courses/" + source + "/notes", null).getJSONArray("data").length());
        deviceA.synchronize(owner);
        deviceB.synchronize(owner);
        assertEquals("Initial content", b.syncDao().note(owner, id).content);
        assertEquals("[\"biology\",\"exam\"]", b.syncDao().note(owner, id).tags);
        deviceA.notes().save(owner, source, id, "Local title", "Local edit", "local");
        deviceB.notes().save(owner, source, id, "Remote title", "Remote edit", "remote");
        deviceB.synchronize(owner);
        deviceA.synchronize(owner);
        var conflict = a.syncDao().note(owner, id);
        assertEquals("Local edit", conflict.content);
        assertEquals("Remote edit", new JSONObject(conflict.conflict).getString("content"));
        assertEquals("Remote title", new JSONObject(conflict.conflict).getString("title"));
        a.close();
        a = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build();
        deviceA = new ClassSyncRepository(a, authA);
        assertEquals("Local edit", a.syncDao().note(owner, id).content);
        assertEquals("Remote edit", new JSONObject(a.syncDao().note(owner, id).conflict).getString("content"));
        deviceA.notes().resolve(owner, id, true);
        deviceA.synchronize(owner);
        deviceB.synchronize(owner);
        assertEquals("Local title", b.syncDao().note(owner, id).title);
        assertEquals("[\"local\"]", b.syncDao().note(owner, id).tags);
        assertEquals(3, b.syncDao().note(owner, id).version);
        deviceA.notes().save(owner, source, id, "Discard local", "Second local", "local");
        deviceB.notes().save(owner, source, id, "Keep server", "Second server", "server");
        deviceB.synchronize(owner);
        deviceA.synchronize(owner);
        deviceA.notes().resolve(owner, id, false);
        assertEquals("Second server", a.syncDao().note(owner, id).content);
        assertFalse(a.syncDao().note(owner, id).dirty);
        assertNull(a.syncDao().note(owner, id).conflict);
        deviceA.notes().save(owner, source, id, "Edit versus note deletion", "Pending", "");
        deviceB.notes().delete(owner, id);
        deviceB.synchronize(owner);
        deviceA.synchronize(owner);
        assertFalse(new JSONObject(a.syncDao().note(owner, id).conflict).isNull("deleted_at"));
        deviceA.notes().resolve(owner, id, false);
        assertTrue(a.syncDao().note(owner, id).deleted);
        assertFalse(a.syncDao().note(owner, id).dirty);
        assertTrue(deviceA.notes().list("another-account").isEmpty());
    }

    @Test public void deletedClassPreservesEditsNewNotesAndExistingConflictAsRecoveryDrafts() throws Exception {
        String edited = note("Existing edited note");
        String conflicted = note("Conflicted note");
        deviceA.synchronize(owner);
        deviceB.synchronize(owner);
        deviceA.notes().save(owner, source, conflicted, "Local conflict", "Local conflict content", "local");
        deviceB.notes().save(owner, source, conflicted, "Remote conflict", "Remote conflict content", "remote");
        deviceB.synchronize(owner);
        deviceA.synchronize(owner);
        assertNotNull(a.syncDao().note(owner, conflicted).conflict);
        deviceA.notes().save(owner, source, edited, "Keep offline edit", "Unsynced edit content", "pending");
        String unsent = note("Never uploaded");
        // A also has a pending class metadata edit when B deletes that class.
        deviceA.save(owner, source, "Local class edit", "", "", "");
        deviceB.delete(owner, source);
        deviceB.synchronize(owner);
        deviceA.synchronize(owner);
        assertNotNull(a.syncDao().find(owner, source).conflict);
        assertEquals(3, deviceA.notes().list(owner).stream().filter(n -> n.recovery).count());
        assertEquals("Unsynced edit content", a.syncDao().note(owner, edited).content);
        assertEquals("Remote conflict content", new JSONObject(a.syncDao().note(owner, conflicted).conflict).getString("content"));
        assertEquals(0, a.syncDao().note(owner, unsent).version);
        assertTrue(a.syncDao().note(owner, unsent).recovery);
        assertNull(a.syncDao().note(owner, edited).attempt);
        long cursor = 0;
        int serverNotes = 0;
        boolean more;
        do {
            JSONObject page = authA.requestFor(owner, "GET", "sync/notes?after=" + cursor, null);
            JSONArray rows = page.getJSONArray("data");
            for (int i = 0; i < rows.length(); i++) {
                assertNotEquals(unsent, rows.getJSONObject(i).getString("id"));
                assertFalse(rows.getJSONObject(i).isNull("deleted_at"));
                serverNotes++;
            }
            cursor = page.getLong("cursor");
            more = page.getBoolean("has_more");
        } while (more);
        assertEquals(2, serverNotes);
        try {
            deviceA.notes().save(owner, source, edited, "Cannot upload here", "", "");
            fail("Deleted parent cannot receive note edits");
        } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        deviceA.resolve(owner, source, false);
        deviceA.notes().copyRecovery(owner, edited, target);
        String copy = a.syncDao().note(owner, edited).recoveredCopyId;
        assertEquals("Unsynced edit content", a.syncDao().note(owner, copy).content);
        deviceA.synchronize(owner);
        deviceB.synchronize(owner);
        assertEquals("Unsynced edit content", b.syncDao().note(owner, copy).content);
        assertEquals("[\"pending\"]", b.syncDao().note(owner, copy).tags);
        assertEquals(target, b.syncDao().note(owner, copy).courseId);
        assertTrue(a.syncDao().note(owner, edited).recovery);
        assertTrue(a.syncDao().find(owner, source).deleted);
        assertTrue(b.syncDao().find(owner, source).deleted);
    }

    @Test public void deletionAfterClassPullIsRejectedAndLostAcknowledgementsRetryWithoutDuplicates() throws Exception {
        String id = note("Lost acknowledgement");
        var pending = a.syncDao().note(owner, id);
        pending.attempt = new JSONObject().put("mutation_id", UUID.randomUUID().toString())
                .put("base_version", 0).put("deleted", false).put("title", pending.title)
                .put("content", pending.content).put("tags", new JSONArray(pending.tags)).toString();
        a.syncDao().putNote(pending);
        authA.requestFor(owner, "PUT", "sync/courses/" + source + "/notes/" + id, new JSONObject(pending.attempt));
        a.close();
        a = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build();
        deviceA = new ClassSyncRepository(a, authA);
        deviceA.synchronize(owner);
        assertEquals(1, a.syncDao().note(owner, id).version);
        assertFalse(a.syncDao().note(owner, id).dirty);
        assertNull(a.syncDao().note(owner, id).attempt);
        assertEquals(1, authA.requestFor(owner, "GET", "courses/" + source + "/notes", null).getJSONArray("data").length());
        deviceB.synchronize(owner);
        deviceA.notes().save(owner, source, id, "Pending after pull", "Must recover", "safe");
        String lost = note("Accepted but unacknowledged");
        var lostNote = a.syncDao().note(owner, lost);
        lostNote.attempt = new JSONObject().put("mutation_id", UUID.randomUUID().toString())
                .put("base_version", 0).put("deleted", false).put("title", lostNote.title)
                .put("content", lostNote.content).put("tags", new JSONArray(lostNote.tags)).toString();
        a.syncDao().putNote(lostNote);
        authA.requestFor(owner, "PUT", "sync/courses/" + source + "/notes/" + lost, new JSONObject(lostNote.attempt));
        deviceB.delete(owner, source);
        deviceB.synchronize(owner);
        // Skip A's class pull to exercise the server's parent_deleted race protection.
        deviceA.notes().synchronize(owner);
        assertTrue(a.syncDao().note(owner, id).recovery);
        assertEquals("Must recover", a.syncDao().note(owner, id).content);
        assertFalse(a.syncDao().find(owner, source).deleted);
        deviceA.synchronize(owner);
        assertTrue(a.syncDao().find(owner, source).deleted);
        assertEquals("Must recover", a.syncDao().note(owner, id).content);
        assertTrue(a.syncDao().note(owner, lost).recovery);
        assertEquals("Initial content", a.syncDao().note(owner, lost).content);
    }

    @Test public void maximumUnicodeContentRoundTripsAndOfflineRetryRetainsExactPayload() throws Exception {
        String id = note("Maximum content");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200000; i++) text.append("\uD83D\uDE00");
        deviceA.notes().save(owner, source, id, "Maximum content", text.toString(), "large");
        try (java.net.ServerSocket unavailable = new java.net.ServerSocket(0)) {
            int port = unavailable.getLocalPort();
            unavailable.close();
            AuthRepository offline = new AuthRepository(new BackendClient("http://127.0.0.1:" + port + "/api/v1/"),
                    new SessionStore(context, "note-a-" + suffix));
            try {
                new NoteSyncRepository(a, offline).synchronize(owner);
                fail("Offline synchronization must fail explicitly");
            } catch (BackendException expected) { assertEquals(0, expected.status()); }
        }
        String immutable = a.syncDao().note(owner, id).attempt;
        assertNotNull(immutable);
        assertEquals(text.toString(), new JSONObject(immutable).getString("content"));
        deviceA.synchronize(owner);
        deviceB.synchronize(owner);
        assertEquals(text.toString(), b.syncDao().note(owner, id).content);
        assertEquals(1, b.syncDao().note(owner, id).version);
        assertNull(a.syncDao().note(owner, id).attempt);
    }
}
