package com.firewolf.players;

import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class MainActivitySmokeTest {
    @Test
    public void launchesCatalogScreen() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                new Intent(getApplicationContext(), MainActivity.class))) {
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.video_list)));
        }
    }
}
