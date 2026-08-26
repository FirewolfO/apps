package top.lxvb.yuque;

import org.json.JSONObject;

final class Models {
    private Models() {}

    static final class User {
        final String id;
        final String username;
        final String displayName;
        final String avatarUrl;
        final boolean admin;

        User(String id, String username, String displayName, String avatarUrl, boolean admin) {
            this.id = id;
            this.username = username;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
            this.admin = admin;
        }

        static User from(JSONObject object) {
            return new User(
                    object.optString("id"), object.optString("username"),
                    object.optString("displayName"), object.optString("avatarUrl"),
                    object.optBoolean("isAdmin"));
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id).put("username", username).put("displayName", displayName)
                        .put("avatarUrl", avatarUrl).put("isAdmin", admin);
            } catch (Exception ignored) {}
            return object;
        }
    }

    static final class Conversation {
        final String id;
        final User peer;
        final int unreadCount;
        final String lastType;
        final String lastContent;
        final long lastTime;

        Conversation(String id, User peer, int unreadCount, String lastType, String lastContent, long lastTime) {
            this.id = id;
            this.peer = peer;
            this.unreadCount = unreadCount;
            this.lastType = lastType;
            this.lastContent = lastContent;
            this.lastTime = lastTime;
        }

        static Conversation from(JSONObject object) {
            JSONObject last = object.optJSONObject("lastMessage");
            return new Conversation(
                    object.optString("id"), User.from(object.optJSONObject("peer")),
                    object.optInt("unreadCount"), last == null ? "" : last.optString("type"),
                    last == null ? "" : last.optString("content"),
                    last == null ? object.optLong("createdAt") : last.optLong("createdAt"));
        }
    }

    static final class Message {
        static final String SENDING = "sending";
        static final String SENT = "sent";
        static final String FAILED = "failed";
        static final String UPLOADING = "uploading";
        static final String RECEIVING = "receiving";

        final String id;
        final String conversationId;
        final String senderId;
        final String senderName;
        final String clientId;
        final String type;
        final String content;
        final String mediaUrl;
        final String mimeType;
        final long durationMs;
        final long createdAt;
        final boolean readByPeer;
        final String deliveryState;
        final int progress;

        Message(String id, String conversationId, String senderId, String senderName,
                String clientId, String type, String content, String mediaUrl,
                String mimeType, long durationMs, long createdAt,
                boolean readByPeer, String deliveryState, int progress) {
            this.id = id;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.senderName = senderName;
            this.clientId = clientId;
            this.type = type;
            this.content = content;
            this.mediaUrl = mediaUrl;
            this.mimeType = mimeType;
            this.durationMs = durationMs;
            this.createdAt = createdAt;
            this.readByPeer = readByPeer;
            this.deliveryState = deliveryState;
            this.progress = progress;
        }

        static Message from(JSONObject object) {
            return new Message(
                    object.optString("id"), object.optString("conversationId"),
                    object.optString("senderId"), object.optString("senderName"),
                    object.optString("clientId"), object.optString("type"),
                    object.optString("content"), object.optString("mediaUrl"),
                    object.optString("mimeType"), object.optLong("durationMs"),
                    object.optLong("createdAt"), object.optBoolean("readByPeer"), SENT, 100);
        }

        static Message pending(String conversationId, User sender, String clientId,
                               String type, String content, String mediaUrl, String mimeType) {
            return new Message(
                    "local-" + clientId, conversationId, sender.id, sender.displayName,
                    clientId, type, content, mediaUrl, mimeType, 0,
                    System.currentTimeMillis(), false, SENDING, 100);
        }

        static Message uploading(String conversationId, User sender, String clientId,
                                 String type, String localUri, String mimeType) {
            return new Message(
                    "local-" + clientId, conversationId, sender.id, sender.displayName,
                    clientId, type, "", localUri, mimeType, 0,
                    System.currentTimeMillis(), false, UPLOADING, 0);
        }

        static Message receiving(JSONObject object) {
            String clientId = object.optString("clientId");
            return new Message(
                    "receiving-" + clientId, object.optString("conversationId"),
                    object.optString("senderId"), object.optString("senderName"), clientId,
                    object.optString("type", "image"), "", "", object.optString("mimeType"),
                    0, object.optLong("createdAt", System.currentTimeMillis()), false, RECEIVING, 0);
        }

        Message withDeliveryState(String state) {
            return new Message(id, conversationId, senderId, senderName, clientId, type,
                    content, mediaUrl, mimeType, durationMs, createdAt, readByPeer, state, progress);
        }

        Message withProgress(int value) {
            return new Message(id, conversationId, senderId, senderName, clientId, type,
                    content, mediaUrl, mimeType, durationMs, createdAt, readByPeer,
                    deliveryState, Math.max(0, Math.min(100, value)));
        }

        Message readyToSend(String uploadedUrl, String uploadedMimeType, String uploadedType) {
            return new Message(id, conversationId, senderId, senderName, clientId, uploadedType,
                    content, uploadedUrl, uploadedMimeType, durationMs, createdAt,
                    readByPeer, SENDING, 100);
        }

        Message asRead() {
            return new Message(id, conversationId, senderId, senderName, clientId, type,
                    content, mediaUrl, mimeType, durationMs, createdAt, true, deliveryState, progress);
        }
    }
}
