package io.maru.helper;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;

public class ApkInstallReceiver extends BroadcastReceiver {
    private static final String MARU_TITLE = "Maru APK Download";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }

        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        if (downloadId == -1) return;

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) return;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        android.database.Cursor cursor = downloadManager.query(query);
        if (cursor == null) return;

        try {
            if (!cursor.moveToFirst()) return;

            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (statusIndex < 0) return;
            int status = cursor.getInt(statusIndex);
            if (status != DownloadManager.STATUS_SUCCESSFUL) return;

            /* Only handle Maru APK downloads */
            int titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);
            if (titleIndex >= 0) {
                String title = cursor.getString(titleIndex);
                if (!MARU_TITLE.equals(title)) return;
            }

            int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            if (uriIndex < 0) return;
            String localUri = cursor.getString(uriIndex);
            if (localUri == null) return;

            Uri apkUri = Uri.parse(localUri);
            File apkFile = new File(apkUri.getPath());
            if (!apkFile.exists()) return;

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri contentUri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    apkFile
                );
                installIntent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            } else {
                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            }

            context.startActivity(installIntent);
        } finally {
            cursor.close();
        }
    }
}
