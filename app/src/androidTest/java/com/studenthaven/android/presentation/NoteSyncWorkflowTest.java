package com.studenthaven.android.presentation;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.BackendTestAccounts;
import com.studenthaven.android.R;
import com.studenthaven.android.data.ClassSyncDatabase;
import com.studenthaven.android.data.SessionStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NoteSyncWorkflowTest {
    @Test public void offlineEditorRotationConflictAndDeletedParentRecoveryCopy() throws Exception {
        Assume.assumeTrue("Enable backendIntegration for live note UI tests",
                "true".equals(InstrumentationRegistry.getArguments().getString("backendIntegration")));
        Context context = ApplicationProvider.getApplicationContext();
        HavenApplication app = (HavenApplication) context;
        SessionStore store = new SessionStore(context);
        Assume.assumeTrue("Do not overwrite a developer sign-in", store.load() == null);
        String suffix = UUID.randomUUID().toString();
        String password = "Test-" + suffix;
        BackendTestAccounts.awaitRegistrationSlot();
        var account = app.auth().signIn(true, "Note UI", "note-ui-" + suffix + "@example.com", password, password);
        String owner = account.id();
        app.classSync().save(owner, null, "UI Source", "SRC", "", "");
        app.classSync().save(owner, null, "UI Destination", "DST", "", "");
        String source = app.classSync().list(owner).stream().filter(c -> c.name.equals("UI Source")).findFirst().orElseThrow().id;
        String target = app.classSync().list(owner).stream().filter(c -> c.name.equals("UI Destination")).findFirst().orElseThrow().id;
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> NoteSyncDialogFragment.forClass(source)
                    .showNow(activity.getSupportFragmentManager(), "test-notes"));
            await(app);
            onView(withText(R.string.note_add)).inRoot(isDialog()).perform(scrollTo(), click());
            onView(withHint(R.string.note_sync_title)).inRoot(isDialog()).perform(scrollTo(), replaceText("UI note"), closeSoftKeyboard());
            onView(withHint(R.string.note_sync_content)).inRoot(isDialog()).perform(scrollTo(), replaceText("Offline UI content"), closeSoftKeyboard());
            onView(withHint(R.string.note_sync_tags)).inRoot(isDialog()).perform(scrollTo(), replaceText("biology\nexam"), closeSoftKeyboard());
            scenario.recreate();
            onView(withHint(R.string.note_sync_title)).inRoot(isDialog()).check(matches(withText("UI note")));
            onView(withHint(R.string.note_sync_content)).inRoot(isDialog()).check(matches(withText("Offline UI content")));
            onView(withHint(R.string.note_sync_tags)).inRoot(isDialog()).check(matches(withText("biology\nexam")));
            onView(withText(R.string.save)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            var note = app.classSync().notes().list(owner).get(0);
            assertEquals(0, note.version);
            assertEquals("[\"biology\",\"exam\"]", note.tags);
            onView(withText(R.string.sync_now)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            onView(withText(R.string.edit)).inRoot(isDialog()).perform(scrollTo(), click());
            onView(withHint(R.string.note_sync_content)).inRoot(isDialog()).perform(scrollTo(), replaceText("UI local conflict"), closeSoftKeyboard());
            onView(withHint(R.string.note_sync_tags)).inRoot(isDialog()).perform(scrollTo(), replaceText("local"), closeSoftKeyboard());
            onView(withText(R.string.save)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            app.auth().requestFor(owner, "PUT", "sync/courses/" + source + "/notes/" + note.id,
                    new JSONObject().put("mutation_id", UUID.randomUUID().toString()).put("base_version", 1)
                            .put("deleted", false).put("title", "Server title").put("content", "UI server conflict")
                            .put("tags", new JSONArray("[\"remote\"]")));
            onView(withText(R.string.sync_now)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            onView(withText(R.string.sync_resolve)).inRoot(isDialog()).perform(scrollTo(), click());
            onView(withText(containsString("UI local conflict"))).inRoot(isDialog()).check(matches(withText(containsString("UI server conflict"))));
            onView(withText(R.string.sync_keep_local)).inRoot(isDialog()).perform(click());
            await(app);
            onView(withText(R.string.sync_now)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            assertEquals("UI local conflict", app.classSync().notes().list(owner).get(0).content);
            onView(withText(R.string.edit)).inRoot(isDialog()).perform(scrollTo(), click());
            onView(withHint(R.string.note_sync_content)).inRoot(isDialog()).perform(scrollTo(), replaceText("Recover this UI draft"), closeSoftKeyboard());
            onView(withText(R.string.save)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            app.auth().requestFor(owner, "DELETE", "courses/" + source, new JSONObject().put("version", 1));
            onView(withText(R.string.sync_now)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            onView(withText(R.string.note_recovery)).inRoot(isDialog()).perform(scrollTo(), click());
            await(app);
            onView(withText("UI note")).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()));
            onView(withText(R.string.note_copy_recovery)).inRoot(isDialog()).perform(scrollTo(), click());
            onView(withText("UI Destination (DST)")).inRoot(isDialog()).perform(click());
            onView(withText(R.string.sync_copy)).inRoot(isDialog()).perform(click());
            await(app);
            var original = app.classSync().notes().list(owner).stream().filter(n -> n.id.equals(note.id)).findFirst().orElseThrow();
            assertTrue(original.recovery);
            assertNotNull(original.recoveredCopyId);
            var copy = app.classSync().notes().list(owner).stream().filter(n -> n.id.equals(original.recoveredCopyId))
                    .findFirst().orElseThrow();
            assertEquals(target, copy.courseId);
            assertEquals("Recover this UI draft", copy.content);
            assertEquals("[\"local\"]", copy.tags);
            onView(withText(R.string.note_already_copied)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()));
        } finally {
            await(app);
            app.auth().requestFor(owner, "DELETE", "me", new JSONObject().put("password", password));
            store.clear();
            try (ClassSyncDatabase db = Room.databaseBuilder(context, ClassSyncDatabase.class,
                    "student-haven-class-sync.db").addMigrations(ClassSyncDatabase.MIGRATION_1_2).build()) {
                var sql = db.getOpenHelper().getWritableDatabase();
                for (String table : new String[]{"synced_notes", "note_sync_cursors", "synced_classes", "sync_cursors"}) {
                    sql.execSQL("DELETE FROM " + table + " WHERE accountId = ?", new Object[]{owner});
                }
            }
        }
    }

    private void await(HavenApplication app) throws Exception {
        app.authIo().submit(() -> {}).get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
