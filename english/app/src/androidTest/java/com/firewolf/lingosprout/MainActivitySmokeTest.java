package com.firewolf.lingosprout;

import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class MainActivitySmokeTest {
    @Test
    public void testLearningHomeReplacesStartupScreen() {
        AtomicBoolean homeVisible = new AtomicBoolean();
        AtomicBoolean errorVisible = new AtomicBoolean();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            long deadline = SystemClock.uptimeMillis() + 10_000;
            do {
                scenario.onActivity(activity -> {
                    homeVisible.set(activity.findViewById(R.id.learning_content) != null);
                    errorVisible.set(activity.findViewById(R.id.load_error_content) != null);
                });
                if (!homeVisible.get() && !errorVisible.get()) SystemClock.sleep(50);
            } while (!homeVisible.get() && !errorVisible.get()
                    && SystemClock.uptimeMillis() < deadline);
        }

        assertFalse("The load error screen appeared", errorVisible.get());
        assertTrue("The learning home did not appear", homeVisible.get());
    }
}
