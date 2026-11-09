package com.studenthaven.android.data;

import com.studenthaven.android.data.ClassSyncDatabase.SyncedNote;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class NoteSyncRepository {
    private final ClassSyncDatabase database;
    private final ClassSyncDatabase.SyncDao dao;
    private final AuthRepository auth;

    public NoteSyncRepository(ClassSyncDatabase database, AuthRepository auth) {
        this.database = database;
        dao = database.syncDao();
        this.auth = auth;
    }

    public List<SyncedNote> list(String account) { return dao.notes(account); }

    public void save(String account, String courseId, String id, String title, String content, String tags) {
        JSONArray parsed = parseTags(tags);
        if (title.trim().isEmpty() || title.trim().codePointCount(0, title.trim().length()) > 255) {
            throw new IllegalArgumentException("Note title must contain 1 to 255 characters.");
        }
        if (content.codePointCount(0, content.length()) > 200000) {
            throw new IllegalArgumentException("Note content must be at most 200,000 characters.");
        }
        database.runInTransaction(() -> {
            activeClass(account, courseId);
            SyncedNote item = id == null ? new SyncedNote() : require(account, id);
            if (id != null && !courseId.equals(item.courseId)) {
                throw new IllegalArgumentException("This note belongs to a different class.");
            }
            editable(item);
            item.accountId = account;
            item.courseId = courseId;
            if (id == null) item.id = UUID.randomUUID().toString();
            item.title = title.trim();
            item.content = content;
            item.tags = parsed.toString();
            item.dirty = true;
            dao.putNote(item);
        });
    }

    public void delete(String account, String id) {
        database.runInTransaction(() -> {
            SyncedNote item = require(account, id);
            activeClass(account, item.courseId);
            editable(item);
            item.deleted = true;
            item.dirty = true;
            dao.putNote(item);
        });
    }

    public void copyRecovery(String account, String id, String target) {
        database.runInTransaction(() -> {
            SyncedNote source = require(account, id);
            if (!source.recovery) throw new IllegalArgumentException("Only a recovery draft can be copied.");
            if (source.recoveredCopyId != null) {
                throw new IllegalArgumentException("This draft was already copied. The original recovery draft is preserved.");
            }
            activeClass(account, target);
            SyncedNote copy = new SyncedNote();
            copy.accountId = account;
            copy.id = UUID.randomUUID().toString();
            copy.courseId = target;
            copy.title = source.title;
            copy.content = source.content;
            copy.tags = source.tags;
            copy.dirty = true;
            dao.putNote(copy);
            source.recoveredCopyId = copy.id;
            dao.putNote(source);
        });
    }

    public void resolve(String account, String id, boolean keepLocal) throws BackendException {
        SyncedNote item = require(account, id);
        activeClass(account, item.courseId);
        if (item.recovery || item.conflict == null) {
            throw new IllegalArgumentException("This note has no editable conflict.");
        }
        try {
            JSONObject remote = new JSONObject(item.conflict);
            validate(remote, account, item.id, item.courseId);
            if (keepLocal) {
                item.version = remote.getLong("version");
                item.conflict = null;
                item.attempt = null;
                item.dirty = true;
            } else apply(item, remote);
            dao.putNote(item);
        } catch (JSONException exception) {
            throw new BackendException("Cannot read the saved note conflict. Retry synchronization.", exception);
        }
    }

    public void synchronize(String account) throws BackendException {
        try {
            preserveOrphans(account);
            for (SyncedNote item : dao.notes(account)) {
                if (!item.dirty || item.recovery || item.conflict != null) continue;
                var parent = dao.find(account, item.courseId);
                if (parent == null || parent.deleted || parent.version == 0 || parent.dirty || parent.conflict != null) {
                    continue;
                }
                if (item.deleted && item.version == 0 && item.attempt == null) {
                    item.dirty = false;
                    dao.putNote(item);
                    continue;
                }
                if (item.attempt == null) {
                    item.attempt = new JSONObject().put("mutation_id", UUID.randomUUID().toString())
                            .put("base_version", item.version).put("deleted", item.deleted)
                            .put("title", item.title).put("content", item.content)
                            .put("tags", new JSONArray(item.tags)).toString();
                    dao.putNote(item);
                }
                try {
                    JSONObject response = auth.requestFor(account, "PUT",
                            "sync/courses/" + item.courseId + "/notes/" + item.id, new JSONObject(item.attempt));
                    apply(item, response.getJSONObject("data"));
                    item.awaitingFeed = true;
                    dao.putNote(item);
                } catch (BackendException exception) {
                    if (exception.status() == 422) {
                        item.attempt = null;
                        dao.putNote(item);
                        throw exception;
                    }
                    JSONObject response = exception.response();
                    if (exception.status() != 409 || response == null) throw exception;
                    if ("parent_deleted".equals(response.optString("code"))) {
                        JSONObject deletedParent = response.getJSONObject("parent");
                        if (!account.equals(deletedParent.getString("user_id"))
                                || !item.courseId.equals(deletedParent.getString("id"))
                                || deletedParent.isNull("deleted_at")) throw new JSONException("Invalid deleted parent");
                        recover(item);
                        dao.putNote(item);
                    } else {
                        JSONObject remote = response.optJSONObject("conflict");
                        if (remote == null) throw exception;
                        validate(remote, account, item.id, item.courseId);
                        item.conflict = remote.toString();
                        item.attempt = null;
                        dao.putNote(item);
                    }
                }
            }
            boolean more;
            do {
                var cursor = dao.noteCursor(account);
                long after = cursor == null ? 0 : cursor.revision;
                JSONObject page = auth.requestFor(account, "GET", "sync/notes?after=" + after, null);
                JSONArray rows = page.getJSONArray("data");
                long next = page.getLong("cursor");
                more = page.getBoolean("has_more");
                if (rows.length() > 1 || next < after || (more && next <= after)) {
                    throw new JSONException("Invalid note cursor");
                }
                long revision = after;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject remote = rows.getJSONObject(i);
                    validate(remote, account, remote.getString("id"), remote.getString("course_id"));
                    if (remote.getLong("sync_revision") <= revision) throw new JSONException("Unordered note page");
                    revision = remote.getLong("sync_revision");
                }
                if (next != revision) throw new JSONException("Invalid note page cursor");
                database.runInTransaction(() -> {
                    for (int i = 0; i < rows.length(); i++) {
                        try {
                            JSONObject remote = rows.getJSONObject(i);
                            SyncedNote item = dao.note(account, remote.getString("id"));
                            if (item == null) {
                                item = new SyncedNote();
                                item.accountId = account;
                                item.id = remote.getString("id");
                                item.courseId = remote.getString("course_id");
                            }
                            validate(remote, account, item.id, item.courseId);
                            if (remote.getBoolean("parent_deleted") && !item.recovery
                                    && (item.dirty || item.awaitingFeed)) {
                                recover(item);
                                dao.putNote(item);
                            }
                            if (item.recovery && item.conflict == null) {
                                item.conflict = remote.toString();
                                dao.putNote(item);
                            } else if (!item.recovery && remote.getLong("version") > item.version) {
                                if (item.dirty) item.conflict = remote.toString();
                                else apply(item, remote);
                                dao.putNote(item);
                            }
                        } catch (JSONException exception) {
                            throw new IllegalStateException("Invalid note page", exception);
                        }
                    }
                    ClassSyncDatabase.NoteCursor saved = new ClassSyncDatabase.NoteCursor();
                    saved.accountId = account;
                    saved.revision = next;
                    dao.putNoteCursor(saved);
                });
            } while (more);
            preserveOrphans(account);
            dao.acknowledgeNoteFeed(account);
        } catch (JSONException exception) {
            throw new BackendException("The backend returned invalid note synchronization data.", exception);
        }
    }

    private void preserveOrphans(String account) throws JSONException {
        HashSet<String> deletedParents = new HashSet<>();
        for (var parent : dao.all(account)) {
            if (parent.deleted || (parent.conflict != null
                    && !new JSONObject(parent.conflict).isNull("deleted_at"))) deletedParents.add(parent.id);
        }
        database.runInTransaction(() -> {
            for (SyncedNote item : dao.notes(account)) {
                if (!item.recovery && (item.dirty || item.awaitingFeed) && deletedParents.contains(item.courseId)) {
                    recover(item);
                    dao.putNote(item);
                }
            }
        });
    }

    private void recover(SyncedNote item) {
        item.recovery = true;
        item.attempt = null;
        // Keep local fields and any saved server conflict; never upload this row again.
    }

    private void apply(SyncedNote item, JSONObject remote) throws JSONException {
        validate(remote, item.accountId, item.id, item.courseId);
        item.title = remote.getString("title");
        item.content = remote.getString("content");
        item.tags = remote.getJSONArray("tags").toString();
        item.version = remote.getLong("version");
        item.deleted = !remote.isNull("deleted_at");
        item.dirty = false;
        item.awaitingFeed = false;
        item.attempt = null;
        item.conflict = null;
    }

    private void validate(JSONObject remote, String account, String id, String course) throws JSONException {
        if (!account.equals(remote.getString("user_id")) || !id.equals(remote.getString("id"))
                || !course.equals(remote.getString("course_id")) || remote.getLong("version") < 1
                || !remote.has("deleted_at") || remote.isNull("title") || remote.isNull("content")) {
            throw new JSONException("Invalid note owner, parent, or metadata");
        }
        remote.getString("title");
        remote.getString("content");
        remote.getBoolean("parent_deleted");
        JSONArray tags = remote.getJSONArray("tags");
        for (int i = 0; i < tags.length(); i++) tags.getString(i);
    }

    public static JSONArray parseTags(String text) {
        List<String> tags = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (String line : text.split("\\r?\\n")) {
            String tag = line.trim();
            if (tag.isEmpty()) continue;
            if (tag.codePointCount(0, tag.length()) > 100 || !seen.add(tag)) {
                throw new IllegalArgumentException("Tags must be unique and at most 100 characters each.");
            }
            tags.add(tag);
        }
        if (tags.size() > 50) throw new IllegalArgumentException("Use at most 50 tags, one per line.");
        return new JSONArray(tags);
    }

    public static String tagText(String json) throws JSONException {
        JSONArray tags = new JSONArray(json);
        List<String> values = new ArrayList<>();
        for (int i = 0; i < tags.length(); i++) values.add(tags.getString(i));
        return String.join("\n", values);
    }

    private SyncedNote require(String account, String id) {
        SyncedNote item = dao.note(account, id);
        if (item == null) throw new IllegalArgumentException("This note is not in this account.");
        return item;
    }

    private void activeClass(String account, String id) {
        var parent = dao.find(account, id);
        if (parent == null || parent.deleted || parent.conflict != null) {
            throw new IllegalArgumentException("Choose an active class without a conflict.");
        }
    }

    private void editable(SyncedNote item) {
        if (item.recovery) throw new IllegalArgumentException("Copy this recovery draft into another active class.");
        if (item.attempt != null) throw new IllegalArgumentException("Sync to retry the unacknowledged note before editing.");
        if (item.conflict != null) throw new IllegalArgumentException("Resolve this note conflict before editing.");
        if (item.deleted) throw new IllegalArgumentException("This note is marked for deletion.");
    }
}
