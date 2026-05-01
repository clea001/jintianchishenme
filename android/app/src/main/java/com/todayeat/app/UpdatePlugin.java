package com.todayeat.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    @PluginMethod
    public void downloadAndInstall(PluginCall call) {
        String downloadUrl = call.getString("url");
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            call.reject("Missing download URL");
            return;
        }

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

                // Check download status
                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(downloadId);
                Cursor cursor = dm.query(query);
                if (cursor != null && cursor.moveToFirst()) {
                    int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    cursor.close();

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        installApk(context, apkFile);
                        JSObject result = new JSObject();
                        result.put("success", true);
                        call.resolve(result);
                    } else {
                        call.reject("Download failed with status: " + status);
                    }
                }

                try { context.unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
                downloadReceiver = null;
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        // Return immediately - the call will be resolved via a saved reference
        // For simplicity, we resolve immediately to indicate download started
        JSObject result = new JSObject();
        result.put("started", true);
        call.resolve(result);
    }

    private void installApk(Context context, File apkFile) {
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

        context.startActivity(intent);
    }
}
