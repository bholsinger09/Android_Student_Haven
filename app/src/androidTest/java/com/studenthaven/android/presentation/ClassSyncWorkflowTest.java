package com.studenthaven.android.presentation;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.studenthaven.android.BuildConfig;
import com.studenthaven.android.BackendTestAccounts;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.R;
import com.studenthaven.android.data.AuthRepository;
import com.studenthaven.android.data.BackendClient;
import com.studenthaven.android.data.ClassSyncDatabase;
import com.studenthaven.android.data.SessionStore;
import com.studenthaven.android.data.StudyDatabase;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ClassSyncWorkflowTest {
    @Test public void savesOfflineRestoresDraftResolvesConflictAndRequiresImportConsent() throws Exception {
        Assume.assumeTrue("Enable backendIntegration for local Laravel UI tests",
                "true".equals(InstrumentationRegistry.getArguments().getString("backendIntegration")));
        Context context = ApplicationProvider.getApplicationContext();
        HavenApplication app = (HavenApplication) context;
        SessionStore store = new SessionStore(context);
        Assume.assumeTrue("Do not overwrite a developer sign-in", store.load() == null);
        String suffix = UUID.randomUUID().toString();
        String password = "Test-" + suffix;
        BackendTestAccounts.awaitRegistrationSlot();
        AuthRepository.Account account = app.auth().signIn(true, "Class UI",
                "sync-ui-" + suffix + "@example.com", password, password);
        StudyDatabase.Course original = new StudyDatabase.Course();
        original.name = "Consent source " + suffix;
        original.code = "LOCAL";
        app.repository().saveCourse(original, false);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> new ClassSyncDialogFragment()
                    .showNow(activity.getSupportFragmentManager(), "class-sync-test"));
            await(app);
            onView(withId(R.id.sync_name)).perform(scrollTo(), replaceText("UI offline class"), closeSoftKeyboard());
            onView(withId(R.id.sync_code)).perform(scrollTo(), replaceText("BIO"), closeSoftKeyboard());
            scenario.recreate();
            onView(withId(R.id.sync_name)).check(matches(withText("UI offline class")));
            onView(withId(R.id.sync_save)).perform(scrollTo(), click());
            await(app);
            onView(withText(containsString(context.getString(R.string.sync_pending))))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.sync_now)).perform(scrollTo(), click());
            await(app);
            onView(withText(containsString(context.getString(R.string.sync_up_to_date))))
                    .perform(scrollTo()).check(matches(isDisplayed()));
            String id = app.classSync().list(account.id()).get(0).id;
            onView(withText(R.string.edit)).perform(scrollTo(), click());
            onView(withId(R.id.sync_name)).perform(scrollTo(), replaceText("UI local edit"), closeSoftKeyboard());
            onView(withId(R.id.sync_save)).perform(scrollTo(), click());
            await(app);
            app.auth().requestFor(account.id(), "PUT", "sync/courses/" + id, new JSONObject()
                    .put("mutation_id", UUID.randomUUID().toString()).put("base_version", 1)
                    .put("deleted", false).put("name", "UI remote edit").put("code", "BIO"));
            onView(withId(R.id.sync_now)).perform(scrollTo(), click());
            await(app);
            onView(withText(R.string.sync_resolve)).perform(scrollTo(), click());
            onView(withText(containsString("LOCAL VERSION"))).check(matches(withText(containsString("UI local edit"))));
            onView(withText(containsString("LOCAL VERSION"))).check(matches(withText(containsString("UI remote edit"))));
            onView(withText(R.string.sync_use_server)).perform(click());
            await(app);
            onView(withText(containsString("UI remote edit"))).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withId(R.id.sync_import)).perform(scrollTo(), click());
            onView(withText(R.string.cancel)).perform(click());
            assertEquals(1, app.classSync().list(account.id()).size());
            onView(withId(R.id.sync_import)).perform(scrollTo(), click());
            onView(withText(R.string.sync_copy)).perform(click());
            await(app);
            assertEquals(app.repository().localCoursesForImport().size() + 1,
                    app.classSync().list(account.id()).size());
            onView(withId(R.id.sync_import)).perform(scrollTo(), click());
            onView(withText(R.string.sync_copy)).perform(click());
            await(app);
            assertEquals(app.repository().localCoursesForImport().size() + 1,
                    app.classSync().list(account.id()).size());
            assertTrue(app.repository().localCoursesForImport().stream()
                    .anyMatch(item -> item.id.equals(original.id) && item.name.equals(original.name)));
            BackendClient client = new BackendClient(BuildConfig.BACKEND_URL);
            client.request("POST", "auth/logout", null, new JSONObject(store.load()).getString("token"));
            onView(withId(R.id.sync_now)).perform(scrollTo(), click());
            await(app);
            onView(withId(R.id.sync_identity)).check(matches(withText(R.string.account_signed_out)));
            onView(withId(R.id.sync_close)).perform(click());
            await(app);
            onView(withId(R.id.welcome_submit)).check(matches(isDisplayed()));
        } finally {
            BackendClient client = new BackendClient(BuildConfig.BACKEND_URL);
            JSONObject login = client.request("POST", "auth/login", new JSONObject()
                    .put("email", account.email()).put("password", password).put("device_name", "test-cleanup"), null);
            client.request("DELETE", "me", new JSONObject().put("password", password), login.getString("token"));
            store.clear();
            app.repository().deleteCourse(original);
            try (ClassSyncDatabase database = Room.databaseBuilder(context, ClassSyncDatabase.class,
                    "student-haven-class-sync.db").build()) {
                var sql = database.getOpenHelper().getWritableDatabase();
                sql.execSQL("DELETE FROM synced_classes WHERE accountId = ?", new Object[]{account.id()});
                sql.execSQL("DELETE FROM sync_cursors WHERE accountId = ?", new Object[]{account.id()});
            }
        }
    }

    private void await(HavenApplication app) throws Exception {
        app.authIo().submit(() -> {}).get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
