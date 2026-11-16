package com.vkyra.code.update;

import com.vkyra.code.R;

import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.File;

/** Shared page-owned update presentation. Work is cancelled when its page is destroyed. */
public final class UpdateUi {
    private UpdateUi() {}

    public static void check(FragmentActivity activity, LifecycleOwner owner) {
        if (!VersionChecker.isConfigured()) {
            Toast.makeText(activity, "当前版本暂未启用在线更新", Toast.LENGTH_LONG).show();
            return;
        }
        VersionChecker checker = new VersionChecker(activity);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("检查更新")
                .setMessage("正在检查最新版本…").setCancelable(false)
                .setNegativeButton("取消", (ignored, which) -> checker.cancel()).create();
        DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
            @Override public void onDestroy(LifecycleOwner ignored) { checker.close(); dialog.dismiss(); }
        };
        owner.getLifecycle().addObserver(observer);
        dialog.setOnDismissListener(ignored -> {
            checker.cancel();
            owner.getLifecycle().removeObserver(observer);
        });
        dialog.show();
        checker.checkVersion(new VersionChecker.VersionCheckCallback() {
            @Override public void onVersionCheckComplete(boolean newer, VersionChecker.ReleaseInfo info, String current) {
                dialog.dismiss();
                checker.close();
                if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) return;
                if (info == null) Toast.makeText(activity, "暂无适用于当前设备的稳定版本", Toast.LENGTH_LONG).show();
                else UpdateDialogHelper.showUpdateDialog(activity, info, current, newer,
                        () -> download(activity, owner, info.downloadUrl));
            }
            @Override public void onVersionCheckError(String error) {
                dialog.dismiss(); checker.close();
                if (owner.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED) error(activity, error);
            }
        });
    }

    public static void download(FragmentActivity activity, LifecycleOwner owner, String url) {
        ApkDownloadManager manager = new ApkDownloadManager(activity);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        LinearProgressIndicator indicator = new LinearProgressIndicator(activity);
        indicator.setIndeterminate(true);
        layout.addView(indicator, new LinearLayout.LayoutParams(-1, -2));
        TextView progress = new TextView(activity);
        progress.setText("下载中…");
        layout.addView(progress);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle("下载更新")
                .setView(layout).setCancelable(false)
                .setNegativeButton("取消", (ignored, which) -> manager.cancel()).create();
        DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
            @Override public void onDestroy(LifecycleOwner ignored) { manager.close(); dialog.dismiss(); }
        };
        owner.getLifecycle().addObserver(observer);
        dialog.show();
        manager.downloadApk(url, new ApkDownloadManager.DownloadCallback() {
            private boolean alive() { return owner.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED; }
            @Override public void onProgress(int percent, long downloaded, long total) {
                if (!alive()) return;
                indicator.setIndeterminate(false);
                indicator.setProgressCompat(percent, true);
                progress.setText(activity.getString(R.string.update_download_progress, percent));
            }
            @Override public void onDownloadComplete(File file) {
                dialog.dismiss();
                if (alive()) manager.installApk(file);
            }
            @Override public void onDownloadError(String message) { failed(message); }
            @Override public void onInstallError(String message) { failed(message); }
            private void failed(String message) {
                dialog.dismiss(); manager.close();
                owner.getLifecycle().removeObserver(observer);
                if (alive()) error(activity, message);
            }
        });
    }

    private static void error(FragmentActivity activity, String message) {
        new MaterialAlertDialogBuilder(activity).setTitle("更新失败").setMessage(message)
                .setPositiveButton("确定", null).show();
    }
}
