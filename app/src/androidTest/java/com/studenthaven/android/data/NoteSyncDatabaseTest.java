package com.studenthaven.android.data;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NoteSyncDatabaseTest {
    private ClassSyncRepository repository(ClassSyncDatabase db) {
        return new ClassSyncRepository(db, new AuthRepository(new BackendClient(""),
                new SessionStore(ApplicationProvider.getApplicationContext(), "unused-note-test")));
    }

    @Test public void versionOneMigrationPreservesClassesCursorsAndPendingAttempts() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String file = "note-migration-" + UUID.randomUUID();
        try {
            try (var old = context.openOrCreateDatabase(file, Context.MODE_PRIVATE, null)) {
                old.execSQL("CREATE TABLE synced_classes (accountId TEXT NOT NULL, id TEXT NOT NULL, "
                        + "name TEXT NOT NULL, code TEXT NOT NULL, professor TEXT NOT NULL, location TEXT NOT NULL, "
                        + "sourceLocalId TEXT, version INTEGER NOT NULL, deleted INTEGER NOT NULL, dirty INTEGER NOT NULL, "
                        + "attempt TEXT, conflict TEXT, PRIMARY KEY(accountId,id))");
                old.execSQL("CREATE UNIQUE INDEX index_synced_classes_accountId_sourceLocalId ON "
                        + "synced_classes (accountId,sourceLocalId)");
                old.execSQL("CREATE TABLE sync_cursors (accountId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)");
                old.execSQL("INSERT INTO synced_classes VALUES('A','id','Class','BIO','','','local',3,0,1,'immutable','both versions')");
                old.execSQL("INSERT INTO sync_cursors VALUES('A',17)");
                old.setVersion(1);
            }
            try (ClassSyncDatabase db = Room.databaseBuilder(context, ClassSyncDatabase.class, file)
                    .addMigrations(ClassSyncDatabase.MIGRATION_1_2).build()) {
                var item = db.syncDao().find("A", "id");
                assertEquals("Class", item.name);
                assertEquals(3, item.version);
                assertEquals("local", item.sourceLocalId);
                assertEquals("immutable", item.attempt);
                assertEquals("both versions", item.conflict);
                assertEquals(17, db.syncDao().cursor("A").revision);
                assertTrue(db.syncDao().notes("A").isEmpty());
                ClassSyncDatabase.NoteCursor cursor = new ClassSyncDatabase.NoteCursor();
                cursor.accountId = "A";
                cursor.revision = 8;
                db.syncDao().putNoteCursor(cursor);
                assertEquals(8, db.syncDao().noteCursor("A").revision);
            }
        } finally { context.deleteDatabase(file); }
    }

    @Test public void offlineNotesPersistWithConflictAndRecoveryCopyIsExplicitAndAccountScoped() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String file = "note-offline-" + UUID.randomUUID();
        String parent, target, note;
        try {
            try (ClassSyncDatabase db = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build()) {
                var classes = repository(db);
                classes.save("A", null, "Source", "", "", "");
                classes.save("A", null, "Target", "", "", "");
                parent = classes.list("A").stream().filter(c -> c.name.equals("Source")).findFirst().orElseThrow().id;
                target = classes.list("A").stream().filter(c -> c.name.equals("Target")).findFirst().orElseThrow().id;
                var notes = classes.notes();
                notes.save("A", parent, null, "Title", "Offline content", "biology\nexam");
                var item = notes.list("A").get(0);
                note = item.id;
                assertEquals("[\"biology\",\"exam\"]", item.tags);
                assertTrue(item.dirty);
                assertEquals(0, item.version);
                item.attempt = "{\"mutation_id\":\"immutable\"}";
                db.syncDao().putNote(item);
                try {
                    notes.save("A", parent, note, "Changed", "Cannot change dispatched attempt", "");
                    fail("Must retain dispatched payload");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("unacknowledged"));
                }
                assertTrue(notes.list("B").isEmpty());
            }
            try (ClassSyncDatabase db = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build()) {
                var notes = repository(db).notes();
                var item = db.syncDao().note("A", note);
                assertEquals("{\"mutation_id\":\"immutable\"}", item.attempt);
                item.attempt = null;
                item.conflict = new JSONObject().put("id", note).put("user_id", "A").put("course_id", parent)
                        .put("version", 4).put("deleted_at", JSONObject.NULL).put("title", "Server title")
                        .put("parent_deleted", false)
                        .put("content", "Server content").put("tags", new org.json.JSONArray("[\"remote\"]")).toString();
                db.syncDao().putNote(item);
                notes.resolve("A", note, true);
                item = db.syncDao().note("A", note);
                assertEquals("Offline content", item.content);
                assertEquals(4, item.version);
                assertTrue(item.dirty);
                item.recovery = true;
                item.conflict = "{\"server\":\"preserved\"}";
                db.syncDao().putNote(item);
                try {
                    notes.save("A", parent, note, "No automatic restoration", "", "");
                    fail("Recovery cannot upload to original parent");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("recovery"));
                }
                notes.copyRecovery("A", note, target);
                var source = db.syncDao().note("A", note);
                var copy = db.syncDao().note("A", source.recoveredCopyId);
                assertNotEquals(note, copy.id);
                assertEquals(target, copy.courseId);
                assertEquals("Offline content", copy.content);
                assertEquals("[\"biology\",\"exam\"]", copy.tags);
                assertEquals(0, copy.version);
                assertTrue(copy.dirty);
                assertFalse(copy.recovery);
                assertEquals("{\"server\":\"preserved\"}", source.conflict);
                try {
                    notes.copyRecovery("A", note, target);
                    fail("Repeated copy must not create duplicates");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("already copied"));
                }
                try {
                    notes.copyRecovery("B", note, target);
                    fail("Wrong account must not copy recovery");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("this account"));
                }
                assertEquals(2, notes.list("A").size());
                assertTrue(notes.list("B").isEmpty());
            }
        } finally { context.deleteDatabase(file); }
    }

    @Test public void noteValidationDoesNotPartiallyWrite() throws Exception {
        try (ClassSyncDatabase db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), ClassSyncDatabase.class).build()) {
            var classes = repository(db);
            classes.save("A", null, "Class", "", "", "");
            String parent = classes.list("A").get(0).id;
            String[] invalidTags = {"same\nsame", new String(new char[101]).replace('\0', 'x')};
            for (String tags : invalidTags) {
                try {
                    classes.notes().save("A", parent, null, "Title", "Content", tags);
                    fail("Invalid tags should fail before writing");
                } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Tags")); }
            }
            try {
                classes.notes().save("A", parent, null, "Title",
                        new String(new char[200001]).replace('\0', 'x'), "");
                fail("Oversized content must fail");
            } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("200,000")); }
            assertTrue(classes.notes().list("A").isEmpty());
        }
    }

    @Test public void acknowledgedDraftAwaitingFeedSurvivesRestartAndRecoversAfterParentDeletion() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String file = "note-ack-feed-" + UUID.randomUUID();
        String parent, id;
        try {
            try (ClassSyncDatabase db = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build()) {
                var classes = repository(db);
                classes.save("A", null, "Parent", "", "", "");
                parent = classes.list("A").get(0).id;
                classes.notes().save("A", parent, null, "Acknowledged", "Protect across restart", "draft");
                var note = classes.notes().list("A").get(0);
                id = note.id;
                note.version = 2;
                note.dirty = false;
                note.awaitingFeed = true;
                db.syncDao().putNote(note);
            }
            try (ClassSyncDatabase db = Room.databaseBuilder(context, ClassSyncDatabase.class, file).build()) {
                assertTrue(db.syncDao().note("A", id).awaitingFeed);
                var classes = repository(db);
                var deleted = db.syncDao().find("A", parent);
                deleted.deleted = true;
                db.syncDao().put(deleted);
                try {
                    classes.notes().synchronize("A");
                    fail("Missing sign-in must be reported");
                } catch (BackendException expected) { assertEquals(401, expected.status()); }
                var recovered = db.syncDao().note("A", id);
                assertTrue(recovered.recovery);
                assertEquals("Protect across restart", recovered.content);
                assertEquals("[\"draft\"]", recovered.tags);
                assertNull(recovered.attempt);
            }
        } finally { context.deleteDatabase(file); }
    }
}
