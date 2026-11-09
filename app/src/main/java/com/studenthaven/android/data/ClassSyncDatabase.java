package com.studenthaven.android.data;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import java.util.List;

@Database(entities = {ClassSyncDatabase.SyncedClass.class, ClassSyncDatabase.Cursor.class,
        ClassSyncDatabase.SyncedNote.class, ClassSyncDatabase.NoteCursor.class},
        version = 2, exportSchema = true)
public abstract class ClassSyncDatabase extends RoomDatabase implements AutoCloseable {
    public abstract SyncDao syncDao();

    public static final androidx.room.migration.Migration MIGRATION_1_2 =
            new androidx.room.migration.Migration(1, 2) {
                @Override public void migrate(@NonNull androidx.sqlite.db.SupportSQLiteDatabase db) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS synced_notes (accountId TEXT NOT NULL, "
                            + "id TEXT NOT NULL, courseId TEXT NOT NULL, title TEXT NOT NULL, "
                            + "content TEXT NOT NULL, tags TEXT NOT NULL, version INTEGER NOT NULL, "
                            + "deleted INTEGER NOT NULL, dirty INTEGER NOT NULL, recovery INTEGER NOT NULL, awaitingFeed INTEGER NOT NULL, "
                            + "attempt TEXT, conflict TEXT, recoveredCopyId TEXT, PRIMARY KEY(accountId, id))");
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_synced_notes_accountId_courseId "
                            + "ON synced_notes (accountId, courseId)");
                    db.execSQL("CREATE TABLE IF NOT EXISTS note_sync_cursors "
                            + "(accountId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL)");
                }
            };

    @Entity(tableName = "synced_notes", primaryKeys = {"accountId", "id"},
            indices = @Index(value = {"accountId", "courseId"}))
    public static class SyncedNote {
        @NonNull public String accountId = "";
        @NonNull public String id = "";
        @NonNull public String courseId = "";
        @NonNull public String title = "";
        @NonNull public String content = "";
        @NonNull public String tags = "[]";
        public long version;
        public boolean deleted;
        public boolean dirty;
        public boolean recovery;
        public boolean awaitingFeed;
        public String attempt;
        public String conflict;
        public String recoveredCopyId;
    }

    @Entity(tableName = "note_sync_cursors")
    public static class NoteCursor {
        @androidx.room.PrimaryKey @NonNull public String accountId = "";
        public long revision;
    }

    @Entity(tableName = "synced_classes", primaryKeys = {"accountId", "id"},
            indices = @Index(value = {"accountId", "sourceLocalId"}, unique = true))
    public static class SyncedClass {
        @NonNull public String accountId = "";
        @NonNull public String id = "";
        @NonNull public String name = "";
        @NonNull public String code = "";
        @NonNull public String professor = "";
        @NonNull public String location = "";
        public String sourceLocalId;
        public long version;
        public boolean deleted;
        public boolean dirty;
        public String attempt;
        public String conflict;
    }

    @Entity(tableName = "sync_cursors")
    public static class Cursor {
        @androidx.room.PrimaryKey @NonNull public String accountId = "";
        public long revision;
    }

    @Dao
    public interface SyncDao {
        @Query("SELECT * FROM synced_notes WHERE accountId = :account ORDER BY title COLLATE NOCASE, id")
        List<SyncedNote> notes(String account);
        @Query("SELECT * FROM synced_notes WHERE accountId = :account AND id = :id")
        SyncedNote note(String account, String id);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putNote(SyncedNote item);
        @Query("UPDATE synced_notes SET awaitingFeed = 0 WHERE accountId = :account")
        void acknowledgeNoteFeed(String account);
        @Query("SELECT * FROM note_sync_cursors WHERE accountId = :account")
        NoteCursor noteCursor(String account);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putNoteCursor(NoteCursor cursor);
        @Query("SELECT * FROM synced_classes WHERE accountId = :account ORDER BY name COLLATE NOCASE, id")
        List<SyncedClass> all(String account);
        @Query("SELECT * FROM synced_classes WHERE accountId = :account AND id = :id")
        SyncedClass find(String account, String id);
        @Query("SELECT * FROM synced_classes WHERE accountId = :account AND sourceLocalId = :localId")
        SyncedClass imported(String account, String localId);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void put(SyncedClass item);
        @Query("SELECT * FROM sync_cursors WHERE accountId = :account")
        Cursor cursor(String account);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void putCursor(Cursor cursor);
    }
}
