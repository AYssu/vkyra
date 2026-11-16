package com.vkyra.code.installer;

import com.vkyra.code.R;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** One progress UI with lifecycle cancellation for all toolchain installers. */
public final class InstallerDialogs {
    private InstallerDialogs() { }

    public static void show(Context context, String title, InstallJob.Work work, Runnable onSuccess) {
        Activity activity = InstallJobs.activity(context);
        if (activity != null && (activity.isFinishing() || activity.isDestroyed())) return;
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * context.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        layout.addView(progress);
        TextView status = new TextView(context);
        status.setText("准备安装…");
        layout.addView(status);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle(title).setView(layout)
                .setCancelable(false).setNegativeButton("取消", null).create();
        dialog.show();
        InstallJob job = InstallJobs.start(context, work, new InstallJob.Listener() {
            private boolean alive() { return activity == null || (!activity.isFinishing() && !activity.isDestroyed()); }
            @Override public void progress(int current, int total, String name) {
                if (alive()) status.setText("处理中：" + current + " · " + name);
            }
            @Override public void success(int files) {
                dialog.dismiss();
                if (alive() && onSuccess != null) onSuccess.run();
            }
            @Override public void error(String message) {
                dialog.dismiss();
                if (alive()) new MaterialAlertDialogBuilder(context).setTitle("安装失败")
                        .setMessage(message).setPositiveButton("确定", null).show();
            }
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> { job.cancel(); dialog.dismiss(); });
        if (activity instanceof LifecycleOwner) {
            LifecycleOwner owner = (LifecycleOwner) activity;
            DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
                @Override public void onDestroy(LifecycleOwner source) { job.cancel(); dialog.dismiss(); source.getLifecycle().removeObserver(this); }
            };
            owner.getLifecycle().addObserver(observer);
            dialog.setOnDismissListener(ignored -> owner.getLifecycle().removeObserver(observer));
        }
    }
}
