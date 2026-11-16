package com.vkyra.code.terminal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, cancellable subprocess sessions. Each instance is single use. */
public final class ManagedProcessRunner {
    public interface Signal { void kill(int pid); }
    public static final class Result {
        public final int exitCode;
        public final String output, errorOutput;
        public final boolean cancelled, outputTruncated;
        Result(int exitCode, String output, String errors, boolean cancelled, boolean truncated) {
            this.exitCode = exitCode; this.output = output; errorOutput = errors;
            this.cancelled = cancelled; outputTruncated = truncated;
        }
    }
    private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "Vkyra-process-timeout"); t.setDaemon(true); return t;
    });
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 12, 10, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "Vkyra-process-io"); t.setDaemon(true); return t; });
    private static final ThreadPoolExecutor CANCELLATIONS = new ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> new Thread(r, "Vkyra-process-cancel"));
    static { TIMEOUTS.setRemoveOnCancelPolicy(true); }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile Process process;
    private volatile ProcessOwnership.Identity identity;
    private volatile RuntimeException cleanupFailure;
    private volatile boolean started;
    private boolean nativeProcess;
    private java.util.function.Consumer<String> outputListener;
    public void setOutputListener(java.util.function.Consumer<String> listener) { outputListener = listener; }
    public ManagedProcessRunner(Signal ignored) { }
    public boolean isCancelled() { return cancelled.get(); }
    public boolean isRunning() { return process != null; }

    public void cancel() {
        cancelled.set(true);
        Process running = process;
        if (running == null) return;
        try { CANCELLATIONS.execute(() -> stop(running)); }
        catch (RejectedExecutionException full) { stop(running); }
    }
    /** Must be called off the UI thread before rolling back files used by a script. */
    public void awaitStopped() throws InterruptedException {
        if (started) finished.await();
        if (cleanupFailure != null) throw cleanupFailure;
    }
    private synchronized void stop(Process running) {
        try {
            if (nativeProcess) {
                // PipeProcess retains its unreaped leader until every pipe is drained.
                running.destroyForcibly();
            } else {
                ProcessOwnership.Identity root = identity;
                if (root != null) {
                    for (ProcessOwnership.Identity member : ProcessOwnership.sessionGroups(ProcessOwnership.snapshot(), root.pid).values()) {
                        if (member.sessionId == root.pid &&
                                member.sameProcess(ProcessOwnership.read(member.pid))) {
                            Process kill = new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + member.processGroup).start();
                            kill.waitFor();
                        }
                    }
                }
                running.destroyForcibly();
            }
        } catch (Exception failure) {
            cleanupFailure = new IllegalStateException("Cannot stop owned process session", failure);
        }
    }
    private static final class Captured {
        final String text; final boolean truncated;
        Captured(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }
    private Captured read(InputStream stream, boolean pidHeader) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            if (pidHeader) {
                identity = ProcessOwnership.parseStat(reader.readLine());
                if (identity == null) throw new IOException("Cannot read owned process identity");
            }
            StringBuilder output = new StringBuilder();
            char[] buffer = new char[8192]; int count; boolean truncated = false;
            while ((count = reader.read(buffer)) != -1) {
                int remaining = OUTPUT_LIMIT - output.length();
                if (remaining > 0) output.append(buffer, 0, Math.min(count, remaining));
                if (count > remaining) truncated = true;
                if (outputListener != null) outputListener.accept(new String(buffer, 0, count));
            }
            return new Captured(output.toString(), truncated);
        }
    }
    public Result run(ProcessBuilder command, long timeoutMillis) throws Exception {
        return run(command, timeoutMillis, null, false);
    }
    /** Separate output is required when stdout is a document (clang-format). */
    public Result run(ProcessBuilder command, long timeoutMillis, String input, boolean separateOutput) throws Exception {
        started = true;
        ScheduledFuture<?> timeout = null, interrupted = null;
        Process running = null;
        List<Future<?>> jobs = new ArrayList<>();
        try {
            if (cancelled.get()) return new Result(-1, "操作已取消", "", true, false);
            command.redirectErrorStream(!separateOutput);
            nativeProcess = new File("/system/bin/sh").exists();
            if (nativeProcess) {
                running = (Process) Class.forName("com.termux.terminal.PipeProcess").getMethod("start", ProcessBuilder.class).invoke(null, command);
            } else {
                String setsid = new File("/usr/bin/setsid").exists() ? "/usr/bin/setsid" : "/bin/setsid";
                List<String> wrapped = new ArrayList<>(Arrays.asList(setsid, "/bin/sh", "-c", "read -r identity < /proc/$$/stat; printf '%s\\n' \"$identity\"; exec \"$@\"", "vkyra-process"));
                wrapped.addAll(command.command()); command.command(wrapped);
                running = command.start();
            }
            process = running;
            final Process owned = running;
            timeout = TIMEOUTS.schedule(this::cancel, Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
            Thread worker = Thread.currentThread();
            interrupted = TIMEOUTS.scheduleWithFixedDelay(() -> { if (worker.isInterrupted()) cancel(); }, 100, 100, TimeUnit.MILLISECONDS);
            Future<Captured> stdout = IO.submit(() -> read(owned.getInputStream(), !nativeProcess)); jobs.add(stdout);
            Future<Captured> stderr = IO.submit(() -> read(owned.getErrorStream(), false)); jobs.add(stderr);
            Future<?> stdin = IO.submit(() -> {
                try (OutputStream stream = owned.getOutputStream()) {
                    if (input != null) stream.write(input.getBytes(StandardCharsets.UTF_8));
                } catch (IOException failure) { if (!cancelled.get()) throw new UncheckedIOException(failure); }
            }); jobs.add(stdin);
            if (cancelled.get()) stop(running);
            Captured output = await(stdout); Captured errors = await(stderr); await(stdin);
            while (running.isAlive()) Thread.sleep(10);
            // A successful script may leave background children with closed stdio. Stop
            // them before reporting completion or allowing installation rollback/commit.
            stop(running);
            if (cleanupFailure != null) throw cleanupFailure;
            int status = running.waitFor();
            return new Result(status, output.text, errors.text, cancelled.get(), output.truncated || errors.truncated);
        } catch (Exception failure) {
            if (cancelled.get() && cleanupFailure == null) return new Result(-1, "操作已取消", "", true, false);
            if (failure instanceof ExecutionException && failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (interrupted != null) interrupted.cancel(false);
            if (running != null) {
                stop(running);
                try { running.waitFor(); } catch (InterruptedException interruptedWait) { Thread.currentThread().interrupt(); }
                close(running.getOutputStream()); close(running.getInputStream()); close(running.getErrorStream());
            }
            for (Future<?> job : jobs) job.cancel(true);
            process = null; finished.countDown();
        }
    }
    private <T> T await(Future<T> job) throws Exception {
        while (true) {
            if (cleanupFailure != null) throw cleanupFailure;
            try { return job.get(100, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { }
        }
    }
    private static void close(Closeable stream) { try { stream.close(); } catch (IOException ignored) { } }
}
