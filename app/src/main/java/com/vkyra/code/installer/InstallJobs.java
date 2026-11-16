package com.vkyra.code.installer;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.lang.ref.WeakReference;

/** Android lifecycle binding around the otherwise host-testable installation owner. */
public final class InstallJobs {
    private InstallJobs() { }

    public static InstallJob start(Context context, InstallJob.Work work, InstallJob.Listener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        InstallJob job = new InstallJob(work, listener, main::post);
        Activity activity = activity(context);
        if (activity instanceof LifecycleOwner) {
            LifecycleOwner owner = (LifecycleOwner) activity;
            WeakReference<LifecycleOwner> reference = new WeakReference<>(owner);
            DefaultLifecycleObserver observer = new DefaultLifecycleObserver() {
                @Override public void onDestroy(LifecycleOwner source) { job.cancel(); source.getLifecycle().removeObserver(this); }
            };
            owner.getLifecycle().addObserver(observer);
            job.whenClosed().whenComplete((unused, failure) -> main.post(() -> {
                LifecycleOwner current = reference.get();
                if (current != null) current.getLifecycle().removeObserver(observer);
            }));
        }
        return job;
    }

    public static Activity activity(Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) return null;
            current = next;
        }
        return null;
    }
}
