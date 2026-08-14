package com.firewolf.lingosprout;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.TextView;

import com.firewolf.lingosprout.data.ProgressStore;
import com.firewolf.lingosprout.data.WordRepository;
import com.firewolf.lingosprout.speech.WordSpeaker;
import com.firewolf.lingosprout.ui.LingoSproutView;

public final class MainActivity extends Activity {
    private WordSpeaker speaker;
    private LingoSproutView learningView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        try {
            WordRepository repository = WordRepository.load(this);
            ProgressStore progressStore = new ProgressStore(this);
            speaker = new WordSpeaker(this);
            learningView = new LingoSproutView(this, repository, progressStore, speaker);
            setContentView(learningView);
        } catch (Exception error) {
            TextView message = new TextView(this);
            message.setText(R.string.load_error);
            message.setTextColor(Color.rgb(32, 51, 44));
            message.setTextSize(20);
            message.setGravity(android.view.Gravity.CENTER);
            message.setPadding(40, 40, 40, 40);
            message.setBackgroundColor(Color.rgb(247, 250, 248));
            setContentView(message);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(247, 250, 248));
        window.setNavigationBarColor(Color.rgb(247, 250, 248));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (android.os.Build.VERSION.SDK_INT >= 26) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @Override
    public void onBackPressed() {
        if (learningView != null && learningView.handleBack()) return;
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (speaker != null) speaker.stop();
    }

    @Override
    protected void onDestroy() {
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }
}
