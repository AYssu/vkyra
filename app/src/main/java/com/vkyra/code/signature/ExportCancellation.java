package com.vkyra.code.signature;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Cancellation and final publication share a lock, providing a single commit boundary. */
public final class ExportCancellation implements BooleanSupplier {
    private boolean cancelled;
    private Runnable cancellationHook;
    public synchronized void cancel() {
        cancelled = true;
        if (cancellationHook != null) cancellationHook.run();
    }
    @Override public synchronized boolean getAsBoolean() { return cancelled; }
    public synchronized void setCancellationHook(Runnable hook) {
        cancellationHook = hook;
        if (cancelled && hook != null) hook.run();
    }
    public void check() throws IOException {
        if (getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Export cancelled");
    }
    synchronized void commit(Commit commit) throws IOException { check(); commit.run(); }
    interface Commit { void run() throws IOException; }
}
