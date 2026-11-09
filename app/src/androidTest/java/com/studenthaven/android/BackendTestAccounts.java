package com.studenthaven.android;

import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayDeque;

public final class BackendTestAccounts {
    private static final long WINDOW_MS = 61000;
    private static final ArrayDeque<Long> registrations = new ArrayDeque<>();
    private static boolean freshWindow;

    private BackendTestAccounts() {}

    public static synchronized void awaitRegistrationSlot() throws InterruptedException {
        // The local API shares an IP quota with previous runs, not just this process.
        if (!freshWindow) {
            Log.i("BackendTests", "Waiting for any previous registration quota to expire");
            Thread.sleep(WINDOW_MS);
            freshWindow = true;
        }
        long now = SystemClock.elapsedRealtime();
        while (!registrations.isEmpty() && now - registrations.peekFirst() >= WINDOW_MS) {
            registrations.removeFirst();
        }
        if (registrations.size() == 5) {
            Log.i("BackendTests", "Pacing temporary accounts to respect the API registration limit");
            Thread.sleep(WINDOW_MS - (now - registrations.peekFirst()));
            now = SystemClock.elapsedRealtime();
            while (!registrations.isEmpty() && now - registrations.peekFirst() >= WINDOW_MS) {
                registrations.removeFirst();
            }
        }
        registrations.addLast(now);
    }
}
