package top.lxvb.yuque;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

public final class YuqueApp extends Application implements RealtimeClient.Listener, ApiClient.AuthListener, Application.ActivityLifecycleCallbacks {
    private static final String INCOMING_CHANNEL = "incoming_calls";
    private static final String MESSAGE_CHANNEL = "messages_v1";
    private SessionStore session;
    private ApiClient api;
    private RealtimeClient realtime;
    private Activity resumedActivity;

    @Override public void onCreate() {
        super.onCreate();
        session = new SessionStore(this);
        api = new ApiClient(session);
        realtime = new RealtimeClient(api, session);
        api.setAuthListener(this);
        realtime.addListener(this);
        registerActivityLifecycleCallbacks(this);
        createNotificationChannels();
    }

    SessionStore session() { return session; }
    ApiClient api() { return api; }
    RealtimeClient realtime() { return realtime; }

    @Override public void onRealtimeEvent(String event, JSONObject data) {
        if ("auth:expired".equals(event)) {
            onSessionExpired();
            return;
        }
        if ("message:new".equals(event)) {
            showMessageNotification(data);
            return;
        }
        if ("call:end".equals(event)) {
            cancelIncoming(data.optString("callId"));
            return;
        }
        if (!"call:start".equals(event)) return;
        Models.User caller = new Models.User(
                data.optString("fromUserId"), data.optString("fromUsername"),
                data.optString("fromUserName", "来电"), "", false);
        String conversationId = data.optString("conversationId");
        String callId = data.optString("callId");
        String type = data.optString("type", "audio");
        Activity activity = resumedActivity;
        if (activity != null && !(activity instanceof CallActivity)) {
            CallActivity.openIncoming(activity, conversationId, caller, callId, type);
        } else if (activity instanceof CallActivity) {
            JSONObject busy = new JSONObject();
            try { busy.put("conversationId", conversationId).put("callId", callId).put("reason", "busy"); } catch (Exception ignored) {}
            realtime.send("call:end", busy);
        } else {
            showIncomingNotification(conversationId, caller, callId, type);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel incoming = new NotificationChannel(
                INCOMING_CHANNEL, "来电", NotificationManager.IMPORTANCE_HIGH);
        incoming.setDescription("语音和视频来电提醒");
        incoming.enableVibration(true);
        NotificationChannel messages = new NotificationChannel(
                MESSAGE_CHANNEL, "新消息", NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("聊天消息提醒");
        messages.enableVibration(true);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(incoming);
        manager.createNotificationChannel(messages);
    }

    private void showMessageNotification(JSONObject message) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        String conversationId = message.optString("conversationId");
        Activity activity = resumedActivity;
        if (activity instanceof ChatActivity && ((ChatActivity) activity).isConversation(conversationId)) return;

        String type = message.optString("type");
        String content;
        if ("image".equals(type)) content = "[图片]";
        else if ("video".equals(type)) content = "[视频]";
        else content = message.optString("content", "新消息");
        String sender = message.optString("senderName", "新消息");
        String messageId = message.optString("id", String.valueOf(System.currentTimeMillis()));

        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int id = 100000 + Math.abs(messageId.hashCode() % 100000);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MESSAGE_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(sender)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        getSystemService(NotificationManager.class).notify(id, builder.build());
    }

    private void showIncomingNotification(String conversationId, Models.User caller, String callId, String type) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(this, CallActivity.class);
        MainActivity.putPeer(intent, conversationId, caller);
        intent.putExtra("callType", type).putExtra("incoming", true).putExtra("callId", callId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId(callId), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, INCOMING_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(caller.displayName)
                .setContentText("video".equals(type) ? "视频来电" : "语音来电")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        getSystemService(NotificationManager.class).notify(notificationId(callId), builder.build());
    }

    void cancelIncoming(String callId) {
        getSystemService(NotificationManager.class).cancel(notificationId(callId));
    }

    private int notificationId(String callId) { return 5000 + Math.abs(callId.hashCode() % 100000); }

    @Override public void onSessionExpired() {
        if (session.token().isEmpty()) return;
        session.clear();
        realtime.disconnect();
        Activity activity = resumedActivity;
        if (activity != null && !(activity instanceof LoginActivity)) {
            Intent intent = new Intent(activity, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
        }
    }

    @Override public void onActivityResumed(@NonNull Activity activity) { resumedActivity = activity; }
    @Override public void onActivityPaused(@NonNull Activity activity) { if (resumedActivity == activity) resumedActivity = null; }
    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}
    @Override public void onActivityStarted(@NonNull Activity activity) {}
    @Override public void onActivityStopped(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {}
    @Override public void onActivityDestroyed(@NonNull Activity activity) {}
}
