package top.lxvb.yuque;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

public class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        SessionStore session = new SessionStore(context);
        if (downloadId < 0 || downloadId != session.updateDownloadId()) return;

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        int status = DownloadManager.STATUS_FAILED;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (cursor.moveToFirst()) {
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        }

        session.clearUpdateDownload();
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            Toast.makeText(context, "新版本下载失败，请稍后重试", Toast.LENGTH_LONG).show();
            return;
        }

        Uri apkUri = manager.getUriForDownloadedFile(downloadId);
        if (apkUri == null) {
            Toast.makeText(context, "无法打开安装包", Toast.LENGTH_LONG).show();
            return;
        }

        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(apkUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(install);
        } catch (RuntimeException error) {
            Toast.makeText(context, "无法启动安装程序", Toast.LENGTH_LONG).show();
        }
    }
}
