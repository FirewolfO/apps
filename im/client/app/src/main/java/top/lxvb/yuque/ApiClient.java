package top.lxvb.yuque;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

final class ApiClient {
    interface AuthListener { void onSessionExpired(); }

    interface JsonCallback {
        void onSuccess(JSONObject body);
        void onError(String message);
    }

    interface EndpointCallback {
        void onSuccess(String serverUrl);
        void onError(String message);
    }

    interface UploadCallback extends JsonCallback {
        void onProgress(int percent);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client;
    private final OkHttpClient discoveryClient;
    private final SessionStore session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String serverUrl;
    private AuthListener authListener;

    ApiClient(SessionStore session) {
        this.session = session;
        String savedServerUrl = session.serverUrl();
        if (BuildConfig.DISCOVERY_HOST.isEmpty()) {
            serverUrl = BuildConfig.SERVER_URL;
            if (!serverUrl.equals(savedServerUrl)) session.saveServerUrl(serverUrl);
        } else {
            serverUrl = savedServerUrl.isEmpty() ? BuildConfig.SERVER_URL : savedServerUrl;
        }
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        discoveryClient = client.newBuilder()
                .callTimeout(4, TimeUnit.SECONDS)
                .build();
    }

    OkHttpClient httpClient() {
        return client;
    }

    String serverUrl() {
        return serverUrl;
    }

    void resolveEndpoint(EndpointCallback callback) {
        if (BuildConfig.DISCOVERY_HOST.isEmpty()) {
            callback.onSuccess(serverUrl);
            return;
        }
        String discoveryUrl = "https://cloudflare-dns.com/dns-query?name="
                + Uri.encode(BuildConfig.DISCOVERY_HOST) + "&type=TXT";
        Request request = new Request.Builder()
                .url(discoveryUrl)
                .header("Accept", "application/dns-json")
                .get()
                .build();
        discoveryClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                deliverDiscoveryFallback(callback);
            }

