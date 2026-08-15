package com.firewolf.lingosprout;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.firewolf.lingosprout.data.ProgressStore;
import com.firewolf.lingosprout.data.WordRepository;
import com.firewolf.lingosprout.speech.WordSpeaker;
import com.firewolf.lingosprout.ui.LingoSproutView;

public final class MainActivity extends Activity {
    private static final String LOG_TAG = "LingoSprout";

    private WordSpeaker speaker;
    private LingoSproutView learningView;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createStartupView());
        configureWindow();

        Thread catalogLoader = new Thread(this::loadLearningContent, "word-catalog-loader");
        catalogLoader.start();
    }

    private void loadLearningContent() {
        try {
            WordRepository repository = WordRepository.load(getApplicationContext());
            runOnUiThread(() -> showLearningContent(repository));
        } catch (Exception error) {
            Log.e(LOG_TAG, "Unable to load the word library", error);
            runOnUiThread(this::showLoadError);
        }
    }

    private void showLearningContent(WordRepository repository) {
        if (destroyed) return;
        try {
            ProgressStore progressStore = new ProgressStore(this);
            speaker = new WordSpeaker(getApplicationContext());
            learningView = new LingoSproutView(this, repository, progressStore, speaker);
            learningView.setId(R.id.learning_content);
            setContentView(learningView);
            initializeSpeakerAfterFirstDraw(speaker);
        } catch (RuntimeException error) {
            Log.e(LOG_TAG, "Unable to create the learning screen", error);
            showLoadError();
        }
    }

    private void initializeSpeakerAfterFirstDraw(WordSpeaker pendingSpeaker) {
        ViewTreeObserver observer = learningView.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                learningView.post(() -> {
                    if (!destroyed && speaker == pendingSpeaker) pendingSpeaker.initialize();
                });
                return true;
            }
        });
    }

    private View createStartupView() {
        FrameLayout root = new FrameLayout(this);
        root.setId(R.id.startup_content);
        root.setBackgroundColor(Color.rgb(247, 250, 248));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        content.addView(logo, new LinearLayout.LayoutParams(dp(88), dp(88)));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.rgb(32, 51, 44));
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(6);
        content.addView(title, titleParams);

        TextView status = new TextView(this);
        status.setText(R.string.loading_words);
        status.setTextColor(Color.rgb(111, 125, 119));
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(8);
        content.addView(status, statusParams);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(66, 169, 120)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(128), dp(4));
        progressParams.topMargin = dp(22);
        content.addView(progress, progressParams);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(content, contentParams);
        return root;
    }

    private void showLoadError() {
        if (destroyed) return;
        TextView message = new TextView(this);
        message.setId(R.id.load_error_content);
        message.setText(R.string.load_error);
        message.setTextColor(Color.rgb(32, 51, 44));
        message.setTextSize(18);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(40), dp(40), dp(40), dp(40));
        message.setBackgroundColor(Color.rgb(247, 250, 248));
        setContentView(message);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void configureWindow() {
        Window window = getWindow();
        View decorView = window.getDecorView();
        window.setStatusBarColor(Color.rgb(247, 250, 248));
        window.setNavigationBarColor(Color.rgb(247, 250, 248));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Api30WindowAppearance.apply(decorView);
        } else if (android.os.Build.VERSION.SDK_INT >= 26) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @TargetApi(30)
    private static final class Api30WindowAppearance {
        private Api30WindowAppearance() {}

        static void apply(View decorView) {
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller == null) return;
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(lightBars, lightBars);
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
        destroyed = true;
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }
}
