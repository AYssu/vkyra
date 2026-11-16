package com.vkyra.code.editor;

import android.app.Activity;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.fail;

/** Waits for the actual owning window; never grants focus or bypasses a real UI click. */
final class DialogTestUi {
    private DialogTestUi() {}

    static <T extends Activity> void awaitActivityFocus(ActivityScenario<T> scenario) {
        long deadline=SystemClock.uptimeMillis()+5_000;
        AtomicBoolean ready=new AtomicBoolean();
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                android.view.View decor=activity.getWindow().getDecorView();
                ready.set(decor.isAttachedToWindow() && decor.hasWindowFocus() && !decor.isLayoutRequested());
            });
            if(ready.get()) return;
            SystemClock.sleep(20);
        } while(SystemClock.uptimeMillis()<deadline);
        fail("Activity window did not regain focus before opening the next dialog");
    }
}
