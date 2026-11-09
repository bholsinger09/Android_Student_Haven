package com.studenthaven.android.presentation;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.studenthaven.android.BuildConfig;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.R;
import com.studenthaven.android.data.AuthRepository;
import com.studenthaven.android.data.BackendClient;
import com.studenthaven.android.data.BackendException;
import com.studenthaven.android.data.SessionStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BackendAuthWorkflowTest {
    @Test public void registerVerifyRestoreLogoutLoginAndRevokedSessionLeaveLocalDataUntouched() throws Exception {
        Assume.assumeTrue("Enable with -Pandroid.testInstrumentationRunnerArguments.backendIntegration=true",
                "true".equals(InstrumentationRegistry.getArguments().getString("backendIntegration")));
        Context context = ApplicationProvider.getApplicationContext();
        HavenApplication application = (HavenApplication) context;
        SessionStore store = new SessionStore(context);
        Assume.assumeTrue("Do not overwrite a developer's saved sign-in", store.load() == null);
        var oldProfile = context.getSharedPreferences("profile", 0).getAll();
        int[] counts = localCounts(context);
        String email = "android-" + UUID.randomUUID() + "@example.com";
        String password = "Test-" + UUID.randomUUID();
        BackendClient client = new BackendClient(BuildConfig.BACKEND_URL);
        String cleanupToken = null;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.tab_more)).perform(click());
            onView(withText(R.string.profile)).perform(scrollTo(), click());
            onView(withText(R.string.backend_account)).perform(scrollTo(), click());
            await(application);
            onView(withId(R.id.account_check)).perform(scrollTo(), click());
            await(application);
            onView(withId(R.id.account_status)).check(matches(withText(containsString("Connected to Laravel"))));
            onView(withId(R.id.account_email)).perform(scrollTo(), replaceText(email), closeSoftKeyboard());
            scenario.recreate();
            onView(withId(R.id.account_email)).check(matches(withText(email)));
            onView(withId(R.id.account_password)).check(matches(withText("")));
            onView(withId(R.id.account_password)).perform(scrollTo(), replaceText(password), closeSoftKeyboard());
            onView(withId(R.id.account_submit)).perform(scrollTo(), click());
            await(application);
            onView(withId(R.id.account_status)).check(matches(withText(containsString("Invalid email or password"))));
            onView(withId(R.id.account_mode)).perform(scrollTo(), click());
            onView(withId(R.id.account_name)).perform(scrollTo(), replaceText("Android Tester"), closeSoftKeyboard());
            onView(withId(R.id.account_password)).perform(scrollTo(), replaceText(password), closeSoftKeyboard());
            onView(withId(R.id.account_confirmation)).perform(scrollTo(), replaceText(password), closeSoftKeyboard());
            onView(withId(R.id.account_submit)).perform(scrollTo(), click());
            await(application);
            cleanupToken = new JSONObject(store.load()).getString("token");
            onView(withId(R.id.account_identity)).check(matches(withText(containsString(email))));
            AuthRepository restored = new AuthRepository(client, new SessionStore(context));
            assertEquals(email, restored.restore().email());
            assertEquals(email, restored.check().email());
            scenario.recreate();
            onView(withId(R.id.account_identity)).check(matches(withText(containsString(email))));
            onView(withId(R.id.account_check)).perform(scrollTo(), click());
            await(application);
            onView(withId(R.id.account_status)).check(matches(withText(containsString("account was verified"))));
            onView(withId(R.id.account_logout)).perform(scrollTo(), click());
            await(application);
            assertNull(store.load());
            try {
                client.request("GET", "me", null, cleanupToken);
                fail("Logout must revoke token");
            } catch (BackendException expected) {
                assertEquals(401, expected.status());
            }
            onView(withId(R.id.account_mode)).perform(scrollTo(), click());
            onView(withId(R.id.account_email)).perform(scrollTo(), replaceText(email), closeSoftKeyboard());
            onView(withId(R.id.account_password)).perform(scrollTo(), replaceText(password), closeSoftKeyboard());
            onView(withId(R.id.account_submit)).perform(scrollTo(), click());
            await(application);
            cleanupToken = new JSONObject(store.load()).getString("token");
            client.request("POST", "auth/logout", null, cleanupToken);
            onView(withId(R.id.account_check)).perform(scrollTo(), click());
            await(application);
            onView(withId(R.id.account_identity)).check(matches(withText(R.string.account_signed_out)));
            onView(withId(R.id.account_status)).check(matches(withText(containsString("session expired"))));
            assertNull(store.load());
            assertEquals(oldProfile, context.getSharedPreferences("profile", 0).getAll());
            assertArrayEquals(counts, localCounts(context));
        } finally {
            // Obtain a fresh token if the workflow intentionally revoked the prior one.
            if (cleanupToken != null) {
                JSONObject login = client.request("POST", "auth/login", new JSONObject()
                        .put("email", email).put("password", password).put("device_name", "test-cleanup"), null);
                client.request("DELETE", "me", new JSONObject().put("password", password), login.getString("token"));
            }
            store.clear();
        }
    }

    private void await(HavenApplication application) throws Exception {
        application.authIo().submit(() -> {}).get(20, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private int[] localCounts(Context context) {
        try (var database = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath("student-haven.db").getPath(), null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY)) {
            String[] tables = {"courses", "notes", "flashcards", "assignments"};
            int[] counts = new int[tables.length];
            for (int i = 0; i < tables.length; i++) {
                counts[i] = (int) android.database.DatabaseUtils.queryNumEntries(database, tables[i]);
            }
            return counts;
        }
    }
}
