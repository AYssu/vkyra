package com.vkyra.code.git;

import com.vkyra.code.project.RecentProjectManager;
import com.vkyra.code.settings.AppPreferences;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class GitCloneManager {
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(0, 2, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "vkyra-git-clone");
                thread.setDaemon(true);
                return thread;
            });

    private GitCloneManager() {}

    public interface CloneCallback {
        void onProgress(String task, int completed, int total);
        void onSuccess(String projectPath);
        void onError(String error);
    }

    /** The page owns this handle and must cancel it when it abandons the operation. */
    public static final class CloneTask implements AutoCloseable {
        private final AtomicReference<CloneCallback> callback;
        private final Executor callbacks;
        private volatile boolean cancelled;
        private volatile Future<?> future;

        private CloneTask(CloneCallback callback, Executor callbacks) {
            this.callback = new AtomicReference<>(callback);
            this.callbacks = callbacks;
        }

        public void cancel() {
            cancelled = true;
            callback.set(null);
            Future<?> running = future;
            if (running != null) running.cancel(true);
            EXECUTOR.purge();
        }

        public boolean isCancelled() { return cancelled; }
        public boolean isDone() { return future != null && future.isDone(); }
        @Override public void close() { cancel(); }

        private void setFuture(Future<?> future) {
            this.future = future;
            if (cancelled) future.cancel(true);
        }

        private void post(java.util.function.Consumer<CloneCallback> action, boolean terminal) {
            callbacks.execute(() -> {
                CloneCallback current = terminal ? callback.getAndSet(null) : callback.get();
                if (current != null && !cancelled) action.accept(current);
            });
        }
    }

    /** Clone in a staging directory, then publish a complete repository under its reserved name. */
    public static CloneTask cloneAsync(Context context, String repoUrl, String projectName,
                                       String username, String password, CloneCallback callback) {
        Context application = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        CloneTask task = new CloneTask(callback, handler::post);
        try {
            task.setFuture(EXECUTOR.submit(() -> {
                try (OriginCredentialsProvider credentials = new OriginCredentialsProvider()) {
                    String origin = GitCredentialManager.extractOrigin(repoUrl);
                    credentials.add(origin, username, password);
                    File root = new File(AppPreferences.getProjectRoot(application));
                    File project = cloneRepository(root, repoUrl, projectName, credentials, monitor(task));
                    if (task.cancelled || Thread.currentThread().isInterrupted()) return;
                    RecentProjectManager.initProject(project.getAbsolutePath(), projectName, "git", null);
                    task.post(cb -> cb.onSuccess(project.getAbsolutePath()), true);
                } catch (Exception e) {
                    if (!task.cancelled && !Thread.currentThread().isInterrupted()) {
                        String message = e instanceof CloneInputException ? e.getMessage()
                                : GitManager.errorMessage(e);
                        task.post(cb -> cb.onError(message), true);
                    }
                }
            }));
        } catch (RejectedExecutionException e) {
            task.setFuture(java.util.concurrent.CompletableFuture.completedFuture(null));
            task.post(cb -> cb.onError("克隆队列已满，请稍后重试"), true);
        }
        return task;
    }

    static File cloneRepository(File projectRoot, String repoUrl, String projectName,
                                OriginCredentialsProvider credentials, ProgressMonitor monitor) throws Exception {
        validateProjectName(projectName);
        if (new URIish(repoUrl).getPass() != null) {
            throw new CloneInputException("请把密码或 Token 填入认证栏，不要放入仓库地址");
        }
        if (monitor.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Clone cancelled");
        }
        Path root = projectRoot.getCanonicalFile().toPath();
        Files.createDirectories(root);
        Path destination = root.resolve(projectName);
        // Exclusive reservation prevents overwriting an existing project, including an empty directory.
        Files.createDirectory(destination);
        Path staging = null;
        boolean published = false;
        try {
            staging = Files.createTempDirectory(root, ".vkyra-clone-");
            try (Git ignored = Git.cloneRepository().setURI(repoUrl).setDirectory(staging.toFile())
                    .setTransportConfigCallback(OriginHttpConnectionFactory::configure)
                    .setCredentialsProvider(credentials).setProgressMonitor(monitor).setTimeout(30).call()) {
                if (monitor.isCancelled()) throw new InterruptedException("Clone cancelled");
            }
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            published = true;
            return destination.toFile();
        } finally {
            if (staging != null) {
                try { FileUtils.delete(staging.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING); }
                catch (IOException ignored) { /* Never delete the destination to clean up a staging failure. */ }
            }
            if (!published) {
                try { Files.deleteIfExists(destination); }
                catch (IOException ignored) { /* A concurrently populated directory must be preserved. */ }
            }
        }
    }

    static void validateProjectName(String name) throws CloneInputException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new CloneInputException("项目名称必须是单个文件夹名称，不能包含路径分隔符");
        }
    }

    private static ProgressMonitor monitor(CloneTask task) {
        return new ProgressMonitor() {
            private String title = "";
            private int total;
            private int completed;
            private long lastPost;
            @Override public void start(int totalTasks) {}
            @Override public void beginTask(String title, int totalWork) {
                this.title = title;
                this.total = totalWork;
                this.completed = 0;
                publish();
            }
            @Override public void update(int delta) {
                completed += delta;
                if (System.nanoTime() - lastPost >= TimeUnit.MILLISECONDS.toNanos(100)) publish();
            }
            private void publish() {
                lastPost = System.nanoTime();
                String snapshotTitle = title;
                int snapshotCompleted = completed;
                int snapshotTotal = total;
                task.post(cb -> cb.onProgress(snapshotTitle, snapshotCompleted, snapshotTotal), false);
            }
            @Override public void endTask() { publish(); }
            @Override public boolean isCancelled() {
                return task.cancelled || Thread.currentThread().isInterrupted();
            }
        };
    }

    public static String extractRepoName(String url) {
        if (url == null || url.trim().isEmpty()) return "";
        try {
            return new URIish(url.trim()).getHumanishName();
        } catch (Exception e) {
            return "";
        }
    }

    private static final class CloneInputException extends IOException {
        CloneInputException(String message) { super(message); }
    }
}
