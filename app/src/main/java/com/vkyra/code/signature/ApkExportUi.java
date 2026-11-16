package com.vkyra.code.signature;

import android.graphics.Bitmap;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.vkyra.code.sharing.ApkSharing;
import java.io.File;

/** Page-owned presentation for an immutable, cancellable export request. */
public final class ApkExportUi {
    private ApkExportUi() {}
    public static void start(FragmentActivity activity, File source, String name, String packageName,
                             int version, String versionName, Bitmap icon, String executablePath) {
        File parent = source == null ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) : source.getParentFile();
        String filename = (name + "_v" + versionName).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + ".apk";
        File destination = new File(parent, filename);
        AppInfoDialog.AppInfo info = new AppInfoDialog.AppInfo(icon, name, packageName, version, versionName);
        File executable = executablePath == null || executablePath.trim().isEmpty() ? null : new File(executablePath);
        ApkExportManager.Request request;
        try { request = new ApkExportManager.Request(source, executable, destination, info); }
        catch (RuntimeException failure) { error(activity, "无法创建导出任务，请检查源文件和图标"); return; }
        Runnable begin = () -> run(activity, request);
        if (destination.exists()) new MaterialAlertDialogBuilder(activity).setTitle("覆盖已有 APK？")
                .setMessage(destination.getAbsolutePath()).setNegativeButton("取消", null)
                .setPositiveButton("覆盖", (dialog, which) -> begin.run()).show();
        else begin.run();
    }
    private static void run(FragmentActivity activity, ApkExportManager.Request request) {
        if (activity.isDestroyed()) return;
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * activity.getResources().getDisplayMetrics().density); layout.setPadding(pad,pad,pad,pad);
        TextView message = new TextView(activity); message.setText("正在准备…"); layout.addView(message);
        LinearProgressIndicator progress = new LinearProgressIndicator(activity); progress.setMax(100); layout.addView(progress);
        final ApkExportTask[] task = new ApkExportTask[1];
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("生成 APK").setView(layout)
                .setCancelable(false).setNegativeButton("取消", (ignored,which) -> { if(task[0]!=null) task[0].cancel(); }).create();
        DefaultLifecycleObserver owner = new DefaultLifecycleObserver() {
            @Override public void onDestroy(LifecycleOwner lifecycleOwner) {
                if(task[0]!=null) task[0].cancel(); dialog.dismiss(); lifecycleOwner.getLifecycle().removeObserver(this);
            }
        };
        activity.getLifecycle().addObserver(owner);
        dialog.setOnDismissListener(ignored -> {
            if(task[0]!=null) task[0].cancel();
            activity.getLifecycle().removeObserver(owner);
        });
        dialog.show();
        task[0] = new ApkExportManager(activity).export(request, new ApkExportTask.Listener() {
            @Override public void onProgress(String text, int percent) { message.setText(text); progress.setProgressCompat(percent,true); }
            @Override public void onSuccess(File output) {
                dialog.dismiss();
                if(activity.getLifecycle().getCurrentState()==Lifecycle.State.DESTROYED) return;
                new MaterialAlertDialogBuilder(activity).setTitle("APK 已生成").setMessage(output.getAbsolutePath())
                        .setPositiveButton("安装", (ignored,which) -> ApkSharing.install(activity,output))
                        .setNegativeButton("完成",null).show();
            }
            @Override public void onError(String text) { dialog.dismiss(); if(!activity.isDestroyed()) error(activity,text); }
        });
    }
    private static void error(FragmentActivity activity,String text) {
        new MaterialAlertDialogBuilder(activity).setTitle("导出失败").setMessage(text).setPositiveButton("确定",null).show();
    }
}
