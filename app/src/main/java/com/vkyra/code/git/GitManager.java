package com.vkyra.code.git;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Serial repository operations with explicit cancellation and deferred repository ownership. */
public class GitManager implements AutoCloseable {
    private static final int NETWORK_TIMEOUT_SECONDS = 30;
    private final Supplier<OriginCredentialsProvider> credentialSource;
    private final Executor callbackExecutor;
    private final Set<FutureTask<?>> tasks = new HashSet<>();
    private ThreadPoolExecutor executor;
    private Session session;
    private long generation;

    public enum FileStatus { UNTRACKED, MODIFIED, ADDED, DELETED, CONFLICTING, UNCHANGED }

    public interface GitCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public GitManager(Context context) {
        this(new Handler(Looper.getMainLooper())::post,
                savedCredentials(context.getApplicationContext()));
    }

    GitManager(Executor callbackExecutor) {
        this(callbackExecutor, OriginCredentialsProvider::new);
    }

    GitManager(Executor callbackExecutor, Supplier<OriginCredentialsProvider> credentialSource) {
        this.credentialSource = credentialSource;
        this.callbackExecutor = callbackExecutor;
    }

    private static Supplier<OriginCredentialsProvider> savedCredentials(Context application) {
        return () -> GitCredentialManager.createCredentialsProvider(application);
    }

    public static boolean isGitRepository(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        // A linked worktree's .git is a file; let JGit validate it when opening.
        return new File(dir, ".git").exists();
    }

    public static File findGitRoot(File dir) {
        File current = dir != null && dir.isFile() ? dir.getParentFile() : dir;
        while (current != null) {
            if (isGitRepository(current)) return current;
            current = current.getParentFile();
        }
        return null;
    }

    /** Reopening the same repository keeps running tasks and the existing JGit handle. */
    public synchronized boolean open(File dir) {
        File root = findGitRoot(dir);
        if (root == null) {
            closeCurrentSession();
            return false;
        }
        try {
            root = root.getCanonicalFile();
            if (session != null && root.equals(session.directory)) return true;
            Git next = Git.open(root);
            closeCurrentSession();
            session = new Session(next, root);
            return true;
        } catch (IOException e) {
            closeCurrentSession();
            return false;
        }
    }

    /** Cancels callbacks/work immediately; the JGit handle closes after its last command exits. */
    @Override
    public synchronized void close() {
        closeCurrentSession();
    }

    private void closeCurrentSession() {
        generation++;
        for (FutureTask<?> task : new ArrayList<>(tasks)) task.cancel(true);
        tasks.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        Session previous = session;
        session = null;
        if (previous != null) previous.closeWhenIdle();
    }

    public synchronized void cancelPendingOperations() {
        generation++;
        for (FutureTask<?> task : new ArrayList<>(tasks)) task.cancel(true);
        tasks.clear();
        if (executor != null) executor.purge();
    }

    public String getCurrentBranch() {
        return read(git -> git.getRepository().getBranch(), null);
    }

    public Future<?> getCurrentBranchAsync(GitCallback<String> callback) {
        return runAsync((git, monitor) -> git.getRepository().getBranch(), callback);
    }

    public FileStatus getFileStatus(String relativePath) {
        return read(git -> {
            Status status = git.status().addPath(relativePath).call();
            if (status.getConflicting().contains(relativePath)) return FileStatus.CONFLICTING;
            if (status.getUntracked().contains(relativePath)) return FileStatus.UNTRACKED;
            if (status.getAdded().contains(relativePath)) return FileStatus.ADDED;
            if (status.getModified().contains(relativePath) || status.getChanged().contains(relativePath)) {
                return FileStatus.MODIFIED;
            }
            if (status.getRemoved().contains(relativePath) || status.getMissing().contains(relativePath)) {
                return FileStatus.DELETED;
            }
            return FileStatus.UNCHANGED;
        }, FileStatus.UNCHANGED);
    }

    public GitStatus getStatus() {
        return read(git -> statusOf(git.status().call()), new GitStatus());
    }

    public Future<?> getStatusAsync(GitCallback<GitStatus> callback) {
        return runAsync((git, monitor) -> statusOf(git.status().call()), callback);
    }

    private static GitStatus statusOf(Status status) {
        GitStatus result = new GitStatus();
        result.staged.addAll(status.getAdded());
        result.staged.addAll(status.getChanged());
        result.staged.addAll(status.getRemoved());
        result.modified.addAll(status.getModified());
        result.modified.addAll(status.getMissing());
        result.untracked.addAll(status.getUntracked());
        result.conflicting.addAll(status.getConflicting());
        return result;
    }

    public Future<?> stageFile(String path, GitCallback<Void> callback) {
        return runAsync((git, monitor) -> {
            git.add().addFilepattern(path).setUpdate(true).call();
            git.add().addFilepattern(path).call();
            return null;
        }, callback);
    }

