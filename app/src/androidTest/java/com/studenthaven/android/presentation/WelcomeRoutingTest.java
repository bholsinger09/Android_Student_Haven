package com.studenthaven.android.presentation;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.R;
import com.studenthaven.android.data.SessionStore;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WelcomeRoutingTest {
    @Test public void signedOutLaunchAndRecreationNeverExposeStudyTabs() throws Exception {
        SessionStore store = new SessionStore(ApplicationProvider.getApplicationContext());
        Assume.assumeTrue("Do not overwrite a developer sign-in", store.load() == null);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            await();
            onView(withId(R.id.welcome_sign_in)).check(matches(isDisplayed()));
            onView(withId(R.id.welcome_create)).check(matches(isDisplayed()));
            onView(withId(R.id.welcome_google)).check(matches(isDisplayed()));
            onView(withId(R.id.welcome_microsoft)).check(matches(isDisplayed()));
            onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            scenario.recreate();
            await();
            onView(withId(R.id.welcome_submit)).check(matches(isDisplayed()));
            onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
        }
    }

    @Test public void unsolicitedProviderCallbackCannotSignInOrRevealStudyTabs() throws Exception {
        SessionStore store = new SessionStore(ApplicationProvider.getApplicationContext());
        Assume.assumeTrue("Do not overwrite a developer sign-in", store.load() == null);
        android.content.Intent intent = new android.content.Intent(ApplicationProvider.getApplicationContext(), MainActivity.class)
                .setData(android.net.Uri.parse("studenthaven://oauth/callback?attempt=ffffffff-ffff-ffff-ffff-ffffffffffff"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            await();
            onView(withId(R.id.welcome_status)).check(matches(withText(containsString("No provider sign-in"))));
            onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            assertNull(store.load());
        }
    }

    @Test public void expiredSessionReturnsToWelcomeInsteadOfRestoringStudyScreen() throws Exception {
        SessionStore store = new SessionStore(ApplicationProvider.getApplicationContext());
        Assume.assumeTrue("Do not overwrite a developer sign-in", store.load() == null);
        try {
            store.save(new JSONObject().put("token", "instrumentation-only")
                    .put("expires_at", "2099-01-01T00:00:00Z")
                    .put("data", new JSONObject().put("id", "expiry-ui-test").put("name", "UI Tester")
                            .put("email", "expiry@example.com")).toString());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                await();
                onView(withId(R.id.tab_home)).check(matches(isDisplayed()));
                store.save("{\"expires_at\":\"2020-01-01T00:00:00Z\"}");
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
                await();
                onView(withId(R.id.welcome_submit)).check(matches(isDisplayed()));
                onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.welcome_status)).check(matches(withText(containsString("expired"))));
                assertNull(store.load());
            }
        } finally { store.clear(); }
    }

    private void await() throws Exception {
        HavenApplication app = ApplicationProvider.getApplicationContext();
        app.authIo().submit(() -> {}).get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
