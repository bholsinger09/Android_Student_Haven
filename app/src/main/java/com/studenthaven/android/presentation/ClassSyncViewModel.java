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
import java.util.List;

public final class ClassSyncViewModel extends AndroidViewModel {
    public record State(boolean busy, AuthRepository.Account account, List<SyncedClass> classes,
                        String message, boolean error) {}
    private final MutableLiveData<State> state =
            new MutableLiveData<>(new State(false, null, List.of(), "", false));
    private final HavenApplication application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String accountId;

    public ClassSyncViewModel(Application application) {
        super(application);
        this.application = (HavenApplication) application;
        run(account -> "Offline workspace ready. Tap Sync now to exchange changes.");
    }

    public LiveData<State> state() { return state; }

    public void sync() {
        run(account -> {
            application.classSync().synchronize(account);
            return "Synchronization complete. Resolve any conflicts below, then sync again.";
        });
    }

    public void save(String id, String name, String code, String professor, String location) {
        run(account -> {
            application.classSync().save(account, id, name, code, professor, location);
            return "Saved offline. Tap Sync now to upload.";
        });
    }

    public void delete(String id) {
        run(account -> {
            application.classSync().delete(account, id);
            return "Deletion queued offline. Tap Sync now to upload.";
        });
    }

    public void importLocal() {
        run(account -> {
            int count = application.classSync().importClasses(account,
                    application.repository().localCoursesForImport());
            return count + " device-only classes copied. Originals are unchanged. Tap Sync now to upload.";
        });
    }

    public void resolve(String id, boolean keepLocal) {
        run(account -> {
            application.classSync().resolve(account, id, keepLocal);
            return keepLocal ? "Local version chosen. Tap Sync now; newer server changes can cause another conflict."
                    : "Server version chosen. The pending local change was discarded.";
        });
    }

    private void run(Action action) {
        State previous = state.getValue();
        if (previous.busy()) return;
        state.setValue(new State(true, previous.account(), previous.classes(), "", false));
        application.authIo().execute(() -> {
            AuthRepository.Account account = null;
            String message;
            boolean error = false;
            try {
                account = application.auth().restore();
                if (account == null) throw new BackendException("Sign in before opening Synced Classes.", 401);
                if (accountId != null && !accountId.equals(account.id())) {
                    account = null;
                    throw new BackendException("The account changed. Close this workspace and reopen it.", 401);
                }
                accountId = account.id();
                message = action.run(accountId);
            } catch (BackendException exception) {
                message = exception.getMessage();
                error = true;
                if (exception.status() == 401) account = null;
            } catch (IllegalArgumentException | IllegalStateException | android.database.sqlite.SQLiteException exception) {
                Log.e("ClassSync", "Class workspace operation failed", exception);
                message = exception.getMessage();
                error = true;
            }
            List<SyncedClass> classes = List.of();
            if (account != null) {
                try {
                    classes = application.classSync().list(account.id());
                } catch (IllegalStateException | android.database.sqlite.SQLiteException exception) {
                    Log.e("ClassSync", "Cannot read offline classes", exception);
                    message = "Cannot read this account's offline classes. Close and retry.";
                    error = true;
                }
            }
            State next = new State(false, account, classes, message, error);
            main.post(() -> state.setValue(next));
        });
    }

    private interface Action { String run(String account) throws BackendException; }
}
