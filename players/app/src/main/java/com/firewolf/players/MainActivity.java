package com.firewolf.players;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity implements VideoAdapter.Listener {
    private enum Mode { HOME, MOVIES, NASA, FAVORITES }

    private CatalogRepository repository;
    private PlaybackStore playbackStore;
    private VideoAdapter adapter;
    private SwipeRefreshLayout refreshLayout;
    private TextView syncStatus;
    private EditText search;
    private LinearLayout categories;
    private List<VideoItem> allItems = new ArrayList<>();
    private Mode mode = Mode.HOME;
    private String category = "全部";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        applyInsets(findViewById(R.id.root));

        repository = CatalogRepository.get(this);
        playbackStore = new PlaybackStore(this);
        syncStatus = findViewById(R.id.sync_status);
        search = findViewById(R.id.search);
        categories = findViewById(R.id.categories);
        refreshLayout = findViewById(R.id.swipe_refresh);

        RecyclerView list = findViewById(R.id.video_list);
        GridLayoutManager manager = new GridLayoutManager(this, getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 3 : 2);
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                int type = adapter.getItemViewType(position);
                return type == VideoAdapter.TYPE_VIDEO ? 1 : manager.getSpanCount();
            }
        });
        adapter = new VideoAdapter(ImageLoader.get(this), this);
        list.setLayoutManager(manager);
        list.setAdapter(adapter);

        allItems = repository.loadCached();
        bindCategories();
        render();
        bindActions();
        updateSyncLabel(repository.lastSuccessfulSync(), allItems.isEmpty() ? "等待更新" : "片单已就绪");

        long age = System.currentTimeMillis() - repository.lastSuccessfulSync();
        if (age > 15 * 60 * 1000L || allItems.isEmpty()) refreshResources();
        AppUpdateChecker.check(this, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mode == Mode.FAVORITES) render();
    }

    private void bindActions() {
        refreshLayout.setColorSchemeResources(R.color.brand);
        refreshLayout.setOnRefreshListener(this::refreshResources);
        findViewById(R.id.settings).setOnClickListener(view -> showSettings());
        findViewById(R.id.sync_status).setOnClickListener(view -> refreshResources());

        bindNavigation(R.id.nav_home, Mode.HOME);
        bindNavigation(R.id.nav_movies, Mode.MOVIES);
        bindNavigation(R.id.nav_nasa, Mode.NASA);
        bindNavigation(R.id.nav_favorites, Mode.FAVORITES);
        selectNavigation();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { render(); }
            @Override public void afterTextChanged(Editable value) {}
        });
    }

    private void bindNavigation(int id, Mode target) {
        findViewById(id).setOnClickListener(view -> {
            mode = target;
            category = "全部";
            selectNavigation();
            bindCategories();
            render();
        });
    }

    private void selectNavigation() {
        int[] ids = {R.id.nav_home, R.id.nav_movies, R.id.nav_nasa, R.id.nav_favorites};
        Mode[] modes = {Mode.HOME, Mode.MOVIES, Mode.NASA, Mode.FAVORITES};
        for (int index = 0; index < ids.length; index++) {
            TextView view = findViewById(ids[index]);
            boolean selected = modes[index] == mode;
            view.setTextColor(getColor(selected ? R.color.brand : R.color.text_secondary));
            view.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void bindCategories() {
        categories.removeAllViews();
        List<String> values = new ArrayList<>();
        values.add("全部");
        if (mode == Mode.HOME) {
            values.add("最新");
            values.add("开源电影");
            values.add("太空探索");
        } else if (mode == Mode.MOVIES) {
            values.add("动画");
            values.add("科幻");
            values.add("短片");
        } else if (mode == Mode.NASA) {
            values.add("2026");
            values.add("2025");
        }
        for (String value : values) {
            TextView chip = new TextView(this);
            chip.setText(value);
            chip.setTextSize(13);
            chip.setTextColor(getColor(value.equals(category) ? R.color.surface : R.color.text_secondary));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setSelected(value.equals(category));
            chip.setBackgroundResource(R.drawable.bg_chip);
            int horizontal = dp(15);
            chip.setPadding(horizontal, dp(8), horizontal, dp(8));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            chip.setLayoutParams(params);
            chip.setOnClickListener(view -> {
                category = value;
                bindCategories();
                render();
            });
            categories.addView(chip);
        }
    }

    private void render() {
        if (adapter == null) return;
        String query = search == null ? "" : search.getText().toString();
        List<VideoItem> filtered = new ArrayList<>();
        for (VideoItem item : allItems) {
            boolean modeMatch = mode == Mode.HOME
                    || (mode == Mode.MOVIES && !"NASA".equals(item.category))
                    || (mode == Mode.NASA && "NASA".equals(item.category))
                    || (mode == Mode.FAVORITES && playbackStore.isFavorite(item.id));
            if (!modeMatch || !item.matches(query)) continue;
            boolean categoryMatch = "全部".equals(category)
                    || ("最新".equals(category) && item.addedAt.compareTo("2025") >= 0)
                    || ("开源电影".equals(category) && !"NASA".equals(item.category))
                    || ("太空探索".equals(category) && "NASA".equals(item.category))
                    || ("短片".equals(category) && !"NASA".equals(item.category))
                    || category.equals(item.category)
                    || category.equals(item.year);
            if (categoryMatch) filtered.add(item);
        }
        String title = mode == Mode.FAVORITES ? "我的收藏" : mode == Mode.NASA ? "NASA 最新视频" : mode == Mode.MOVIES ? "开放许可电影" : "为你推荐";
        adapter.submit(filtered, title);
    }

    private void refreshResources() {
        refreshLayout.setRefreshing(true);
        syncStatus.setText("正在更新");
        repository.refresh(result -> {
            refreshLayout.setRefreshing(false);
            allItems = result.items;
            bindCategories();
            render();
            updateSyncLabel(repository.lastSuccessfulSync(), result.status);
            Toast.makeText(this, result.status + "，共 " + result.items.size() + " 条", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateSyncLabel(long timestamp, String fallback) {
        if (timestamp <= 0) {
            syncStatus.setText(fallback);
            return;
        }
        String value = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(timestamp));
        syncStatus.setText(value + " 已更新");
    }

    private void showSettings() {
        EditText input = new EditText(this);
        input.setText(repository.remoteCatalogUrl());
        input.setTextColor(getColor(R.color.text_primary));
        input.setHintTextColor(getColor(R.color.text_secondary));
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("远程片单")
                .setMessage("输入你有权使用的 HTTPS JSON 片单地址。应用每 12 小时后台同步，也可下拉立即更新。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", null)
                .setPositiveButton("保存并更新", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (!repository.setRemoteCatalogUrl(input.getText().toString())) {
                    input.setError("仅支持 HTTPS 地址");
                    return;
                }
                dialog.dismiss();
                refreshResources();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                repository.resetRemoteCatalogUrl();
                dialog.dismiss();
                refreshResources();
            });
        });
        dialog.show();
    }

    @Override
    public void onVideoSelected(VideoItem item) {
        Intent intent = new Intent(this, DetailActivity.class);
        VideoIntents.put(intent, item);
        startActivity(intent);
    }

    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
