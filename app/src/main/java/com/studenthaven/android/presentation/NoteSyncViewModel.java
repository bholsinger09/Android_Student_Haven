package com.studenthaven.android.presentation;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.data.AuthRepository;
import com.studenthaven.android.data.BackendException;
import com.studenthaven.android.data.ClassSyncDatabase.SyncedClass;
import com.studenthaven.android.data.ClassSyncDatabase.SyncedNote;
import java.util.List;

public final class NoteSyncViewModel extends AndroidViewModel {
    public record State(boolean busy, AuthRepository.Account account, List<SyncedNote> notes,
                        List<SyncedClass> classes, String message, boolean error) {}
    private final MutableLiveData<State> state =
            new MutableLiveData<>(new State(false, null, List.of(), List.of(), "", false));
    private final HavenApplication application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String accountId;
    public String editingId;
    public String draftTitle = "";
    public String draftContent = "";
    public String draftTags = "";
    public boolean editorOpen;

    public NoteSyncViewModel(Application app) {
        super(app);
        application = (HavenApplication) app;
        reload();
    }

    public LiveData<State> state() { return state; }
    public void reload() { run(account -> "Notes are saved offline. Sync now to exchange changes."); }
    public void sync() {
        run(account -> {
            application.classSync().synchronize(account);
            return "Synchronization complete. Check conflicts and recovery drafts.";
        });
    }
    public void save(String course) {
        String id = editingId, title = draftTitle, content = draftContent, tags = draftTags;
        run(account -> {
            application.classSync().notes().save(account, course, id, title, content, tags);
            editorOpen = false;
            editingId = null;
            draftTitle = draftContent = draftTags = "";
            return "Note saved offline. Tap Sync now to upload.";
        });
    }
    public void delete(String id) {
        run(account -> {
            application.classSync().notes().delete(account, id);
            return "Note deletion queued offline.";
        });
    }
    public void resolve(String id, boolean local) {
        run(account -> {
            application.classSync().notes().resolve(account, id, local);
            return local ? "Local version chosen. Sync again to upload." : "Server version chosen.";
        });
    }
    public void copy(String id, String target) {
        run(account -> {
            application.classSync().notes().copyRecovery(account, id, target);
            return "Copied offline into the selected class. Original recovery draft preserved. Sync to upload.";
        });
    }

    private void run(Action action) {
        State previous = state.getValue();
        if (previous.busy()) return;
        state.setValue(new State(true, previous.account(), previous.notes(), previous.classes(), "", false));
        application.authIo().execute(() -> {
            AuthRepository.Account account = null;
            String message;
            boolean error = false;
            List<SyncedNote> notes = List.of();
            List<SyncedClass> classes = List.of();
            try {
                account = application.auth().restore();
                if (account == null) throw new BackendException("Sign in to open synced notes.", 401);
                if (accountId != null && !accountId.equals(account.id())) {
                    account = null;
                    throw new BackendException("Account changed. Close this workspace and reopen it.", 401);
                }
                accountId = account.id();
                message = action.run(accountId);
            } catch (BackendException exception) {
                message = exception.getMessage();
                error = true;
                if (exception.status() == 401) account = null;
            } catch (IllegalArgumentException | IllegalStateException | android.database.sqlite.SQLiteException exception) {
                Log.e("NoteSync", "Note workspace operation failed", exception);
                message = exception.getMessage();
                error = true;
            }
            if (account != null) {
                try {
                    notes = application.classSync().notes().list(account.id());
                    classes = application.classSync().list(account.id());
                } catch (IllegalStateException | android.database.sqlite.SQLiteException exception) {
                    Log.e("NoteSync", "Cannot read offline notes", exception);
                    message = "Cannot read offline notes. Close and retry.";
                    error = true;
                }
            }
            State next = new State(false, account, notes, classes, message, error);
            main.post(() -> state.setValue(next));
        });
    }
    private interface Action { String run(String account) throws BackendException; }
}
