package com.studenthaven.android.data;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ClassSyncDatabaseTest {
    private ClassSyncRepository repository(ClassSyncDatabase database) {
        return new ClassSyncRepository(database, new AuthRepository(new BackendClient(""),
                new SessionStore(ApplicationProvider.getApplicationContext(), "unused-sync-test")));
    }

    @Test public void copiesOnlyAfterRequestOncePerAccountAndKeepsLocalOriginalUnchanged() throws Exception {
        try (ClassSyncDatabase database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), ClassSyncDatabase.class).build()) {
            ClassSyncRepository sync = repository(database);
            assertTrue(sync.list("A").isEmpty());
            StudyDatabase.Course source = new StudyDatabase.Course();
            source.name = "Biology";
            source.code = "BIO101";
            String originalId = source.id;
            assertEquals(1, sync.importClasses("A", List.of(source)));
            assertEquals(0, sync.importClasses("A", List.of(source)));
            var imported = sync.list("A").get(0);
            assertNotEquals(originalId, imported.id);
            assertEquals(originalId, imported.sourceLocalId);
            assertTrue(imported.dirty);
            sync.save("A", imported.id, "Changed copy", "NEW", "", "");
            sync.delete("A", imported.id);
            assertEquals(0, sync.importClasses("A", List.of(source)));
            assertEquals("Biology", source.name);
            assertEquals(originalId, source.id);
            assertTrue(sync.list("B").isEmpty());
            assertEquals(1, sync.importClasses("B", List.of(source)));
            assertNotEquals(imported.id, sync.list("B").get(0).id);
            try {
                sync.delete("B", imported.id);
                fail("A different account must not edit the imported class");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("this account"));
            }
        }
    }

    @Test public void attemptsAndBothConflictVersionsSurviveReopeningAndResolveExplicitly() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String file = "sync-test-" + UUID.randomUUID() + ".db";
        String id;
        try {
            try (ClassSyncDatabase database = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build()) {
                ClassSyncRepository sync = repository(database);
                sync.save("A", null, "Local draft", "BIO", "", "");
                var item = sync.list("A").get(0);
                id = item.id;
                item.attempt = "{\"mutation_id\":\"immutable-attempt\"}";
                database.syncDao().put(item);
                try {
                    sync.save("A", id, "Cannot replace unacknowledged request", "", "", "");
                    fail("Must not mutate a dispatched request");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("acknowledged"));
                }
            }
            try (ClassSyncDatabase database = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build()) {
                ClassSyncRepository sync = repository(database);
                var item = sync.list("A").get(0);
                assertEquals("{\"mutation_id\":\"immutable-attempt\"}", item.attempt);
                item.attempt = null;
                item.conflict = new JSONObject().put("id", id).put("user_id", "A")
                        .put("version", 4).put("name", "Server draft").put("code", "")
                        .put("professor", "").put("location", "").put("deleted_at", JSONObject.NULL).toString();
                database.syncDao().put(item);
                try {
                    sync.delete("A", id);
                    fail("Cannot delete during conflict");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("Resolve"));
                }
                sync.resolve("A", id, true);
                item = sync.list("A").get(0);
                assertEquals("Local draft", item.name);
                assertEquals(4, item.version);
                assertTrue(item.dirty);
                assertNull(item.conflict);
                item.conflict = new JSONObject().put("id", id).put("user_id", "A")
                        .put("version", 5).put("name", "Server draft").put("code", "")
                        .put("professor", "").put("location", "").put("deleted_at", "2026-11-09").toString();
                database.syncDao().put(item);
                sync.resolve("A", id, false);
                item = sync.list("A").get(0);
                assertTrue(item.deleted);
                assertFalse(item.dirty);
                assertEquals(5, item.version);
            }
        } finally {
            context.deleteDatabase(file);
        }
    }

    @Test public void invalidImportsAndBlankNamesLeaveDatabaseUntouched() throws Exception {
        try (ClassSyncDatabase database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), ClassSyncDatabase.class).build()) {
            ClassSyncRepository sync = repository(database);
            try {
                sync.save("A", null, "", "", "", "");
                fail("Expected validation");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("empty"));
            }
            StudyDatabase.Course good = new StudyDatabase.Course();
            good.name = "Good";
            StudyDatabase.Course bad = new StudyDatabase.Course();
            bad.name = new String(new char[256]).replace('\0', 'x');
            try {
                sync.importClasses("A", List.of(good, bad));
                fail("Expected import validation");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("limits"));
            }
            assertTrue(sync.list("A").isEmpty());
        }
    }
}
