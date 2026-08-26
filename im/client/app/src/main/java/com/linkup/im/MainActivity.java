package com.linkup.im;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.linkup.im.databinding.ActivityMainBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends AppCompatActivity implements RealtimeClient.Listener {
    private static final long UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private ActivityMainBinding binding;
    private LinkUpApp app;
    private ConversationAdapter conversationAdapter;
    private ContactAdapter contactAdapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private AppUpdate pendingUpdate;
    private String promptedUpdateVersion = "";
    private boolean checkingForUpdate;
    private int selectedTab = 0;
    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            checkForUpdate();
            handler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS);
        }
    };
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {});
    private final ActivityResultLauncher<Intent> installPermission = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                    downloadPendingUpdate();
                } else {
                    Toast.makeText(this, "需要允许连线安装应用后才能更新", Toast.LENGTH_LONG).show();
                }
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (LinkUpApp) getApplication();
        if (app.session().user() == null) {
            returnToLogin();
            return;
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Ui.edgeToEdge(this, binding.root);
        setupProfile();
        setupLists();
        setupNavigation();
        binding.logoutButton.setOnClickListener(view -> confirmLogout());
        binding.contactSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleSearch(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        loadConversations();
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        app.realtime().addListener(this);
        app.realtime().connect();
        if (binding != null && selectedTab == 0) loadConversations();
        handler.removeCallbacks(updateCheck);
        handler.post(updateCheck);
    }

    @Override protected void onStop() {
        app.realtime().removeListener(this);
        handler.removeCallbacks(updateCheck);
        super.onStop();
    }

    private void checkForUpdate() {
        if (binding == null || checkingForUpdate || app.session().updateDownloadId() >= 0) return;
        checkingForUpdate = true;
        app.api().latestApp(new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                checkingForUpdate = false;
                JSONObject latest = body.optJSONObject("release");
                if (latest == null) return;
                AppUpdate update = AppUpdate.from(latest);
                if (!update.isValid()
                        || !AppUpdate.isNewer(update.version, BuildConfig.VERSION_NAME)
                        || update.version.equals(promptedUpdateVersion)) return;
                promptedUpdateVersion = update.version;
                showUpdatePrompt(update);
            }

            @Override public void onError(String message) {
                checkingForUpdate = false;
            }
        });
    }

    private void showUpdatePrompt(AppUpdate update) {
        if (isFinishing() || isDestroyed()) return;
        String size = update.size > 0 ? "\n安装包大小：" + formatSize(update.size) : "";
        new MaterialAlertDialogBuilder(this)
                .setTitle("发现新版本 " + update.version)
                .setMessage("可以下载并覆盖安装最新版连线。" + size)
                .setNegativeButton("稍后", null)
                .setPositiveButton("立即更新", (dialog, which) -> prepareUpdate(update))
                .show();
    }

    private void prepareUpdate(AppUpdate update) {
        pendingUpdate = update;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            installPermission.launch(settings);
            return;
        }
        downloadPendingUpdate();
    }

    private void downloadPendingUpdate() {
        AppUpdate update = pendingUpdate;
        if (update == null) return;
        pendingUpdate = null;
        String filename = update.filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".apk")) filename += ".apk";

        DownloadManager.Request request = new DownloadManager.Request(
                Uri.parse(app.api().absoluteAppUrl(update.url)))
                .setTitle("连线 " + update.version)
                .setDescription("正在下载安装包")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        File downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir != null) {
            File previous = new File(downloadDir, filename);
            if (previous.exists() && !previous.delete()) {
                Toast.makeText(this, "无法替换旧安装包", Toast.LENGTH_LONG).show();
                return;
            }
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename);
        }

        try {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            long downloadId = manager.enqueue(request);
            app.session().saveUpdateDownload(downloadId);
            Toast.makeText(this, "新版本开始下载", Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法开始下载，请稍后重试", Toast.LENGTH_LONG).show();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024L) + " KB";
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024d / 1024d);
    }

    private void setupProfile() {
        Models.User user = app.session().user();
        String initials = Ui.initials(user.displayName);
        binding.headerAvatar.setText(initials);
        binding.profileAvatar.setText(initials);
        binding.profileName.setText(user.displayName);
        binding.profileUsername.setText("@" + user.username);
        binding.profileServer.setText(app.api().serverUrl());
        binding.screenSubtitle.setText("@" + user.username);
    }

    private void setupLists() {
        conversationAdapter = new ConversationAdapter(this::openConversation);
        binding.conversationList.setLayoutManager(new LinearLayoutManager(this));
        binding.conversationList.setAdapter(conversationAdapter);
        contactAdapter = new ContactAdapter(this::startConversation);
        binding.contactList.setLayoutManager(new LinearLayoutManager(this));
        binding.contactList.setAdapter(contactAdapter);
    }

    private void setupNavigation() {
        binding.navChats.setOnClickListener(view -> selectTab(0));
        binding.navContacts.setOnClickListener(view -> selectTab(1));
        binding.navProfile.setOnClickListener(view -> selectTab(2));
    }

    private void selectTab(int tab) {
        selectedTab = tab;
        binding.chatsPanel.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        binding.contactsPanel.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        binding.profilePanel.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        binding.screenTitle.setText(tab == 0 ? "消息" : tab == 1 ? "联系人" : "我的");
        binding.screenSubtitle.setVisibility(tab == 2 ? View.GONE : View.VISIBLE);
        if (tab == 1) binding.screenSubtitle.setText("查找成员");
        else if (tab == 0) binding.screenSubtitle.setText("@" + app.session().user().username);
        styleNav(binding.navChats, tab == 0);
        styleNav(binding.navContacts, tab == 1);
        styleNav(binding.navProfile, tab == 2);
        if (tab == 0) loadConversations();
        if (tab == 1) binding.contactSearch.requestFocus();
    }

    private void styleNav(TextView view, boolean selected) {
        view.setTextColor(ContextCompat.getColor(this, selected ? R.color.teal : R.color.muted));
        view.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void loadConversations() {
        app.api().conversations(new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                JSONArray array = body.optJSONArray("conversations");
                List<Models.Conversation> result = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item != null && item.optJSONObject("peer") != null) result.add(Models.Conversation.from(item));
                    }
                }
                conversationAdapter.submit(result);
                binding.conversationEmpty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                binding.conversationList.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void scheduleSearch(String value) {
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        String query = value.trim();
        if (query.isEmpty()) {
            contactAdapter.submit(new ArrayList<>());
            binding.contactEmpty.setText("输入账号或昵称查找联系人");
            binding.contactEmpty.setVisibility(View.VISIBLE);
            return;
        }
        pendingSearch = () -> searchContacts(query);
        handler.postDelayed(pendingSearch, 250);
    }

    private void searchContacts(String query) {
        app.api().searchUsers(query, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                JSONArray array = body.optJSONArray("users");
                List<Models.User> users = new ArrayList<>();
                if (array != null) for (int i = 0; i < array.length(); i++) users.add(Models.User.from(array.optJSONObject(i)));
                contactAdapter.submit(users);
                binding.contactEmpty.setText("没有找到联系人");
                binding.contactEmpty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
                binding.contactList.setVisibility(users.isEmpty() ? View.GONE : View.VISIBLE);
            }

            @Override public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startConversation(Models.User user) {
        app.api().directConversation(user.id, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                JSONObject conversation = body.optJSONObject("conversation");
                openChat(conversation.optString("id"), user);
            }

            @Override public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openConversation(Models.Conversation conversation) {
        openChat(conversation.id, conversation.peer);
    }

    private void openChat(String conversationId, Models.User peer) {
        Intent intent = new Intent(this, ChatActivity.class);
        putPeer(intent, conversationId, peer);
        startActivity(intent);
    }

    static void putPeer(Intent intent, String conversationId, Models.User peer) {
        intent.putExtra("conversationId", conversationId);
        intent.putExtra("peerId", peer.id);
        intent.putExtra("peerName", peer.displayName);
        intent.putExtra("peerUsername", peer.username);
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("退出登录")
                .setMessage("退出后需要重新输入账号和密码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (dialog, which) -> {
                    app.realtime().disconnect();
                    app.session().clear();
                    returnToLogin();
                }).show();
    }

    private void returnToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override public void onRealtimeEvent(String event, JSONObject data) {
        if ("message:new".equals(event) || "conversation:cleared".equals(event)
                || "conversation:hidden".equals(event)) loadConversations();
    }
}
