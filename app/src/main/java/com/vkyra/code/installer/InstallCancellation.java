package com.vkyra.code.installer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** An installation owns its streams; cancellation never runs slow close operations on the UI. */
public final class InstallCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Closeable resource;

    public void check() throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("安装已取消");
        }
    }

    public boolean isCancelled() { return cancelled.get(); }

    public synchronized void attach(Closeable stream) throws IOException {
        check();
        resource = stream;
    }

    public synchronized void detach(Closeable stream) {
        if (resource == stream) resource = null;
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        final Closeable owned;
        synchronized (this) { owned = resource; resource = null; }
        if (owned != null) {
            Thread closer = new Thread(() -> {
                try { owned.close(); } catch (IOException ignored) { }
            }, "vkyra-install-close");
            closer.setDaemon(true);
            closer.start();
        }
    }
}
