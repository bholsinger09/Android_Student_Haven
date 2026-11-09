package com.studenthaven.android.data;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.studenthaven.android.HavenApplication;
import com.studenthaven.android.R;
import com.studenthaven.android.presentation.MainActivity;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SocialAuthUiTest {
    @Test public void pendingBrowserSignInSurvivesRecreationAndCanBeCancelled() throws Exception {
        var context = ApplicationProvider.getApplicationContext();
        SessionStore session = new SessionStore(context);
        SessionStore pending = new SessionStore(context, "social-auth-pending");
        Assume.assumeTrue("Do not overwrite a developer sign-in", session.load() == null && pending.load() == null);
        try {
            pending.save(new JSONObject().put("provider", "google")
                    .put("attempt_id", "0472d138-6ba7-4133-a02d-c903228e019e")
                    .put("verifier", "instrumentation-only")
                    .put("expires_at", Instant.now().plusSeconds(600).toString()).toString());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                await();
                onView(withId(R.id.welcome_finish_social)).check(matches(isDisplayed()));
                onView(withId(R.id.welcome_google)).check(matches(not(isEnabled())));
                scenario.recreate();
                await();
                onView(withId(R.id.welcome_cancel_social)).perform(scrollTo(), click());
                await();
                assertNull(pending.load());
                assertNull(session.load());
                onView(withId(R.id.welcome_finish_social)).check(matches(withEffectiveVisibility(Visibility.GONE)));
                onView(withId(R.id.welcome_google)).check(matches(isEnabled()));
                onView(withId(R.id.welcome_microsoft)).check(matches(isEnabled()));
                onView(withId(R.id.navigation)).check(matches(withEffectiveVisibility(Visibility.GONE)));
            }
        } finally { pending.clear(); }
    }

    private void await() throws Exception {
        HavenApplication app = ApplicationProvider.getApplicationContext();
        app.authIo().submit(() -> {}).get(30, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
