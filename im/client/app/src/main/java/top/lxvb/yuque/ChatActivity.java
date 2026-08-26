package top.lxvb.yuque;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import top.lxvb.yuque.databinding.ActivityChatBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChatActivity extends AppCompatActivity implements RealtimeClient.Listener {
    private static final long ACK_TIMEOUT_MS = 8000;
    private static final List<String> EMOJIS = Arrays.asList(
            "😀", "😄", "😂", "😊", "😍", "🥰", "😎", "🤔",
            "👍", "👏", "🙌", "🤝", "💪", "🙏", "🎉", "❤️",
            "😮", "😢", "😭", "😅", "😴", "🤗", "🌹", "🔥",
            "✅", "💯", "🚀", "☕", "🎁", "📌", "💡", "👀"
    );

    private boolean active;
    private boolean syncing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopTyping = () -> sendTyping(false);
    private final Runnable messageSync = () -> {
        if (!syncing) {
            syncing = true;
            loadMessages(false, false);
        }
    };
    private ActivityChatBinding binding;
    private YuqueApp app;
    private MessageAdapter adapter;
    private String conversationId;
    private Models.User peer;
    private boolean typingSent;
    private final Map<String, Runnable> ackTimeouts = new HashMap<>();

    private final ActivityResultLauncher<PickVisualMediaRequest> mediaPicker = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), this::uploadMedia);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (YuqueApp) getApplication();
        conversationId = getIntent().getStringExtra("conversationId");
        peer = new Models.User(
                getIntent().getStringExtra("peerId"), getIntent().getStringExtra("peerUsername"),
                getIntent().getStringExtra("peerName"), "", false);
        if (conversationId == null || peer.id == null) {
            finish();
            return;
        }
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        Ui.edgeToEdge(this, binding.root);
        binding.peerName.setText(peer.displayName);
        binding.peerAvatar.setText(Ui.initials(peer.displayName));
        binding.backButton.setOnClickListener(view -> finish());
        binding.clearButton.setOnClickListener(view -> showConversationActions());
        binding.audioCallButton.setOnClickListener(view -> CallActivity.openOutgoing(this, conversationId, peer, "audio"));
        binding.videoCallButton.setOnClickListener(view -> CallActivity.openOutgoing(this, conversationId, peer, "video"));
        setupMessages();
        setupComposer();
        setupEmojiPanel();
        loadMessages(true, true);
        markConversationRead();
    }

    @Override protected void onStart() {
        super.onStart();
        app.realtime().addListener(this);
        app.realtime().connect();
        active = true;
        scheduleSync();
    }

    @Override protected void onStop() {
        sendTyping(false);
        active = false;
        handler.removeCallbacks(messageSync);
        app.realtime().removeListener(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        for (Runnable timeout : ackTimeouts.values()) handler.removeCallbacks(timeout);
        ackTimeouts.clear();
        super.onDestroy();
    }

    private void setupMessages() {
        adapter = new MessageAdapter(app.session().user().id, app.api(), this::retryMessage);
        LinearLayoutManager layout = new LinearLayoutManager(this);
        layout.setStackFromEnd(false);
        binding.messageList.setLayoutManager(layout);
        binding.messageList.setAdapter(adapter);
    }

    private void setupComposer() {
        binding.messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                binding.sendButton.setEnabled(!s.toString().trim().isEmpty());
                if (!s.toString().isEmpty()) {
                    sendTyping(true);
                    handler.removeCallbacks(stopTyping);
                    handler.postDelayed(stopTyping, 1600);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        binding.sendButton.setOnClickListener(view -> sendText());
        binding.emojiButton.setOnClickListener(view -> {
            binding.emojiPanel.setVisibility(binding.emojiPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
        binding.mediaButton.setOnClickListener(view -> mediaPicker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo.INSTANCE)
                        .build()));
    }

    private void setupEmojiPanel() {
        for (String emoji : EMOJIS) {
            TextView button = new TextView(this);
            button.setText(emoji);
            button.setTextSize(25);
            button.setGravity(android.view.Gravity.CENTER);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(40);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            button.setLayoutParams(params);
            button.setOnClickListener(view -> {
                int start = binding.messageInput.getSelectionStart();
                binding.messageInput.getText().insert(Math.max(0, start), emoji);
            });
            binding.emojiPanel.addView(button);
        }
    }

    private void loadMessages(boolean replace, boolean showErrors) {
        app.api().messages(conversationId, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                JSONArray array = body.optJSONArray("messages");
                List<Models.Message> messages = new ArrayList<>();
                if (array != null) for (int i = 0; i < array.length(); i++) messages.add(Models.Message.from(array.optJSONObject(i)));
                int added = 0;
                if (replace) adapter.submit(messages);
                else added = adapter.merge(messages);
                if (replace || added > 0) {
                    markConversationRead();
                    scrollToBottom();
                }
                finishSync();
            }

            @Override public void onError(String message) {
                if (showErrors) Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
                finishSync();
            }
        });
    }

    private void finishSync() {
        syncing = false;
        if (active) scheduleSync();
    }

    private void scheduleSync() {
        handler.removeCallbacks(messageSync);
        handler.postDelayed(messageSync, app.realtime().isOpen() ? 1500 : 600);
    }

    private void syncNow() {
        if (syncing) return;
        syncing = true;
        handler.removeCallbacks(messageSync);
        loadMessages(false, false);
    }

    private void confirmClearConversation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("清空聊天记录")
                .setMessage("将清空你和“" + peer.displayName + "”双方的全部消息、图片、视频和通话记录。此操作无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> clearConversation())
                .show();
    }

    private void showConversationActions() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(peer.displayName)
                .setItems(new String[]{"清空聊天记录", "删除会话"}, (dialog, which) -> {
                    if (which == 0) confirmClearConversation();
                    else confirmHideConversation();
                })
                .show();
    }

    private void confirmHideConversation() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除会话")
                .setMessage("会话将从你的消息列表隐藏，聊天记录不会删除，也不会影响对方。收到新消息后会自动重新出现。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> hideConversation())
                .show();
    }

    private void hideConversation() {
        binding.clearButton.setEnabled(false);
        app.api().hideConversation(conversationId, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                finish();
            }

            @Override public void onError(String message) {
                binding.clearButton.setEnabled(true);
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void clearConversation() {
        binding.clearButton.setEnabled(false);
        app.api().clearConversation(conversationId, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                binding.clearButton.setEnabled(true);
                adapter.submit(new ArrayList<>());
                Toast.makeText(ChatActivity.this, "聊天记录已清空", Toast.LENGTH_SHORT).show();
            }

            @Override public void onError(String message) {
                binding.clearButton.setEnabled(true);
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void markConversationRead() {
        JSONObject data = new JSONObject();
        try { data.put("conversationId", conversationId); } catch (Exception ignored) {}
        if (!app.realtime().sendNow("read", data)) app.api().markRead(conversationId);
    }

    private void sendText() {
        String content = String.valueOf(binding.messageInput.getText()).trim();
        if (content.isEmpty()) return;
        binding.messageInput.setText("");
        binding.emojiPanel.setVisibility(View.GONE);
        String type = EMOJIS.contains(content) ? "emoji" : "text";
        sendMessage(type, content, "", "");
    }

    private void uploadMedia(Uri uri) {
        if (uri == null) return;
        binding.mediaButton.setEnabled(false);
        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = "application/octet-stream";
        String type = mime.startsWith("video/") ? "video" : "image";
        String clientId = UUID.randomUUID().toString();
        Models.Message pending = Models.Message.uploading(
                conversationId, app.session().user(), clientId, type, uri.toString(), mime);
        adapter.append(pending);
        scrollToBottom();
        announceMedia(pending);
        uploadPending(pending, uri);
    }

    private void announceMedia(Models.Message message) {
        JSONObject data = messageData(message);
        app.realtime().sendNow("media:prepare", data);
    }

    private void uploadPending(Models.Message pending, Uri uri) {
        app.api().upload(getContentResolver(), uri, new ApiClient.UploadCallback() {
            @Override public void onProgress(int percent) {
                adapter.updateProgress(pending.clientId, percent);
            }

            @Override public void onSuccess(JSONObject body) {
                String actualMime = body.optString("mimeType", pending.mimeType);
                String type = actualMime.startsWith("video/") ? "video" : "image";
                Models.Message ready = adapter.readyToSend(
                        pending.clientId, body.optString("url"), actualMime, type);
                setUploadIdle();
                if (ready != null) transmit(ready);
            }

            @Override public void onError(String message) {
                setUploadIdle();
                adapter.markFailed(pending.clientId);
                JSONObject data = messageData(pending);
                app.realtime().sendNow("media:cancel", data);
                Toast.makeText(ChatActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void sendMessage(String type, String content, String mediaUrl, String mimeType) {
        String clientId = UUID.randomUUID().toString();
        Models.Message pending = Models.Message.pending(
                conversationId, app.session().user(), clientId, type, content, mediaUrl, mimeType);
        adapter.append(pending);
        scrollToBottom();
        transmit(pending);
    }

    private void retryMessage(Models.Message message) {
        if (("image".equals(message.type) || "video".equals(message.type))
                && message.mediaUrl.startsWith("content://")) {
            Models.Message uploading = message.withDeliveryState(Models.Message.UPLOADING).withProgress(0);
            adapter.append(uploading);
            binding.mediaButton.setEnabled(false);
            announceMedia(uploading);
            uploadPending(uploading, Uri.parse(message.mediaUrl));
            return;
        }
        adapter.markSending(message.clientId);
        transmit(message.withDeliveryState(Models.Message.SENDING));
    }

    private void transmit(Models.Message pending) {
        if (app.realtime().sendNow("message:send", messageData(pending))) {
            Runnable timeout = () -> {
                ackTimeouts.remove(pending.clientId);
                adapter.markFailed(pending.clientId);
                Toast.makeText(ChatActivity.this, "发送超时，可点击状态重试", Toast.LENGTH_LONG).show();
            };
            cancelAck(pending.clientId);
            ackTimeouts.put(pending.clientId, timeout);
            handler.postDelayed(timeout, ACK_TIMEOUT_MS);
            return;
        }
        transmitOverHttp(pending);
    }

    private void transmitOverHttp(Models.Message pending) {
        app.api().sendMessage(conversationId, pending.type, pending.content, pending.mediaUrl,
                pending.mimeType, pending.clientId, new ApiClient.JsonCallback() {
            @Override public void onSuccess(JSONObject body) {
                JSONObject message = body.optJSONObject("message");
                if (message != null) adapter.confirm(pending.clientId, Models.Message.from(message));
                scrollToBottom();
            }

            @Override public void onError(String message) {
                adapter.markFailed(pending.clientId);
                Toast.makeText(ChatActivity.this, "发送失败，可点击状态重试", Toast.LENGTH_LONG).show();
            }
        });
    }

    private JSONObject messageData(Models.Message message) {
        JSONObject data = new JSONObject();
        try {
            data.put("conversationId", conversationId)
                    .put("clientId", message.clientId)
                    .put("type", message.type)
                    .put("content", message.content)
                    .put("mediaUrl", message.mediaUrl)
                    .put("mimeType", message.mimeType)
                    .put("durationMs", message.durationMs);
        } catch (Exception ignored) {}
        return data;
    }

    private void cancelAck(String clientId) {
        Runnable timeout = ackTimeouts.remove(clientId);
        if (timeout != null) handler.removeCallbacks(timeout);
    }

    private void setUploadIdle() {
        binding.uploadStatus.setVisibility(View.GONE);
        binding.mediaButton.setEnabled(true);
    }

    private void scrollToBottom() {
        if (adapter.getItemCount() > 0) binding.messageList.scrollToPosition(adapter.getItemCount() - 1);
    }

    private void sendTyping(boolean active) {
        if (active == typingSent) return;
        typingSent = active;
        JSONObject data = new JSONObject();
        try { data.put("conversationId", conversationId).put("active", active); } catch (Exception ignored) {}
        app.realtime().send("typing", data);
    }

    @Override public void onRealtimeEvent(String event, JSONObject data) {
        if ("ready".equals(event)) {
            syncNow();
            return;
        }
        if (!conversationId.equals(data.optString("conversationId"))) return;
        if ("message:new".equals(event)) {
            adapter.append(Models.Message.from(data));
            markConversationRead();
            scrollToBottom();
        } else if ("message:ack".equals(event)) {
            String clientId = data.optString("clientId");
            cancelAck(clientId);
            adapter.confirm(clientId, Models.Message.from(data));
            scrollToBottom();
        } else if ("message:error".equals(event)) {
            String clientId = data.optString("clientId");
            cancelAck(clientId);
            adapter.markFailed(clientId);
            Toast.makeText(this, data.optString("error", "发送失败，可点击状态重试"), Toast.LENGTH_LONG).show();
        } else if ("media:prepare".equals(event)) {
            adapter.append(Models.Message.receiving(data));
            scrollToBottom();
        } else if ("media:cancel".equals(event)) {
            adapter.markReceiveFailed(data.optString("clientId"));
        } else if ("conversation:cleared".equals(event)) {
            adapter.submit(new ArrayList<>());
            if (!app.session().user().id.equals(data.optString("userId"))) {
                Toast.makeText(this, "对方已清空聊天记录", Toast.LENGTH_SHORT).show();
            }
        } else if ("conversation:hidden".equals(event)
                && app.session().user().id.equals(data.optString("userId"))) {
            finish();
        } else if ("read".equals(event) && peer.id.equals(data.optString("userId"))) {
            adapter.markRead(data.optLong("readAt", System.currentTimeMillis()));
        } else if ("typing".equals(event) && peer.id.equals(data.optString("userId"))) {
            binding.typingText.setText(data.optBoolean("active") ? "正在输入…" : "");
        }
    }

    boolean isConversation(String id) {
        return conversationId != null && conversationId.equals(id);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
