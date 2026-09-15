package com.firewolf.friendsspeaking;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

public final class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        SharedPreferences preferences = context.getSharedPreferences(AppUpdateChecker.PREFERENCES, Context.MODE_PRIVATE);
        long expected = preferences.getLong(AppUpdateChecker.DOWNLOAD_ID, -1L);
        long received = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (expected < 0 || expected != received) return;
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        int status = DownloadManager.STATUS_FAILED;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(received))) {
            if (cursor.moveToFirst()) status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        }
        preferences.edit().remove(AppUpdateChecker.DOWNLOAD_ID).apply();
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(context, "新版本下载失败，请稍后重试", Toast.LENGTH_LONG).show();
            return;
        }
        Uri apk = manager.getUriForDownloadedFile(received);
        if (apk == null) {
            Toast.makeText(context, "无法打开安装包", Toast.LENGTH_LONG).show();
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apk, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(install);
        } catch (RuntimeException error) {
            Toast.makeText(context, "请点击下载完成通知打开安装包", Toast.LENGTH_LONG).show();
        }
    }
}
