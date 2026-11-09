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

@Database(entities = {ClassSyncDatabase.SyncedClass.class, ClassSyncDatabase.Cursor.class},
        version = 1, exportSchema = true)
public abstract class ClassSyncDatabase extends RoomDatabase implements AutoCloseable {
    public abstract SyncDao syncDao();

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
