package com.vkyra.code.sharing;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Publishes a private read-only snapshot instead of granting access to project storage. */
public final class ApkSharing {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> new Thread(r, "vkyra-apk-share"));
    private ApkSharing() {}

    public static void install(FragmentActivity activity, File source) {
        final File directory = new File(activity.getCacheDir(), "shared");
        AtomicBoolean cancelled = new AtomicBoolean();
        final Future<?>[] work = new Future<?>[1];
        DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
            @Override public void onDestroy(LifecycleOwner owner) {
                cancelled.set(true);
                if (work[0] != null) work[0].cancel(true);
                owner.getLifecycle().removeObserver(this);
            }
        };
        activity.getLifecycle().addObserver(observer);
        try {
            work[0] = WORKERS.submit(() -> {
                File copy = null;
                try {
                    Files.createDirectories(directory.toPath());
                    copy = File.createTempFile("vkyra-install-", ".apk", directory);
                    try (InputStream input = Files.newInputStream(source.toPath()); OutputStream output = Files.newOutputStream(copy.toPath())) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                            output.write(buffer, 0, count);
                        }
                    }
                    File result = copy;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        activity.getLifecycle().removeObserver(observer);
                        if (cancelled.get() || activity.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                            result.delete(); return;
                        }
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(
                                    FileProvider.getUriForFile(activity, activity.getPackageName()+".fileprovider", result),
                                    "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            activity.startActivity(intent);
                        } catch (RuntimeException failure) {
                            result.delete();
                            Toast.makeText(activity, "无法打开安装程序", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (IOException | RuntimeException failure) {
                    if (copy != null) copy.delete();
                    new Handler(Looper.getMainLooper()).post(() -> {
                        activity.getLifecycle().removeObserver(observer);
                        if (!cancelled.get()) Toast.makeText(activity, "准备安装文件失败", Toast.LENGTH_LONG).show();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            activity.getLifecycle().removeObserver(observer);
            Toast.makeText(activity, "安装任务繁忙，请稍后重试", Toast.LENGTH_LONG).show();
        }
    }
}