    public Future<?> stageAll(GitCallback<Void> callback) {
        return stageFile(".", callback);
    }

    public Future<?> unstageFile(String path, GitCallback<Void> callback) {
        return runAsync((git, monitor) -> {
            git.reset().addPath(path).call();
            return null;
        }, callback);
    }

    public Future<?> commit(String message, GitCallback<String> callback) {
        return runAsync((git, monitor) -> git.commit().setMessage(message).call()
                .getId().abbreviate(7).name(), callback);
    }

    /** Credentials are resolved against each actual transport URI, not a hardcoded GitHub host. */
    public Future<?> pull(GitCallback<String> callback) {
        return runAsync((git, monitor) -> {
            try (OriginCredentialsProvider credentials = credentials()) {
                PullResult result = git.pull().setCredentialsProvider(credentials)
                        .setTransportConfigCallback(OriginHttpConnectionFactory::configure)
                        .setTimeout(NETWORK_TIMEOUT_SECONDS).setProgressMonitor(monitor).call();
                if (!result.isSuccessful()) throw new OperationFailure("拉取存在冲突，请先解决冲突");
                return result.getMergeResult() == null ? "Success"
                        : result.getMergeResult().getMergeStatus().toString();
            }
        }, callback);
    }

    /** @deprecated Unscoped caller-provided credentials cannot be safely attached to a remote. */
    @Deprecated
    public Future<?> pull(String username, String password, GitCallback<String> callback) {
        return pull(callback);
    }

    public boolean hasRemote() {
        return read(git -> !git.remoteList().call().isEmpty(), false);
    }

    public Future<?> hasRemoteAsync(GitCallback<Boolean> callback) {
        return runAsync((git, monitor) -> !git.remoteList().call().isEmpty(), callback);
    }

    /** Display-only origin URL; authentication uses the URI received from the actual transport. */
    public String getRemoteUrl() {
        return read(git -> {
            for (RemoteConfig remote : git.remoteList().call()) {
                if ("origin".equals(remote.getName()) && !remote.getURIs().isEmpty()) {
                    return remote.getURIs().get(0).toString();
                }
            }
            return null;
        }, null);
    }

    public Future<?> addRemote(String name, String url, GitCallback<Void> callback) {
        return runAsync((git, monitor) -> {
            URIish uri = new URIish(url);
            if (uri.getPass() != null) throw new OperationFailure("请在凭据管理中保存密码或 Token，不要放入仓库地址");
            git.remoteAdd().setName(name).setUri(uri).call();
            return null;
        }, callback);
    }

