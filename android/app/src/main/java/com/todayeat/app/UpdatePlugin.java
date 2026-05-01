package com.todayeat.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;

@CapacitorPlugin(name = "AppUpdate")
public class UpdatePlugin extends Plugin {

    private long downloadId = -1;
    private BroadcastReceiver downloadReceiver;
    private PluginCall savedCall;

    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        String downloadUrl = call.getString("url");
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            call.reject("Missing download URL");
            return;
        }

        savedCall = call;
        Context ctx = getContext();
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);

        // Remove old file
        File apkFile = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk");
        if (apkFile.exists()) apkFile.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
        request.setTitle("今天吃什么");
        request.setDescription("正在下载更新...");
        request.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "update.apk");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setMimeType("application/vnd.android.package-archive");
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(true);

        downloadId = dm.enqueue(request);

        // Register receiver for download complete
        if (downloadReceiver != null) {
            try { ctx.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;

                try { context.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
                downloadReceiver = null;

                // Check download status
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS);
                    int status = cursor.getInt(statusIdx);

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        cursor.close();
                        // Notify JS download complete
                        notifyDownloadComplete();
                        // Install
                        installApk(context, apkFile);
                    } else {
                        cursor.close();
                        notifyDownloadFailed("Download status: " + status);
                    }
                } else {
                    if (cursor != null) cursor.close();
                    notifyDownloadFailed("Download query failed");
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        // Return immediately - JS will be notified when download completes
        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    private void notifyDownloadComplete() {
        try {
            JSObject data = new JSObject();
            data.put("complete", true);
            notifyListeners("downloadComplete", data);
        } catch (Exception ignored) {}
    }

    private void notifyDownloadFailed(String reason) {
        try {
            JSObject data = new JSObject();
            data.put("error", reason);
            notifyListeners("downloadFailed", data);
        } catch (Exception ignored) {}
    }

    private void installApk(Context context, File apkFile) {
        if (!apkFile.exists()) {
            notifyDownloadFailed("APK file not found");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Uri apkUri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apkFile);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile),
                    "application/vnd.android.package-archive");
        }

        try {
            context.startActivity(intent);
        } catch (Exception e) {
            // If install fails, open download folder
            notifyDownloadFailed("Install failed: " + e.getMessage());
            Intent viewIntent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(viewIntent);
        }
    }
}
