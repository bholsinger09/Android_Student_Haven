package com.studenthaven.android;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.room.Room;
import com.studenthaven.android.data.RoomStudyRepository;
import com.studenthaven.android.data.StudyDatabase;
import com.studenthaven.android.data.StudyRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HavenApplication extends Application {
    private StudyRepository repository;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        StudyDatabase database = Room.databaseBuilder(this, StudyDatabase.class, "student-haven.db")
                .build();
        repository = new RoomStudyRepository(database);
    }

    public StudyRepository repository() { return repository; }
    public ExecutorService io() { return io; }
}
