package com.vkyra.code.toolchain;

import com.vkyra.code.terminal.ManagedProcessRunner;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Single-use shell request. No UI or Context is read by its bounded worker. */
public final class CustomCommandTask implements AutoCloseable {
    public static final long COMPILE_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);
    public static final long SHELL_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    public static final class Request {
        public final String shell, command, sourceFile, outputFile, runCommand;
        public final File directory;
        public final long timeoutMillis;

        public Request(String shell, String command, File directory, long timeoutMillis,
                       String sourceFile, String outputFile, String runCommand) {
            if (shell == null || shell.isEmpty() || command == null || timeoutMillis <= 0) {
                throw new IllegalArgumentException("Invalid command request");
            }
            this.shell = shell;
            this.command = command;
            this.directory = directory;
            this.timeoutMillis = timeoutMillis;
            this.sourceFile = sourceFile;
            this.outputFile = outputFile;
            this.runCommand = runCommand;
        }
    }

    public static final class Result {
        public final Request request;
        public final String output, errorOutput, errorMessage;
        public final int exitCode;
        public final long elapsedMillis;
        public final boolean success, outputTruncated;

        private Result(Request request, ManagedProcessRunner.Result process, Exception failure, long elapsed) {
            this.request = request;
            elapsedMillis = elapsed;
            output = process == null ? "" : process.output;
            errorOutput = process == null ? "" : process.errorOutput;
            exitCode = process == null ? -1 : process.exitCode;
            success = failure == null && process != null && !process.cancelled && exitCode == 0;
            outputTruncated = process != null && process.outputTruncated;
            if (success) errorMessage = "";
            else if (failure != null) errorMessage = "执行失败: " + failure.getMessage();
            else if (process != null && process.cancelled) errorMessage = "命令执行超时";
            else if (!errorOutput.isEmpty()) errorMessage = errorOutput;
            else if (!output.isEmpty()) errorMessage = output;
            else errorMessage = "命令执行失败，退出码: " + exitCode;
        }
    }

    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 10,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "Vkyra-custom-command");
                thread.setDaemon(true);
                return thread;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }

    private final ManagedProcessRunner runner = new ManagedProcessRunner(null);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean claimed = new AtomicBoolean();
    private final AtomicReference<Consumer<Result>> callback;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private final FutureTask<Void> job;

    public CustomCommandTask(Request request, Executor delivery, Consumer<Result> callback) {
        this.callback = new AtomicReference<>(callback);
        job = new FutureTask<>(() -> {
            if (!claimed.compareAndSet(false, true)) return null;
            long start = System.nanoTime();
            try {
                if (cancelled.get()) return null;
                ProcessBuilder command = new ProcessBuilder(request.shell, "-c", request.command);
                if (request.directory != null) command.directory(request.directory);
                ManagedProcessRunner.Result result = runner.run(command, request.timeoutMillis, null, true);
                deliver(delivery, new Result(request, result, null, elapsed(start)));
            } catch (Exception failure) {
                deliver(delivery, new Result(request, null, failure, elapsed(start)));
            } finally {
                closed.complete(null);
            }
            return null;
        });
        try { WORKERS.execute(job); }
        catch (RejectedExecutionException busy) {
            claimed.set(true);
            closed.complete(null);
            deliver(delivery, new Result(request, null, busy, 0));
        }
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private void deliver(Executor delivery, Result result) {
        if (cancelled.get()) return;
        try {
            delivery.execute(() -> {
                Consumer<Result> listener = callback.getAndSet(null);
                if (!cancelled.get() && listener != null) listener.accept(result);
            });
        } catch (RejectedExecutionException unavailable) {
            callback.set(null);
        }
    }

    /** Completes only once the worker has reaped its owned processes and closed their streams. */
    public CompletableFuture<Void> whenClosed() { return closed; }
    public boolean isCancelled() { return cancelled.get(); }

    public void cancel() {
        cancelled.set(true);
        callback.set(null);
        runner.cancel();
        if (claimed.compareAndSet(false, true)) {
            job.cancel(false);
            WORKERS.remove(job);
            closed.complete(null);
        }
        // Do not interrupt the worker: it must finish the runner's process reaping/stream cleanup.
    }

    @Override public void close() { cancel(); }
}