            @Override public void onResponse(Call call, Response response) {
                try (response; ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful() || responseBody == null) {
                        deliverDiscoveryFallback(callback);
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody.string());
                    JSONArray answers = json.optJSONArray("Answer");
                    String resolved = "";
                    if (answers != null) {
                        for (int i = 0; i < answers.length(); i++) {
                            JSONObject answer = answers.optJSONObject(i);
                            if (answer == null || answer.optInt("type") != 16) continue;
                            resolved = normalizeDiscoveryAnswer(answer.optString("data"));
                            if (!resolved.isEmpty()) break;
                        }
                    }
                    if (resolved.isEmpty()) {
                        deliverDiscoveryFallback(callback);
                        return;
                    }
                    serverUrl = resolved;
                    session.saveServerUrl(resolved);
                    String finalResolved = resolved;
                    main.post(() -> callback.onSuccess(finalResolved));
                } catch (Exception error) {
                    deliverDiscoveryFallback(callback);
                }
            }
        });
    }

    private void deliverDiscoveryFallback(EndpointCallback callback) {
        String saved = session.serverUrl();
        String fallback = saved.isEmpty() ? BuildConfig.SERVER_URL : saved;
        main.post(() -> {
            if (!fallback.isEmpty()) {
                serverUrl = fallback;
                callback.onSuccess(fallback);
            } else {
                callback.onError("无法获取服务器地址，请检查网络后重试");
            }
        });
    }

    static String normalizeDiscoveryAnswer(String answer) {
        String value = answer == null ? "" : answer.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null
                    || !host.endsWith(".trycloudflare.com") || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) return "";
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) return "";
            return "https://" + host;
        } catch (Exception ignored) {
            return "";
        }
    }

    void setAuthListener(AuthListener listener) { authListener = listener; }

    void login(String username, String password, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try { body.put("username", username).put("password", password); } catch (Exception ignored) {}
        request("POST", "/api/auth/login", body, false, callback);
    }

    void conversations(JsonCallback callback) {
        request("GET", "/api/conversations", null, true, callback);
    }

    void rtcConfig(JsonCallback callback) {
        request("GET", "/api/rtc/config", null, true, callback);
    }

    void searchUsers(String query, JsonCallback callback) {
        request("GET", "/api/users?q=" + Uri.encode(query), null, true, callback);
    }

    void directConversation(String userId, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try { body.put("userId", userId); } catch (Exception ignored) {}
        request("POST", "/api/conversations/direct", body, true, callback);
    }

    void messages(String conversationId, JsonCallback callback) {
        request("GET", "/api/conversations/" + conversationId + "/messages?limit=100", null, true, callback);
    }

    void markRead(String conversationId) {
        request("POST", "/api/conversations/" + conversationId + "/read", new JSONObject(), true, new JsonCallback() {
            @Override public void onSuccess(JSONObject body) {}
            @Override public void onError(String message) {}
        });
    }

    void clearConversation(String conversationId, JsonCallback callback) {
        request("DELETE", "/api/conversations/" + conversationId + "/messages", null, true, callback);
    }

    void hideConversation(String conversationId, JsonCallback callback) {
        request("DELETE", "/api/conversations/" + conversationId, null, true, callback);
    }

    void latestApp(JsonCallback callback) {
        Request request = new Request.Builder()
                .url(BuildConfig.APP_CENTER_URL + "/api/apps/yuque/latest")
                .get()
                .build();
        execute(request, callback);
    }

    void sendMessage(String conversationId, String type, String content, String mediaUrl,
                     String mimeType, String clientId, JsonCallback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("type", type).put("content", content).put("mediaUrl", mediaUrl)
                    .put("mimeType", mimeType).put("clientId", clientId);
        } catch (Exception ignored) {}
        request("POST", "/api/conversations/" + conversationId + "/messages", body, true, callback);
    }

    void upload(ContentResolver resolver, Uri uri, UploadCallback callback) {
        String mime = resolver.getType(uri);
        if (mime == null) mime = "application/octet-stream";
        String filename = queryFilename(resolver, uri);
        RequestBody fileBody = new ContentUriBody(
                resolver, uri, MediaType.parse(mime), percent -> main.post(() -> callback.onProgress(percent)));
        RequestBody multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody).build();
        Request.Builder builder = new Request.Builder().url(serverUrl + "/api/uploads").post(multipart);
        addAuthorization(builder);
        callback.onProgress(0);
        execute(builder.build(), callback);
    }

    String absoluteMediaUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("content://")) return path;
        return serverUrl + (path.startsWith("/") ? path : "/" + path);
    }

    String absoluteAppUrl(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return BuildConfig.APP_CENTER_URL + (path.startsWith("/") ? path : "/" + path);
    }

    private void request(String method, String path, JSONObject body, boolean authorized, JsonCallback callback) {
        RequestBody requestBody = body == null ? null : RequestBody.create(body.toString(), JSON);
        Request.Builder builder = new Request.Builder().url(serverUrl + path);
        if (authorized) addAuthorization(builder);
        switch (method) {
            case "POST": builder.post(requestBody == null ? RequestBody.create(new byte[0]) : requestBody); break;
            case "PATCH": builder.patch(requestBody); break;
            case "DELETE": builder.delete(); break;
            default: builder.get();
        }
        execute(builder.build(), callback);
    }

    private void addAuthorization(Request.Builder builder) {
        String token = session.token();
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
    }

    private void execute(Request request, JsonCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                main.post(() -> callback.onError("无法连接服务器"));
            }

            @Override public void onResponse(Call call, Response response) {
                try (response; ResponseBody responseBody = response.body()) {
                    String raw = responseBody == null ? "{}" : responseBody.string();
                    JSONObject json = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                    main.post(() -> {
                        if (response.code() == 401 && authListener != null) authListener.onSessionExpired();
                        if (response.isSuccessful()) callback.onSuccess(json);
                        else callback.onError(json.optString("error", "请求失败 (" + response.code() + ")"));
                    });
                } catch (Exception error) {
                    main.post(() -> callback.onError("服务器响应格式错误"));
                }
            }
        });
    }

    private String queryFilename(ContentResolver resolver, Uri uri) {
        try (android.database.Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "media";
    }

    private static final class ContentUriBody extends RequestBody {
        private final ContentResolver resolver;
        private final Uri uri;
        private final MediaType mediaType;
        private final ProgressListener progressListener;

        ContentUriBody(ContentResolver resolver, Uri uri, MediaType mediaType, ProgressListener progressListener) {
            this.resolver = resolver;
            this.uri = uri;
            this.mediaType = mediaType;
            this.progressListener = progressListener;
        }

        @Override public MediaType contentType() { return mediaType; }

        @Override public long contentLength() {
            try (android.database.Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
            } catch (Exception ignored) {}
            return -1;
        }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            long total = contentLength();
            long written = 0;
            int lastPercent = -1;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open selected media");
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    sink.write(buffer, 0, count);
                    written += count;
                    int percent = total > 0 ? (int) Math.min(99, written * 100 / total) : 0;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progressListener.onProgress(percent);
                    }
                }
                progressListener.onProgress(100);
            }
        }
    }

    private interface ProgressListener { void onProgress(int percent); }
}
