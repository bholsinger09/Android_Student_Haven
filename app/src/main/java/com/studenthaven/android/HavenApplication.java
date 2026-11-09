package com.studenthaven.android;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.room.Room;
import com.studenthaven.android.data.RoomStudyRepository;
import com.studenthaven.android.data.StudyDatabase;
import com.studenthaven.android.data.StudyRepository;
import com.studenthaven.android.data.AuthRepository;
import com.studenthaven.android.data.BackendClient;
import com.studenthaven.android.data.SessionStore;
import com.studenthaven.android.data.ClassSyncDatabase;
import com.studenthaven.android.data.ClassSyncRepository;
import com.studenthaven.android.data.SocialAuthRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HavenApplication extends Application {
    private StudyRepository repository;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService authIo = Executors.newSingleThreadExecutor();
    private AuthRepository auth;
    private ClassSyncRepository classSync;
    private SocialAuthRepository socialAuth;

    @Override public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        StudyDatabase database = Room.databaseBuilder(this, StudyDatabase.class, "student-haven.db")
                .build();
        repository = new RoomStudyRepository(database);
        auth = new AuthRepository(new BackendClient(BuildConfig.BACKEND_URL), new SessionStore(this));
        socialAuth = new SocialAuthRepository(this, new BackendClient(BuildConfig.BACKEND_URL), auth);
        ClassSyncDatabase syncDatabase = Room.databaseBuilder(this, ClassSyncDatabase.class,
                "student-haven-class-sync.db").addMigrations(ClassSyncDatabase.MIGRATION_1_2).build();
        classSync = new ClassSyncRepository(syncDatabase, auth);
    }

    public StudyRepository repository() { return repository; }
    public ExecutorService io() { return io; }
    public AuthRepository auth() { return auth; }
    public ExecutorService authIo() { return authIo; }
    public ClassSyncRepository classSync() { return classSync; }
    public SocialAuthRepository socialAuth() { return socialAuth; }
}
