package com.vkyra.code.update;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Owned download/install work. Closing releases callbacks and cancels network and verification work. */
public class ApkDownloadManager implements AutoCloseable {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).followSslRedirects(false).build();
    private static final ExecutorService WORKERS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { Thread thread = new Thread(runnable, "vkyra-update"); thread.setDaemon(true); return thread; });
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private volatile DownloadCallback callback;
    private volatile Call call;
    private volatile Future<?> task;
    private volatile boolean downloading;
    private volatile boolean installing;
    private volatile boolean closed;

    public ApkDownloadManager(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void downloadApk(String url, DownloadCallback callback) {
        if (closed || downloading || installing) return;
        cancel();
        this.callback = callback;
        long token = generation.get();
        if (!UpdateReleaseSelector.isSecureUrl(url)) {
            post(token, target -> target.onDownloadError("更新下载地址必须使用 HTTPS")); return;
        }
        downloading = true;
        Call operation = HTTP.newCall(new Request.Builder().url(url).build());
        call = operation;
        try {
            task = WORKERS.submit(() -> {
                File destination = new File(new File(context.getCacheDir(), "updates"), "vkyra-" + UUID.randomUUID() + ".apk");
                try {
                    TransactionalApkDownloader.download(operation, destination,
                            () -> closed || generation.get() != token,
                            file -> ApkUpdateVerifier.verify(context, file),
                            (percent, downloaded, total) -> post(token, target -> target.onProgress(percent, downloaded, total)));
                    synchronized (ApkDownloadManager.this) {
                        if (closed || token != generation.get()) { destination.delete(); return; }
                        downloading = false;
                        call = null;
                    }
                    post(token, target -> target.onDownloadComplete(destination));
                } catch (Exception error) {
                    synchronized (ApkDownloadManager.this) { if (token == generation.get()) { downloading = false; call = null; } }
                    post(token, target -> target.onDownloadError("下载或安装包校验失败，请重试并确认发布签名一致"));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            downloading = false;
            call = null;
            post(token, target -> target.onDownloadError("更新任务繁忙，请稍后重试"));
        }
    }

    /** Verification runs again immediately before handing this private immutable copy to Android. */
    public synchronized void installApk(File apkFile) {
        if (closed || downloading || installing) return;
        installing = true;
        long token = generation.get();
        try {
            task = WORKERS.submit(() -> {
                File verified = null;
                try {
                    if (!apkFile.isFile() || apkFile.length() > TransactionalApkDownloader.MAX_APK_BYTES)
                        throw new java.io.IOException("无效安装包");
                    File directory = new File(context.getCacheDir(), "updates");
                    java.nio.file.Files.createDirectories(directory.toPath());
                    verified = File.createTempFile("vkyra-install-", ".apk", directory);
                    java.nio.file.Files.copy(apkFile.toPath(), verified.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    ApkUpdateVerifier.verify(context, verified);
                    File installFile = verified;
                    main.post(() -> {
                        synchronized (ApkDownloadManager.this) {
                            if (closed || token != generation.get()) { installFile.delete(); return; }
                            installing = false;
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(
                                        FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", installFile),
                                        "application/vnd.android.package-archive");
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                context.startActivity(intent);
                            } catch (RuntimeException error) {
                                installFile.delete();
                                if (callback != null) callback.onInstallError("无法启动安装程序，请检查安装权限");
                            }
                        }
                    });
                } catch (Exception error) {
                    if (verified != null) verified.delete();
                    synchronized (ApkDownloadManager.this) { if (token == generation.get()) installing = false; }
                    post(token, target -> target.onInstallError("安装包校验失败，包名、签名或版本不符合更新要求"));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            installing = false;
            post(token, target -> target.onInstallError("更新任务繁忙，请稍后重试"));
        }
    }

    private interface Delivery { void run(DownloadCallback callback); }
    private void post(long token, Delivery delivery) {
        main.post(() -> { synchronized (ApkDownloadManager.this) {
            if (!closed && token == generation.get() && callback != null) delivery.run(callback);
        } });
    }
    public boolean isDownloading() { return downloading; }
    public synchronized void cancel() {
        generation.incrementAndGet();
        callback = null;
        downloading = false;
        installing = false;
        if (call != null) call.cancel();
        call = null;
        if (task != null) task.cancel(true);
        task = null;
    }
    @Override public synchronized void close() { closed = true; cancel(); }

    public interface DownloadCallback {
        void onProgress(int progress, long downloadedSize, long totalSize);
        void onDownloadComplete(File apkFile);
        void onDownloadError(String error);
        void onInstallError(String error);
    }
}
