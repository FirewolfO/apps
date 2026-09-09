package com.firewolf.xiaolinstudy.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.firewolf.xiaolinstudy.BuildConfig;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-scoped coordinator: changing activities never cancels an in-flight upload. */
public final class ProgressSync {
    public interface Listener { void onSyncChanged(boolean recordsChanged, String message); }
    private static ProgressSync instance;
    private final SharedPreferences settings;
    private final ProgressStore full;
    private final ProgressStore compact;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable periodic = this::sync;
    private Listener listener;
    private boolean foreground;
    private boolean busy;
    private boolean syncAgain;
    private int generation;
    private int failures;
    private String error = "";

    public static synchronized ProgressSync get(Context context) {
        if (instance == null) instance = new ProgressSync(context.getApplicationContext());
        return instance;
    }
    private ProgressSync(Context context) {
        settings = context.getSharedPreferences("xiaolin_progress_sync", Context.MODE_PRIVATE);
        full = new ProgressStore(context, false);
        compact = new ProgressStore(context, true);
    }
    public synchronized String code() { return settings.getString("code", ""); }
    public synchronized boolean isBusy() { return busy; }
    public synchronized String status() {
        if (busy) return "正在同步学习进度…";
        if (code().isEmpty()) return "仅保存在本机 · 点击开启跨设备同步";
        if (!error.isEmpty()) return error + " · 本机记录已保留";
        long at = settings.getLong("last_success", 0);
        return at == 0 ? "已配对 · 等待首次同步" : "上次同步 "
                + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date(at));
    }
    public synchronized void resume(Listener target) {
        listener = target;
        foreground = true;
        sync();
    }
    public synchronized void pause() {
        foreground = false;
        listener = null;
        handler.removeCallbacks(periodic);
        sync();
    }
    public synchronized void changed() {
        if (code().isEmpty()) return;
        if (busy) { syncAgain = true; return; }
        handler.removeCallbacks(periodic);
        handler.postDelayed(periodic, 1500);
    }
    public synchronized void disconnect() {
        generation++;
        busy = false;
        syncAgain = false;
        error = "";
        failures = 0;
        settings.edit().remove("code").remove("last_success").apply();
        handler.removeCallbacks(periodic);
        notifyListener(false, "已停止本机同步，学习记录已保留");
    }

    public synchronized void create() { connect(""); }
    public synchronized void join(String rawCode) {
        String normalized = rawCode.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{32}")) {
            notifyListener(false, "请输入完整的 32 位同步码，可包含分隔横线");
            return;
        }
        connect(normalized);
    }
    private synchronized void connect(String inputCode) {
        if (busy || !code().isEmpty()) return;
        busy = true;
        error = "";
        int attempt = ++generation;
        notifyListener(false, "");
        executor.execute(() -> {
            try {
                String connected = inputCode;
                JSONObject remote;
                if (connected.isEmpty()) {
                    JSONObject created = request("/spaces", "", empty());
                    connected = created.getString("code");
                    if (!connected.matches("[a-f0-9]{32}")) throw new IOException("同步服务返回异常");
                    remote = created.getJSONObject("state");
                } else {
                    // Validate the code before touching local progress or the active pairing.
                    remote = request("/progress", connected, empty());
                }
                synchronized (this) {
                    if (attempt != generation) return;
                    merge(remote);
                    settings.edit().putString("code", connected).remove("last_success").apply();
                    busy = false;
                    notifyListener(true, "配对成功，正在合并两种版本的学习记录");
                    sync();
                }
            } catch (Exception exception) { failed(attempt, exception, true); }
        });
    }

    public synchronized void sync() {
        handler.removeCallbacks(periodic);
        if (code().isEmpty()) return;
        if (busy) { syncAgain = true; return; }
        busy = true;
        syncAgain = false;
        int attempt = generation;
        String token = code();
        notifyListener(false, "");
        executor.execute(() -> {
            try {
                JSONObject sent = snapshot();
                JSONObject received = request("/progress", token, sent);
                synchronized (this) {
                    if (attempt != generation) return;
                    boolean changed = merge(received);
                    // Snapshot again on the next round so changes made during the request are retained.
                    settings.edit().putLong("last_success", System.currentTimeMillis()).apply();
                    busy = false;
                    error = "";
                    failures = 0;
                    notifyListener(changed, "");
                    if (syncAgain) sync();
                    else if (foreground) handler.postDelayed(periodic, 30_000);
                }
            } catch (Exception exception) { failed(attempt, exception, false); }
        });
    }
    private synchronized void failed(int attempt, Exception exception, boolean showMessage) {
        if (attempt != generation) return;
        busy = false;
        failures++;
        error = exception instanceof SyncException ? exception.getMessage() : "暂未连上同步服务，稍后重试";
        notifyListener(false, showMessage ? error : "");
        if (foreground && !code().isEmpty()) handler.postDelayed(periodic, Math.min(300_000L, 15_000L << Math.min(failures, 4)));
    }
    private void notifyListener(boolean changed, String message) {
        handler.post(() -> {
            synchronized (ProgressSync.this) {
                if (listener != null) listener.onSyncChanged(changed, message);
            }
        });
    }
    private JSONObject snapshot() throws Exception {
        return new JSONObject().put("version", 1).put("full", full.snapshot()).put("compact", compact.snapshot());
    }
    private static JSONObject empty() throws Exception {
        return new JSONObject().put("version", 1).put("full", new JSONObject()).put("compact", new JSONObject());
    }
    private boolean merge(JSONObject state) throws Exception {
        if (state.getInt("version") != 1) throw new IOException("不支持的进度格式");
        JSONObject fullState = state.getJSONObject("full");
        JSONObject compactState = state.getJSONObject("compact");
        // Both modes are required; a partial/invalid response must not silently erase a mode.
        boolean a = full.merge(fullState);
        boolean b = compact.merge(compactState);
        return a || b;
    }
    private static JSONObject request(String path, String code, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.APP_CENTER_URL
                + "/api/xiaolin/sync" + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (!code.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + code);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int status = connection.getResponseCode();
            if (status == 401 || status == 404) throw new SyncException("同步码无效，请检查配对信息");
            if (status == 429) throw new SyncException("同步请求较多，请稍后重试");
            if (status == 400 || status == 413) throw new SyncException("同步记录格式或容量超限，请更新应用或联系维护者");
            if (status < 200 || status >= 300) throw new IOException("Sync HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024 + 1024) throw new IOException("同步响应过大");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    private static final class SyncException extends IOException {
        SyncException(String message) { super(message); }
    }
}
