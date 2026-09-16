package com.firewolf.friendsspeaking;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Opt in with `am instrument -e real_media true ...` on an Internet-connected device. */
@RunWith(AndroidJUnit4.class)
public class PlayerSynchronizationTest {
    @Test(timeout = 420000) public void realAudioRestoresSeeksAndContinuesWithoutTheScreen() throws Exception {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("real_media")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Episode episode = Episode.of(1, 1);
        LearningStore store = new LearningStore(context);
        long originalPosition = store.progress(episode);
        long originalDuration = store.duration(episode);
        float originalSpeed = store.speed();
        long originalOffset = store.subtitleOffset(episode);
        android.net.Uri originalAudio = store.customAudio(episode);
        android.net.Uri originalSubtitle = store.customSubtitle(episode);
        store.saveAudio(episode, null);
        store.saveSubtitle(episode, null);
        store.saveProgress(episode, 450000, 1369466);
        store.saveSpeed(1f);
        AtomicReference<PlaybackService> service = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service.set(((PlaybackService.LocalBinder) binder).service());
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { service.set(null); }
        };
        boolean bound = false;
        try (ActivityScenario<PlayerActivity> scenario = ActivityScenario.launch(
                new Intent(context, PlayerActivity.class).putExtra(PlayerActivity.SEASON, 1)
                        .putExtra(PlayerActivity.EPISODE, 1))) {
            bound = context.bindService(new Intent(context, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
            assertTrue(connected.await(60, TimeUnit.SECONDS));
            await(() -> service.get().isPlaying() && service.get().position() >= 450000
                    && service.get().position() < 470000, 180000);
            AtomicReference<String> status = new AtomicReference<>("");
            long deadline = SystemClock.elapsedRealtime() + 180000;
            while (!status.get().contains("原音逐句对齐") && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> {
                    status.set(((TextView) activity.findViewById(R.id.media_status)).getText().toString());
                    assertTrue((activity.getWindow().getAttributes().flags
                            & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
                });
                SystemClock.sleep(200);
            }
            assertTrue(status.get(), status.get().contains("原音逐句对齐"));
            for (long target : new long[]{50200, 600000, 1280000}) {
                main(() -> service.get().seekTo(target, true));
                await(() -> Math.abs(service.get().position() - target) < 1500, 15000);
            }
            main(() -> service.get().pause());
            await(() -> !service.get().isPlaying(), 5000);
            long paused = readPosition(service.get());
            SystemClock.sleep(700);
            assertTrue(Math.abs(readPosition(service.get()) - paused) < 300);
            main(() -> { service.get().setRate(2f); service.get().play(); });
            await(() -> service.get().isPlaying(), 5000);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            long backgroundStart = readPosition(service.get());
            await(() -> service.get().isPlaying() && service.get().position() > backgroundStart + 1000, 15000);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
            await(() -> service.get().isPlaying(), 5000);
        } finally {
            if (bound) context.unbindService(connection);
            context.stopService(new Intent(context, PlaybackService.class));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            store.saveAudio(episode, originalAudio);
            store.saveSubtitle(episode, originalSubtitle);
            store.saveProgress(episode, originalPosition, originalDuration);
            store.saveSpeed(originalSpeed);
            store.saveSubtitleOffset(episode, originalOffset);
        }
    }

    private static void main(Runnable operation) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(operation);
    }

    private static long readPosition(PlaybackService service) {
        long[] result = {0};
        main(() -> result[0] = service.position());
        return result[0];
    }

    private static void await(BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        boolean[] matched = {false};
        while (SystemClock.elapsedRealtime() < deadline) {
            main(() -> matched[0] = condition.getAsBoolean());
            if (matched[0]) return;
            SystemClock.sleep(100);
        }
        fail("Playback condition timed out");
    }
}
