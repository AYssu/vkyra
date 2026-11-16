package com.vkyra.code;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.vkyra.code.crash.CrashActivity;
import com.vkyra.code.home.MainActivity;
import cat.ereza.customactivityoncrash.config.CaocConfig;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Drives the real separate crash_handler process through accessibility, not ActivityScenario. */
@RunWith(AndroidJUnit4.class)
public class CrashPageTest {
    @Test public void separateCrashPageShowsDetailsAndSurvivesBackAndRotation() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo service=automation.getServiceInfo();
        int previousFlags=service.flags;
        service.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        String trace="java.lang.IllegalStateException: acceptance crash\n\tat com.vkyra.code.Example.run(Example.java:42)";
        Intent intent=new Intent(context,CrashActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("cat.ereza.customactivityoncrash.EXTRA_CONFIG",CaocConfig.Builder.create().restartActivity(MainActivity.class).get())
                .putExtra("cat.ereza.customactivityoncrash.EXTRA_STACK_TRACE",trace);
        try {
            context.startActivity(intent);
            assertTrue(awaitNode(automation,"tv_error_details").getText().toString().contains("acceptance crash"));
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            assertTrue(awaitNode(automation,"tv_error_details").getText().toString().contains("Example.java:42"));
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90));
            awaitOrientation(automation,true);
            assertTrue(awaitNode(automation,"tv_error_details").getText().toString().contains("acceptance crash"));
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
            awaitOrientation(automation,false);
            assertTrue(awaitNode(automation,"btn_copy").performAction(AccessibilityNodeInfo.ACTION_CLICK));
            assertNotNull(awaitNode(automation,"tv_error_details"));
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0);
            awaitOrientation(automation,false);
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE);
            service.flags=previousFlags;
            automation.setServiceInfo(service);
            // A bare asynchronous start can outlive this test and CLEAR_TASK underneath
            // the next page test. Own the real recovery launch until it is resumed/closed.
            try (androidx.test.core.app.ActivityScenario<MainActivity> recovery =
                    androidx.test.core.app.ActivityScenario.launch(new Intent(context,MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK))) {
                recovery.onActivity(activity -> {
                    assertFalse(activity.isFinishing());
                    assertTrue(activity.findViewById(R.id.app_mode_card).hasOnClickListeners());
                });
            }
        }
    }
    private static void awaitOrientation(UiAutomation automation,boolean landscape) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do {
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root!=null) {
                android.graphics.Rect bounds=new android.graphics.Rect(); root.getBoundsInScreen(bounds);
                if((bounds.width()>bounds.height())==landscape) {
                    automation.waitForIdle(250,5000);
                    return;
                }
            }
            Thread.sleep(50);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("Crash page did not complete rotation");
    }
    private static AccessibilityNodeInfo awaitNode(UiAutomation automation,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do {
            AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root!=null) {
                List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId("com.vkyra.code:id/"+id);
                if(!nodes.isEmpty() && nodes.get(0).isVisibleToUser()) return nodes.get(0);
            }
            Thread.sleep(50);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("Missing separate crash page node: "+id);
    }
}
