package com.vkyra.code.installer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** One cancellable worker; callbacks are detached at cancellation/completion and cannot retain a page. */
public final class InstallJob implements AutoCloseable {
    @FunctionalInterface public interface Work { int run(InstallCancellation cancellation, ToolchainArchives.Progress progress) throws IOException; }
    public interface Listener {
        void progress(int current, int total, String name);
        void success(int files);
        void error(String message);
    }
    private final InstallCancellation cancellation = new InstallCancellation();
    private final AtomicReference<Listener> listener;
    private final Executor delivery;
    private final ExecutorService worker;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final Future<?> task;
    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();

    public InstallJob(Work work, Listener listener, Executor delivery) {
        this.listener = new AtomicReference<>(listener);
        this.delivery = delivery;
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "vkyra-toolchain-install");
            thread.setDaemon(true);
            return thread;
        });
        task = worker.submit(() -> {
            started.set(true);
            try {
                cancellation.check();
                int files = work.run(cancellation, (current, total, name) -> post(false, value -> value.progress(current, total, name)));
                post(true, value -> value.success(files));
            } catch (IOException | RuntimeException failure) {
                if (!cancellation.isCancelled()) post(true, value -> value.error(failure.getMessage() == null ? "安装失败" : failure.getMessage()));
            } finally {
                worker.shutdown();
                closed.complete(null);
            }
        });
        // No further tasks can be submitted; shutdown also covers cancellation before execution starts.
        worker.shutdown();
    }

    private interface Delivery { void accept(Listener listener); }
    private void post(boolean terminal, Delivery action) {
        if (cancellation.isCancelled()) return;
        delivery.execute(() -> {
            Listener value = terminal ? listener.getAndSet(null) : listener.get();
            if (value != null && !cancellation.isCancelled()) action.accept(value);
        });
    }

    public void cancel() {
        listener.set(null);
        cancellation.cancel();
        // Interrupt an active task, but do not remove a queued task: its finally owns completion.
        // The worker starts with cancellation.check(), so a queued task performs no installation.
        if (!closed.isDone()) worker.shutdownNow();
        task.cancel(true);
        // If cancelled before starting, the task body/finally never executes.
        if (!started.get() || worker.isTerminated()) closed.complete(null);
    }

    public boolean isCancelled() { return cancellation.isCancelled(); }
    public CompletableFuture<Void> whenClosed() { return closed; }
    @Override public void close() { cancel(); }
}
