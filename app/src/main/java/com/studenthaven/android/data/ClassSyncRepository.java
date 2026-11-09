package com.studenthaven.android.data;

import com.studenthaven.android.data.ClassSyncDatabase.SyncedClass;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class ClassSyncRepository {
    private final ClassSyncDatabase database;
    private final ClassSyncDatabase.SyncDao dao;
    private final AuthRepository auth;
    private final NoteSyncRepository notes;

    public ClassSyncRepository(ClassSyncDatabase database, AuthRepository auth) {
        this.database = database;
        this.dao = database.syncDao();
        this.auth = auth;
        notes = new NoteSyncRepository(database, auth);
    }

    public NoteSyncRepository notes() { return notes; }
    public List<SyncedClass> list(String account) { return dao.all(account); }

    public void save(String account, String id, String name, String code, String professor, String location) {
        if (name.trim().isEmpty()) throw new IllegalArgumentException("Class name cannot be empty.");
        if (name.trim().length() > 255 || code.trim().length() > 100
                || professor.trim().length() > 255 || location.trim().length() > 255) {
            throw new IllegalArgumentException("Use at most 255 characters per field (100 for class code).");
        }
        database.runInTransaction(() -> {
            SyncedClass item = id == null ? new SyncedClass() : require(account, id);
            editable(item);
            item.accountId = account;
            if (id == null) item.id = UUID.randomUUID().toString();
            item.name = name.trim();
            item.code = code.trim();
            item.professor = professor.trim();
            item.location = location.trim();
            item.dirty = true;
            dao.put(item);
        });
    }

    public void delete(String account, String id) {
        database.runInTransaction(() -> {
            SyncedClass item = require(account, id);
            editable(item);
            item.deleted = true;
            item.dirty = true;
            dao.put(item);
        });
    }

    public int importClasses(String account, List<StudyDatabase.Course> courses) {
        // Validate the entire copy first so a failed import never partially succeeds.
        for (StudyDatabase.Course item : courses) {
            if (item.name.trim().isEmpty() || item.name.length() > 255 || item.code.length() > 100
                    || item.professor.length() > 255 || item.location.length() > 255) {
                throw new IllegalArgumentException("A device-only class exceeds backend field limits. Edit it before importing.");
            }
        }
        int[] count = {0};
        database.runInTransaction(() -> {
            for (StudyDatabase.Course original : courses) {
                if (dao.imported(account, original.id) != null) continue;
                SyncedClass copy = new SyncedClass();
                copy.accountId = account;
                copy.id = UUID.randomUUID().toString();
                copy.sourceLocalId = original.id;
                copy.name = original.name;
                copy.code = original.code;
                copy.professor = original.professor;
                copy.location = original.location;
                copy.dirty = true;
                dao.put(copy);
                count[0]++;
            }
        });
        return count[0];
    }

    public void resolve(String account, String id, boolean keepLocal) throws BackendException {
        SyncedClass item = require(account, id);
        if (item.conflict == null) throw new IllegalArgumentException("This class has no conflict to resolve.");
        try {
            JSONObject remote = new JSONObject(item.conflict);
            validateRemote(remote, account, id);
            long version = remote.getLong("version");
            if (keepLocal) {
                item.version = version;
                item.conflict = null;
                item.attempt = null;
                item.dirty = true;
            } else {
                apply(item, remote);
            }
            dao.put(item);
        } catch (JSONException exception) {
            throw new BackendException("Cannot read this saved conflict. Retry synchronization.", exception);
        }
    }

    public void synchronize(String account) throws BackendException {
        try {
            // A dispatched attempt is immutable until acknowledged. Persist it before
            // sending so a lost response/process restart reuses the same mutation ID.
            for (SyncedClass item : dao.all(account)) {
                if (!item.dirty || item.conflict != null) continue;
                if (item.deleted && item.version == 0 && item.attempt == null) {
                    item.dirty = false;
                    dao.put(item);
                    continue;
                }
                if (item.attempt == null) {
                    item.attempt = new JSONObject().put("mutation_id", UUID.randomUUID().toString())
                            .put("base_version", item.version).put("deleted", item.deleted)
                            .put("name", item.name).put("code", item.code)
                            .put("professor", item.professor).put("location", item.location).toString();
                    dao.put(item);
                }
                try {
                    JSONObject response = auth.requestFor(account, "PUT", "sync/courses/" + item.id,
                            new JSONObject(item.attempt));
                    apply(item, response.getJSONObject("data"));
                    dao.put(item);
                } catch (BackendException exception) {
                    JSONObject remote = exception.response() == null ? null
                            : exception.response().optJSONObject("conflict");
                    if (exception.status() != 409 || remote == null) throw exception;
                    validateRemote(remote, account, item.id);
                    item.conflict = remote.toString();
                    item.attempt = null;
                    dao.put(item);
                }
            }
            boolean more;
            do {
                ClassSyncDatabase.Cursor cursor = dao.cursor(account);
                long after = cursor == null ? 0 : cursor.revision;
                JSONObject page = auth.requestFor(account, "GET", "sync/courses?after=" + after, null);
                JSONArray rows = page.getJSONArray("data");
                long next = page.getLong("cursor");
                more = page.getBoolean("has_more");
                if (rows.length() > 100 || next < after || (more && next <= after)) {
                    throw new JSONException("Invalid sync cursor");
                }
                // Parse/validate the entire page before advancing the cursor atomically.
                long revision = after;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject remote = rows.getJSONObject(i);
                    validateRemote(remote, account, remote.getString("id"));
                    long current = remote.getLong("sync_revision");
                    if (current <= revision) throw new JSONException("Unordered sync page");
                    revision = current;
                }
                if (next != revision) throw new JSONException("Cursor does not match the page");
                database.runInTransaction(() -> {
                    for (int i = 0; i < rows.length(); i++) {
                        try {
                            JSONObject remote = rows.getJSONObject(i);
                            SyncedClass item = dao.find(account, remote.getString("id"));
                            if (item == null) {
                                item = new SyncedClass();
                                item.accountId = account;
                                item.id = remote.getString("id");
                            }
                            if (remote.getLong("version") > item.version) {
                                if (item.dirty) item.conflict = remote.toString();
                                else apply(item, remote);
                                dao.put(item);
                            }
                        } catch (JSONException exception) {
                            throw new IllegalStateException("Invalid sync page", exception);
                        }
                    }
                    ClassSyncDatabase.Cursor saved = new ClassSyncDatabase.Cursor();
                    saved.accountId = account;
                    saved.revision = next;
                    dao.putCursor(saved);
                });
            } while (more);
            notes.synchronize(account);
        } catch (JSONException exception) {
            throw new BackendException("The backend returned invalid class synchronization data.", exception);
        }
    }

    private void apply(SyncedClass item, JSONObject remote) throws JSONException {
        validateRemote(remote, item.accountId, item.id);
        item.name = remote.getString("name");
        item.code = remote.getString("code");
        item.professor = remote.getString("professor");
        item.location = remote.getString("location");
        item.version = remote.getLong("version");
        item.deleted = !remote.isNull("deleted_at");
        item.dirty = false;
        item.attempt = null;
        item.conflict = null;
    }

    private void validateRemote(JSONObject remote, String account, String id) throws JSONException {
        if (!account.equals(remote.getString("user_id")) || !id.equals(remote.getString("id"))
                || remote.getLong("version") < 1 || !remote.has("deleted_at")) {
            throw new JSONException("Wrong class owner or version");
        }
        for (String field : List.of("name", "code", "professor", "location")) {
            if (remote.isNull(field)) throw new JSONException("Missing class metadata");
            remote.getString(field);
        }
    }

    private SyncedClass require(String account, String id) {
        SyncedClass item = dao.find(account, id);
        if (item == null) throw new IllegalArgumentException("This class no longer exists in this account.");
        return item;
    }

    private void editable(SyncedClass item) {
        if (item.attempt != null) throw new IllegalArgumentException(
                "The last upload has not been acknowledged. Sync now to retry it before editing.");
        if (item.conflict != null) throw new IllegalArgumentException("Resolve this class conflict before editing.");
        if (item.deleted) throw new IllegalArgumentException("This class is marked for deletion.");
    }
}
