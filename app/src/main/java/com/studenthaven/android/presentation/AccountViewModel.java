package com.studenthaven.android.presentation;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.data.AuthRepository;
import com.studenthaven.android.data.BackendException;

public final class AccountViewModel extends AndroidViewModel {
    public record State(boolean busy, AuthRepository.Account account, String message, boolean error) {}
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(false, null, "", false));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HavenApplication application;

    public AccountViewModel(Application application) {
        super(application);
        this.application = (HavenApplication) application;
        run(() -> new State(false, this.application.auth().restore(), "", false));
    }

    public LiveData<State> state() { return state; }

    public void signIn(boolean register, String name, String email, String password, String confirmation) {
        run(() -> new State(false, application.auth().signIn(register, name, email, password, confirmation),
                "Signed in. Study data remains local and is not synchronized.", false));
    }

    public void check() {
        run(() -> {
            var account = application.auth().check();
            return new State(false, account, account == null
                    ? "Connected to Laravel. Authentication is required as expected."
                    : "Connected to Laravel. Your account was verified.", false);
        });
    }

    public void logout() {
        run(() -> {
            application.auth().logout();
            return new State(false, null, "Signed out. Your local study data is unchanged.", false);
        });
    }

    public void forget() {
        run(() -> {
            application.auth().forget();
            return new State(false, null,
                    "Local sign-in removed. The server token was not revoked; it will expire automatically.", false);
        });
    }

    private void run(Operation operation) {
        State previous = state.getValue();
        if (previous.busy()) return;
        state.setValue(new State(true, previous.account(), "", false));
        application.authIo().execute(() -> {
            State next;
            try {
                next = operation.run();
            } catch (BackendException exception) {
                next = new State(false, exception.status() == 401 ? null : previous.account(),
                        exception.getMessage(), true);
            }
            State result = next;
            main.post(() -> state.setValue(result));
        });
    }

    private interface Operation { State run() throws BackendException; }
}