    public Future<?> push(GitCallback<Void> callback) {
        return runAsync((git, monitor) -> {
            try (OriginCredentialsProvider credentials = credentials()) {
                Iterable<PushResult> results = git.push().setCredentialsProvider(credentials)
                        .setTransportConfigCallback(OriginHttpConnectionFactory::configure)
                        .setTimeout(NETWORK_TIMEOUT_SECONDS).setProgressMonitor(monitor).call();
                for (PushResult result : results) {
                    for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                        if (update.getStatus() != RemoteRefUpdate.Status.OK
                                && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                            throw new OperationFailure("推送被远端拒绝（" + update.getStatus().name() + "）");
                        }
                    }
                }
                return null;
            }
        }, callback);
    }

    /** @deprecated Use origin-scoped saved credentials through push(callback). */
    @Deprecated
    public Future<?> push(String username, String password, GitCallback<Void> callback) {
        return push(callback);
    }

    public List<String> getBranches() {
        return read(GitManager::branchesOf, new ArrayList<>());
    }

    public Future<?> getBranchesAsync(GitCallback<List<String>> callback) {
        return runAsync((git, monitor) -> branchesOf(git), callback);
    }

    private static List<String> branchesOf(Git git) throws Exception {
        List<String> result = new ArrayList<>();
        for (Ref ref : git.branchList().call()) {
            result.add(Repository.shortenRefName(ref.getName()));
        }
        return result;
    }

    public Future<?> checkout(String branchName, GitCallback<Void> callback) {
        return runAsync((git, monitor) -> {
            git.checkout().setName(branchName).setProgressMonitor(monitor).call();
            return null;
        }, callback);
    }

    public Future<?> discardChanges(String path, GitCallback<Void> callback) {
        return runAsync((git, monitor) -> {
            git.checkout().addPath(path).setProgressMonitor(monitor).call();
            return null;
        }, callback);
    }

    public synchronized File getRepoDir() {
        return session == null ? null : session.directory;
    }

    public String getRelativePath(File file) {
        File root = getRepoDir();
        if (root == null || file == null) return null;
        try {
            java.nio.file.Path rootPath = root.getCanonicalFile().toPath();
            java.nio.file.Path filePath = file.getCanonicalFile().toPath();
            return filePath.startsWith(rootPath) ? rootPath.relativize(filePath).toString() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private OriginCredentialsProvider credentials() {
        return credentialSource.get();
    }

    private <T> T read(RepositoryRead<T> operation, T fallback) {
        Session owner;
        synchronized (this) { owner = session; }
        if (owner == null || !owner.acquire()) return fallback;
        try {
            synchronized (owner.commandLock) {
                if (owner.closing) return fallback;
                return operation.run(owner.git);
            }
        } catch (Exception e) {
            return fallback;
        } finally {
            owner.release();
        }
    }

    private synchronized <T> Future<?> runAsync(RepositoryOperation<T> operation, GitCallback<T> callback) {
        Session owner = session;
        long expectedGeneration = generation;
        TaskState cancellation = new TaskState();
        if (owner == null) {
            post(callback, null, "尚未打开 Git 仓库", null, expectedGeneration);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        if (executor == null || executor.isShutdown()) {
            executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
                Thread thread = new Thread(r, "vkyra-git");
                thread.setDaemon(true);
                return thread;
            });
            executor.allowCoreThreadTimeOut(true);
        }
        FutureTask<Void> future = new FutureTask<Void>(() -> {
            if (!owner.acquire()) return null;
            try {
                synchronized (owner.commandLock) {
                    ProgressMonitor monitor = monitor(owner, expectedGeneration, cancellation);
                    if (monitor.isCancelled()) return null;
                    T result = operation.run(owner.git, monitor);
                    if (!monitor.isCancelled()) post(callback, result, null, owner, expectedGeneration, cancellation);
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    post(callback, null, errorMessage(e), owner, expectedGeneration, cancellation);
                }
            } finally {
                owner.release();
            }
            return null;
        }) {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancellation.cancelled = true;
                return super.cancel(mayInterruptIfRunning);
            }
            @Override protected void done() {
                synchronized (GitManager.this) { tasks.remove(this); }
            }
        };
        tasks.add(future);
        try {
            executor.execute(future);
        } catch (RejectedExecutionException e) {
            tasks.remove(future);
            future.cancel(false);
            post(callback, null, "Git 操作队列已满，请稍后重试", owner, expectedGeneration);
        }
        return future;
    }

    private synchronized boolean isCurrent(Session owner, long expectedGeneration) {
        return generation == expectedGeneration && session == owner;
    }

    private ProgressMonitor monitor(Session owner, long expectedGeneration, TaskState cancellation) {
        return new ProgressMonitor() {
            @Override public void start(int totalTasks) {}
            @Override public void beginTask(String title, int totalWork) {}
            @Override public void update(int completed) {}
            @Override public void endTask() {}
            @Override public boolean isCancelled() {
                return cancellation.cancelled || Thread.currentThread().isInterrupted() || owner.closing
                        || !isCurrent(owner, expectedGeneration);
            }
        };
    }

    private <T> void post(GitCallback<T> callback, T result, String error, Session owner, long epoch) {
        post(callback, result, error, owner, epoch, null);
    }

    private <T> void post(GitCallback<T> callback, T result, String error, Session owner, long epoch,
                          TaskState cancellation) {
        if (callback == null) return;
        callbackExecutor.execute(() -> {
            if (!isCurrent(owner, epoch) || (cancellation != null && cancellation.cancelled)) return;
            if (error == null) callback.onSuccess(result);
            else callback.onError(error);
        });
    }

    static String errorMessage(Exception exception) {
        if (exception instanceof OperationFailure) return exception.getMessage();
        // Transport exceptions can contain URL userinfo or server-controlled messages. Do not expose them.
        return "Git 操作失败（" + exception.getClass().getSimpleName() + "），请检查仓库状态、网络和认证配置";
    }

    private interface RepositoryRead<T> { T run(Git git) throws Exception; }
    private interface RepositoryOperation<T> { T run(Git git, ProgressMonitor monitor) throws Exception; }
    private static final class TaskState { volatile boolean cancelled; }
    private static final class OperationFailure extends IOException {
        OperationFailure(String message) { super(message); }
    }

    private static final class Session {
        final Git git;
        final File directory;
        final Object commandLock = new Object();
        volatile boolean closing;
        private int users;

        Session(Git git, File directory) { this.git = git; this.directory = directory; }
        synchronized boolean acquire() {
            if (closing) return false;
            users++;
            return true;
        }
        synchronized void release() {
            if (--users == 0 && closing) git.close();
        }
        synchronized void closeWhenIdle() {
            closing = true;
            if (users == 0) git.close();
        }
    }

    public static class GitStatus {
        public final Set<String> staged = new HashSet<>();
        public final Set<String> modified = new HashSet<>();
        public final Set<String> untracked = new HashSet<>();
        public final Set<String> conflicting = new HashSet<>();

        public int getTotalChanges() {
            Set<String> all = new HashSet<>(staged);
            all.addAll(modified);
            all.addAll(untracked);
            all.addAll(conflicting);
            return all.size();
        }
        public boolean hasChanges() { return getTotalChanges() > 0; }
    }
}
