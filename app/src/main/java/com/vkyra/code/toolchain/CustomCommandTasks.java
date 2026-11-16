package com.vkyra.code.toolchain;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Binds a shell request and its transient UI to the page that started it. */
public final class CustomCommandTasks {
    private CustomCommandTasks() { }

    public static OwnedTask start(LifecycleOwner owner, CustomCommandTask.Request request,
                                  Consumer<CustomCommandTask.Result> callback, Runnable cleanup) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Start commands on the main thread");
        }
        return new OwnedTask(owner, request, callback, cleanup);
    }

    public static final class OwnedTask implements AutoCloseable {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final WeakReference<LifecycleOwner> owner;
        private final AtomicReference<Consumer<CustomCommandTask.Result>> callback;
        private final AtomicReference<Runnable> cleanup;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final LifecycleEventObserver observer;
        private CustomCommandTask task;

        private OwnedTask(LifecycleOwner page, CustomCommandTask.Request request,
                          Consumer<CustomCommandTask.Result> callback, Runnable cleanup) {
            owner = new WeakReference<>(page);
            this.callback = new AtomicReference<>(callback);
            this.cleanup = new AtomicReference<>(cleanup);
            observer = (source, event) -> {
                if (event == Lifecycle.Event.ON_DESTROY) cancel();
            };
            if (page.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                cancelled.set(true);
                finishUi();
                return;
            }
            page.getLifecycle().addObserver(observer);
            task = new CustomCommandTask(request, main::post, result -> {
                Consumer<CustomCommandTask.Result> listener = this.callback.getAndSet(null);
                finishUi();
                if (isActive() && listener != null) listener.accept(result);
            });
        }

        /** Also guards delayed plugin continuations after the process callback has completed. */
        public boolean isActive() {
            LifecycleOwner page = owner.get();
            return !cancelled.get() && page != null
                    && page.getLifecycle().getCurrentState() != Lifecycle.State.DESTROYED;
        }

        public CompletableFuture<Void> whenClosed() {
            return task == null ? CompletableFuture.completedFuture(null) : task.whenClosed();
        }

        public void cancel() {
            cancelled.set(true);
            callback.set(null);
            if (task != null) task.cancel();
            if (Looper.myLooper() == Looper.getMainLooper()) finishUi();
            else main.post(this::finishUi);
        }

        private void finishUi() {
            Runnable action = cleanup.getAndSet(null);
            if (action != null) action.run();
            LifecycleOwner page = owner.get();
            if (page != null) page.getLifecycle().removeObserver(observer);
        }

        @Override public void close() { cancel(); }
    }
}
