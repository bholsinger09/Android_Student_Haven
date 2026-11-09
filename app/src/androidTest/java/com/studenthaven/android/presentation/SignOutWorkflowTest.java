package com.studenthaven.android.presentation;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.studenthaven.android.BackendTestAccounts;
import com.studenthaven.android.BuildConfig;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.R;
import com.studenthaven.android.data.BackendClient;
import com.studenthaven.android.data.BackendException;
import com.studenthaven.android.data.ClassSyncDatabase;
import com.studenthaven.android.data.SessionStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SignOutWorkflowTest {
    @Test public void welcomeRegistrationSignOutAndSignInPreserveStudyDataAndBlockBackNavigation() throws Exception {
        Assume.assumeTrue("Enable backendIntegration for live authentication tests",
                "true".equals(InstrumentationRegistry.getArguments().getString("backendIntegration")));
        Context context = ApplicationProvider.getApplicationContext();
        HavenApplication app = (HavenApplication) context;
        SessionStore store = new SessionStore(context);
        Assume.assumeTrue("Do not overwrite a developer sign-in", store.load() == null);
        String suffix = UUID.randomUUID().toString();
        String password = "Test-" + suffix;
        String email = "android-signout-" + suffix + "@example.com";
        String owner = null;
        BackendClient client = new BackendClient(BuildConfig.BACKEND_URL);
        var oldProfile = context.getSharedPreferences("profile", 0).getAll();
        int classesBefore = app.repository().localCoursesForImport().size();
        try {
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                await(app);
                onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.welcome_sign_in)).check(matches(isDisplayed()));
                onView(withId(R.id.welcome_create)).perform(click());
                onView(withId(R.id.welcome_name)).perform(scrollTo(), replaceText("Welcome Tester"));
                onView(withId(R.id.welcome_email)).perform(scrollTo(), replaceText(email));
                onView(withId(R.id.welcome_password)).perform(scrollTo(), replaceText(password));
                scenario.recreate();
                await(app);
                onView(withId(R.id.welcome_name)).check(matches(withText("Welcome Tester")));
                onView(withId(R.id.welcome_password)).check(matches(withText("")));
                onView(withId(R.id.welcome_password)).perform(scrollTo(), replaceText(password));
                onView(withId(R.id.welcome_confirmation)).perform(scrollTo(), replaceText(password));
                BackendTestAccounts.awaitRegistrationSlot();
                onView(withId(R.id.welcome_submit)).perform(scrollTo());
                hideKeyboard(scenario);
                onView(withId(R.id.welcome_submit)).check(matches(isEnabled())).perform(click());
                await(app);
                java.util.concurrent.atomic.AtomicReference<String> status = new java.util.concurrent.atomic.AtomicReference<>();
                scenario.onActivity(activity -> {
                    android.widget.TextView message = activity.findViewById(R.id.welcome_status);
                    status.set(message == null ? "Welcome screen dismissed" : message.getText().toString());
                });
                assertNotNull("Registration must persist a real session: " + status.get(), store.load());
                JSONObject session = new JSONObject(store.load());
                owner = session.getJSONObject("data").getString("id");
                String token = session.getString("token");
                onView(withId(R.id.tab_home)).check(matches(isSelected()));
                app.classSync().save(owner, null, "Pending class", "", "", "");
                String course = app.classSync().list(owner).get(0).id;
                app.classSync().notes().save(owner, course, null, "Pending note", "Preserve offline work", "pending");
                scenario.recreate();
                await(app);
                onView(withId(R.id.tab_home)).check(matches(isDisplayed()));
                profile();
                onView(withText("Backend Account")).check(doesNotExist());
                onView(withText("Local-only study space")).check(doesNotExist());
                onView(withId(R.id.profile_sign_out)).perform(scrollTo(), click());
                await(app);
                assertNull(store.load());
                onView(withId(R.id.welcome_sign_in)).check(matches(isDisplayed()));
                onView(withId(R.id.welcome_create)).check(matches(isDisplayed()));
                onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                assertEquals("Preserve offline work", app.classSync().notes().list(owner).get(0).content);
                assertTrue(app.classSync().notes().list(owner).get(0).dirty);
                assertEquals(classesBefore, app.repository().localCoursesForImport().size());
                assertEquals(oldProfile, context.getSharedPreferences("profile", 0).getAll());
                try {
                    client.request("GET", "me", null, token);
                    fail("Sign out must revoke this device token");
                } catch (BackendException expected) { assertEquals(401, expected.status()); }
                scenario.recreate();
                await(app);
                onView(withId(R.id.welcome_submit)).check(matches(isDisplayed()));
                signIn(app, email, password + "-wrong");
                onView(withId(R.id.welcome_status)).check(matches(withText(containsString("Invalid email or password"))));
                signIn(app, email, password);
                onView(withId(R.id.tab_home)).check(matches(isSelected()));
                profile();
                // A revoked session returns to welcome instead of leaving private screens behind.
                client.request("POST", "auth/logout", null, new JSONObject(store.load()).getString("token"));
                onView(withId(R.id.profile_sign_out)).perform(scrollTo(), click());
                await(app);
                onView(withId(R.id.welcome_submit)).check(matches(isDisplayed()));
                androidx.test.espresso.Espresso.pressBackUnconditionally();
                assertNull("Back must not restore a signed-out session", store.load());
                if (scenario.getState() == androidx.lifecycle.Lifecycle.State.RESUMED) {
                    onView(withId(R.id.welcome_submit)).check(matches(isDisplayed()));
                    onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                }
            }
            try (ActivityScenario<MainActivity> reopened = ActivityScenario.launch(MainActivity.class)) {
                await(app);
                onView(withId(R.id.welcome_submit)).check(matches(isDisplayed()));
                onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            }
        } finally {
            await(app);
            if (owner != null) {
                app.auth().signIn(false, "", email, password, "");
                app.auth().requestFor(owner, "DELETE", "me", new JSONObject().put("password", password));
                try (ClassSyncDatabase db = Room.databaseBuilder(context, ClassSyncDatabase.class,
                        "student-haven-class-sync.db").addMigrations(ClassSyncDatabase.MIGRATION_1_2).build()) {
                    for (String table : new String[]{"synced_notes", "note_sync_cursors", "synced_classes", "sync_cursors"}) {
                        db.getOpenHelper().getWritableDatabase().execSQL(
                                "DELETE FROM " + table + " WHERE accountId = ?", new Object[]{owner});
                    }
                }
            }
            store.clear();
        }
    }

    private void profile() {
        onView(withId(R.id.tab_more)).perform(click());
        onView(withText(R.string.profile)).perform(scrollTo(), click());
    }

    private void hideKeyboard(ActivityScenario<MainActivity> scenario) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<android.graphics.Rect> bounds = new java.util.concurrent.atomic.AtomicReference<>();
        scenario.onActivity(activity -> androidx.core.view.WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView())
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime()));
        long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
        long stableSince = android.os.SystemClock.elapsedRealtime();
        android.graphics.Rect previous = null;
        boolean stable;
        do {
            scenario.onActivity(activity -> {
                var insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
                visible.set(insets != null && insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
                android.graphics.Rect current = new android.graphics.Rect();
                activity.findViewById(R.id.welcome_submit).getGlobalVisibleRect(current);
                bounds.set(current);
            });
            if (visible.get() || !bounds.get().equals(previous)) stableSince = android.os.SystemClock.elapsedRealtime();
            previous = bounds.get();
            // Insets can report hidden before the form's resize animation finishes.
            stable = !visible.get() && !previous.isEmpty()
                    && android.os.SystemClock.elapsedRealtime() - stableSince >= 250;
            if (!stable) Thread.sleep(50);
        } while (!stable && android.os.SystemClock.elapsedRealtime() < deadline);
        assertTrue("The form must be stable before tapping submit", stable);
    }

    private void signIn(HavenApplication app, String email, String password) throws Exception {
        onView(withId(R.id.welcome_email)).perform(scrollTo(), replaceText(email));
        onView(withId(R.id.welcome_password)).perform(scrollTo(), replaceText(password));
        onView(withId(R.id.welcome_submit)).perform(scrollTo(), click());
        await(app);
    }

    private void await(HavenApplication app) throws Exception {
        app.authIo().submit(() -> {}).get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
