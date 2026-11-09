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
    private final MutableLiveData<Boolean> socialPending = new MutableLiveData<>(false);
    private final MutableLiveData<String> browserUrl = new MutableLiveData<>();
    private String preparedBrowserUrl;

    public AccountViewModel(Application application) {
        super(application);
        this.application = (HavenApplication) application;
        refresh();
    }

    public LiveData<State> state() { return state; }
    public LiveData<Boolean> socialPending() { return socialPending; }
    public LiveData<String> browserUrl() { return browserUrl; }
    public void browserOpened() { browserUrl.setValue(null); }

    public void refresh() {
        run(() -> {
            try {
                return new State(false, application.auth().restore(), "", false);
            } catch (BackendException exception) {
                return new State(false, null, exception.getMessage(), true);
            }
        });
    }

    public void signIn(boolean register, String name, String email, String password, String confirmation) {
        run(() -> {
            application.socialAuth().cancel();
            return new State(false, application.auth().signIn(register, name, email, password, confirmation), "", false);
        });
    }

    public void startSocial(String provider) {
        run(() -> {
            var launch = application.socialAuth().start(provider);
            preparedBrowserUrl = launch.url();
            return new State(false, null, "Continue in your browser. Return here when finished.", false);
        });
    }

    public void finishSocial(String attempt) {
        run(() -> {
            if (application.auth().restore() != null) {
                return new State(false, application.auth().restore(), "", false);
            }
            return new State(false, application.socialAuth().finish(attempt), "", false);
        });
    }

    public void cancelSocial(boolean browserUnavailable) {
        run(() -> {
            application.socialAuth().cancel();
            return new State(false, application.auth().restore(), browserUnavailable
                    ? "No browser is available. Install or enable a browser and try again."
                    : "Provider sign-in cancelled.", browserUnavailable);
        });
    }

    public void logout() {
        run(() -> {
            application.auth().logout();
            return new State(false, null, "Signed out.", false);
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
            boolean pending = false;
            try {
                pending = application.socialAuth().pendingProvider() != null;
            } catch (BackendException exception) {
                pending = exception.status() != 400;
                next = new State(false, next.account(), exception.getMessage(), true);
            }
            State result = next;
            boolean pendingResult = pending;
            String launchUrl = preparedBrowserUrl;
            preparedBrowserUrl = null;
            main.post(() -> {
                socialPending.setValue(pendingResult);
                state.setValue(result);
                if (launchUrl != null) browserUrl.setValue(launchUrl);
            });
        });
    }

    private interface Operation { State run() throws BackendException; }
}
