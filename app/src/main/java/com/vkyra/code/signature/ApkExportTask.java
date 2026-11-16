package com.vkyra.code.signature;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** One owned export. Cancel drops page callbacks immediately; its worker owns all cleanup. */
public final class ApkExportTask implements AutoCloseable {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> { Thread t = new Thread(runnable, "vkyra-apk-export"); t.setDaemon(true); return t; });
    static { WORKERS.allowCoreThreadTimeOut(true); }
    public interface Listener {
        void onProgress(String message, int progress);
        void onSuccess(File output);
        void onError(String error);
    }
    interface Operation { File run(ExportWorkspace workspace, ExportCancellation cancellation, Progress progress) throws Exception; }
    interface Progress { void update(String message, int percent); }
    private final ExportCancellation cancellation = new ExportCancellation();
    private final AtomicReference<Listener> listener;
    private final Executor callbacks;
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Future<?> future;
    private boolean started;
    private ApkExportTask(Listener listener, Executor callbacks) { this.listener = new AtomicReference<>(listener); this.callbacks = callbacks; }

    static ApkExportTask submit(File workspaceParent, File output, Listener listener, Executor callbacks, Operation operation) {
        ApkExportTask task = new ApkExportTask(listener, callbacks);
        try {
            task.future = WORKERS.submit(() -> {
                synchronized (task) {
                    if (task.cancellation.getAsBoolean()) { task.finished.countDown(); return; }
                    task.started = true;
                }
                try (ExportWorkspace workspace = new ExportWorkspace(workspaceParent)) {
                    task.cancellation.check();
                    File artifact = operation.run(workspace, task.cancellation, task::progress);
                    task.cancellation.check();
                    AtomicArtifactPublisher.publish(artifact, output, task.cancellation,
                            file -> SignedApkVerifier.verify(file, null));
                    task.terminal(output, null);
                } catch (Exception error) {
                    String detail = error.getMessage();
                    if (detail == null || detail.isEmpty()) detail = error.getClass().getSimpleName();
                    task.terminal(null, "APK 导出失败：" + detail.substring(0, Math.min(detail.length(), 4000)));
                } finally { task.cancellation.setCancellationHook(null); task.finished.countDown(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            task.terminal(null, "导出任务繁忙，请稍后重试"); task.finished.countDown();
        }
        return task;
    }
    private void progress(String message, int percent) {
        callbacks.execute(() -> { synchronized (this) {
            Listener target = listener.get();
            if (!cancellation.getAsBoolean() && target != null) target.onProgress(message, percent);
        } });
    }
    private void terminal(File output, String error) {
        callbacks.execute(() -> { synchronized (this) {
            Listener target = listener.getAndSet(null);
            if (cancellation.getAsBoolean() || target == null) return;
            if (error == null) target.onSuccess(output); else target.onError(error);
        } });
    }
    public synchronized void cancel() {
        listener.set(null);
        cancellation.cancel();
        // Do not interrupt the process owner's drain/reap path. The hook terminates its owned session.
        if (future != null && future.cancel(false) && !started) finished.countDown();
        WORKERS.purge();
    }
    public boolean isCancelled() { return cancellation.getAsBoolean(); }
    public boolean isDone() { return finished.getCount() == 0; }
    /** Background callers may wait for cancellation cleanup; the UI must never wait. */
    public boolean awaitFinished(long timeout, TimeUnit unit) throws InterruptedException { return finished.await(timeout, unit); }
    @Override public void close() { cancel(); }
}
