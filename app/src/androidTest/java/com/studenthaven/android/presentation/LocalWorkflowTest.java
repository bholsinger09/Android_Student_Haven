package com.studenthaven.android.presentation;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.studenthaven.android.R;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LocalWorkflowTest {
    @Test public void classesNotesGenerationReviewGradesAndDeletionWork() throws Exception {
        String className = "Biology " + UUID.randomUUID().toString().substring(0, 6);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            page("Classes");
            onView(withId(R.id.add)).perform(click());
            field(R.string.course_name, className);
            field(R.string.course_code, "BIO101");
            save();
            awaitDialogClosed();
            onView(allOf(withText(containsString(className)), isDisplayed())).check(matches(isDisplayed()));

            page("Notes");
            onView(withId(R.id.course)).perform(click());
            onView(withText(containsString(className))).perform(click());
            onView(withId(R.id.add)).perform(click());
            field(R.string.title, "Cells");
            field(R.string.content, "Mitosis is cell division. Atom: a unit of matter.");
            save();
            awaitDialogClosed();
            onView(withText("Cells")).perform(scrollTo(), click());
            field(R.string.content, "Mitosis is cell division. Atom: a unit of matter. DNA: genetic material.");
            onView(withText(R.string.generate_cards)).perform(scrollTo(), click());
            awaitDialogClosed();

            page("Flashcards");
            onView(withText(containsString("Mitosis"))).check(matches(isDisplayed()));
            onView(withText(containsString("Mitosis"))).perform(click());
            onView(withText(R.string.reveal)).perform(click());
            onView(withText("cell division")).check(matches(isDisplayed()));
            scenario.recreate();
            onView(withText("cell division")).check(matches(isDisplayed()));
            onView(withText(R.string.review_good)).perform(click());
            awaitIdle();
            onView(withText(R.string.study_complete)).check(matches(isDisplayed()));
            onView(withText(R.string.close)).perform(click());
            onView(withText(startsWith("Next review:"))).check(matches(isDisplayed()));
            onView(withText("DNA")).perform(scrollTo()).check(matches(isDisplayed()));

            page("Grades");
            onView(withId(R.id.add)).perform(click());
            field(R.string.assignment_name, "Quiz");
            field(R.string.score, "80");
            save();
            awaitDialogClosed();
            onView(withId(R.id.summary)).check(matches(withText("80.0%")));
            scenario.recreate();
            onView(withId(R.id.summary)).check(matches(withText("80.0%")));

            page("Classes");
            onView(allOf(withText(containsString(className)), isDisplayed())).perform(click());
            onView(withText(R.string.delete)).perform(scrollTo(), click());
            onView(withText(R.string.delete)).perform(click());
            awaitIdle();
            page("Notes");
            onView(withText(containsString("Cells"))).check(
                    androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
        }
    }

    @Test public void invalidCourseRemainsEditableAndDraftSurvivesRecreation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            page("Classes");
            onView(withId(R.id.add)).perform(click());
            save();
            awaitIdle();
            onView(withText("Class name cannot be empty.")).check(matches(isDisplayed()));
            field(R.string.course_name, "Unsaved draft");
            scenario.recreate();
            onView(withHint(R.string.course_name)).check(matches(withText("Unsaved draft")));
            onView(withText(R.string.cancel)).perform(click());
        }
    }

    @Test public void bottomTabsMoreAndLocalOnlyScreensAreReachableAndRestored() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            page("Home");
            onView(withId(R.id.tab_home)).check(matches(isSelected()));
            onView(withId(R.id.screen_title)).check(matches(withText(R.string.home)));
            page("Profile");
            onView(withId(R.id.tab_more)).check(matches(isSelected()));
            onView(withText(R.string.your_statistics)).check(matches(isDisplayed()));
            scenario.recreate();
            onView(withId(R.id.screen_title)).check(matches(withText(R.string.profile)));
            page("Study Groups");
            onView(withText(R.string.groups_deferred)).check(matches(isDisplayed()));
            page("Home");
            onView(withId(R.id.tab_home)).check(matches(isSelected()));
        }

    }

    @Test public void homeCollegePickerSearchesAndPersistsWithoutRequiringAnAccount() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        var preferences = context.getSharedPreferences("profile", 0);
        String oldCollege = preferences.getString("college", null);
        String oldName = preferences.getString("name", null);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            page("Home");
            onView(withId(R.id.college_choice)).perform(click());
            field(R.string.college, "Boise");
            onView(withText("Boise State University")).perform(scrollTo(), click());
            save();
            awaitDialogClosed();
            onView(withText("Boise State University")).check(matches(isDisplayed()));
            onView(withText(R.string.quick_stats)).check(matches(isDisplayed()));
            scenario.recreate();
            onView(withText("Boise State University")).check(matches(isDisplayed()));
            org.junit.Assert.assertEquals(oldName, preferences.getString("name", null));
        } finally {
            preferences.edit().putString("college", oldCollege).commit();
        }
    }

    private void page(String title) throws Exception {
        int tab = switch (title) {
            case "Home" -> R.id.tab_home;
            case "Classes" -> R.id.tab_classes;
            case "Notes" -> R.id.tab_notes;
            case "Flashcards" -> R.id.tab_flashcards;
            default -> R.id.tab_more;
        };
        onView(withId(tab)).perform(click());
        if (tab == R.id.tab_more) {
            onView(withText(title)).perform(scrollTo(), click());
        }
        awaitIdle();
    }

    private void field(int hint, String text) {
        onView(withHint(hint)).perform(scrollTo(), replaceText(text), closeSoftKeyboard());
    }

    private void save() { onView(withText(R.string.save)).perform(click()); }

    private void awaitIdle() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        var application = (com.studenthaven.android.HavenApplication) context;
        application.io().submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void awaitDialogClosed() throws Exception {
        awaitIdle();
        onView(withText(R.string.save)).check(
                androidx.test.espresso.assertion.ViewAssertions.doesNotExist());
    }
}
