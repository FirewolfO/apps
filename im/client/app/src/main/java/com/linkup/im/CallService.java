package com.linkup.im;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public final class CallService extends Service {
    static final String ACTION_STOP = "com.linkup.im.STOP_CALL_SERVICE";
    private static final String CHANNEL_ID = "ongoing_calls";
    private static final int NOTIFICATION_ID = 4102;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.call_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.call_channel_description));
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @SuppressLint("InlinedApi")
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean video = intent != null && intent.getBooleanExtra("video", false);
        String peerName = intent == null ? "" : intent.getStringExtra("peerName");
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle((video ? "视频通话" : "语音通话") + " · " + peerName)
                .setContentText("通话进行中")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (video) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_NOT_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
