package top.lxvb.yuque;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class RealtimeClient {
    interface Listener {
        void onRealtimeEvent(String event, JSONObject data);
    }

    private final OkHttpClient client;
    private final ApiClient api;
    private final SessionStore session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final List<String> pending = new ArrayList<>();
    private WebSocket socket;
    private boolean open;
    private boolean stopped = true;
    private int reconnectAttempt;

    RealtimeClient(ApiClient api, SessionStore session) {
        this.api = api;
        this.client = api.httpClient();
        this.session = session;
    }

    void addListener(Listener listener) { listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }

    synchronized void connect() {
        String token = session.token();
        if (token.isEmpty()) return;
        stopped = false;
        if (socket != null) return;
        String wsUrl = api.serverUrl().replaceFirst("^http", "ws") + "/ws?token=" + UriCodec.encode(token);
        socket = client.newWebSocket(new Request.Builder().url(wsUrl).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                synchronized (RealtimeClient.this) {
                    if (socket != webSocket) return;
                    open = true;
                    reconnectAttempt = 0;
                    for (String message : pending) webSocket.send(message);
                    pending.clear();
                }
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject envelope = new JSONObject(text);
                    String event = envelope.optString("event");
                    JSONObject data = envelope.optJSONObject("data");
                    main.post(() -> {
                        for (Listener listener : listeners) listener.onRealtimeEvent(event, data == null ? new JSONObject() : data);
                    });
                } catch (Exception ignored) {}
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                reconnect(webSocket);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (response != null && response.code() == 401) {
                    synchronized (RealtimeClient.this) {
                        stopped = true;
                        socket = null;
                        open = false;
                        pending.clear();
                    }
                    main.post(() -> {
                        for (Listener listener : listeners) listener.onRealtimeEvent("auth:expired", new JSONObject());
                    });
                    return;
                }
                reconnect(webSocket);
            }
        });
    }

    private synchronized void reconnect(WebSocket closed) {
        if (socket != closed) return;
        socket = null;
        open = false;
        if (!stopped && !session.token().isEmpty()) {
            long delay = Math.min(3000, 350L << Math.min(reconnectAttempt++, 3));
            main.postDelayed(this::connect, delay);
        }
    }

    synchronized void disconnect() {
        stopped = true;
        if (socket != null) socket.close(1000, "logout");
        socket = null;
        open = false;
        reconnectAttempt = 0;
        pending.clear();
    }

    synchronized void send(String event, JSONObject data) {
        JSONObject envelope = new JSONObject();
        try {
            envelope.put("event", event).put("data", data);
            String message = envelope.toString();
            if (socket != null && open) socket.send(message);
            else {
                pending.add(message);
                connect();
            }
        } catch (Exception ignored) {}
    }

    synchronized boolean sendNow(String event, JSONObject data) {
        if (socket == null || !open) {
            connect();
            return false;
        }
        JSONObject envelope = new JSONObject();
        try {
            envelope.put("event", event).put("data", data);
            return socket.send(envelope.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    synchronized boolean isOpen() {
        return open && socket != null;
    }

    private static final class UriCodec {
        static String encode(String value) {
            return android.net.Uri.encode(value);
        }
    }
}
