package com.vkyra.code.home;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.vkyra.code.R;
import com.vkyra.code.editor.EditorActivity;
import com.vkyra.code.editor.EditorSessionViewModel;
import com.vkyra.code.project.NewProjectActivity;
import com.vkyra.code.settings.AppPreferences;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/**
 * Normal execution is an authorized-home/project positive control. The dedicated
 * external harness force-stops/revokes BEFORE launching with -e phase denied, which
 * additionally proves genuine denial and recovery. Self-revocation kills this runner.
 */
@RunWith(AndroidJUnit4.class)
public class StoragePermissionDeniedTest {
    private static final String READ = Manifest.permission.READ_EXTERNAL_STORAGE;
    private static final String WRITE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    @Test public void preparedStoragePermissionStateAndRecovery() throws Exception {
        String phase = InstrumentationRegistry.getArguments().getString("phase", "normal");
        assertTrue("phase must be normal or denied", "normal".equals(phase) || "denied".equals(phase));
        boolean denied = "denied".equals(phase);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Legacy permission contract is intentional", context.getApplicationInfo().targetSdkVersion <= 28);
        int expectedPermission = denied ? PackageManager.PERMISSION_DENIED : PackageManager.PERMISSION_GRANTED;
        assertEquals("prepare the requested permission phase before starting the runner", expectedPermission, context.checkSelfPermission(READ));
        assertEquals("prepare the requested permission phase before starting the runner", expectedPermission, context.checkSelfPermission(WRITE));
        UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        SharedPreferences preferences = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        boolean hadRoot = preferences.contains(AppPreferences.KEY_PROJECT_ROOT);
        String oldRoot = preferences.getString(AppPreferences.KEY_PROJECT_ROOT, null);
        File root = new File(Environment.getExternalStorageDirectory(), "VkyraPermissionTest_" + java.util.UUID.randomUUID());
        File project = new File(root, "Recovered");
        AccessibilityServiceInfo serviceInfo = automation.getServiceInfo();
        int originalFlags = serviceInfo.flags;
        try {
            assertFalse("Fixture must be unique", root.exists());
            if (denied) assertFalse("Real external filesystem must reject writes before testing the UI", root.mkdir());
            else assertTrue("authorized positive control must really write external storage", root.mkdir());
            serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(serviceInfo);
            if (denied) {
                assertHomeRejectsDeniedPermission(context, automation);
                assertPrivateEditorPermissionUi(context, true);
            }

            assertTrue(preferences.edit().putString(AppPreferences.KEY_PROJECT_ROOT, root.getPath()).commit());
            try (ActivityScenario<NewProjectActivity> scenario = ActivityScenario.launch(
                    new Intent(context, NewProjectActivity.class))) {
                CountDownLatch initialInputReady = new CountDownLatch(1);
                scenario.onActivity(a -> a.findViewById(R.id.et_project_name).postDelayed(initialInputReady::countDown, 250));
                assertTrue(initialInputReady.await(3, TimeUnit.SECONDS));
                onView(withId(R.id.et_project_name)).perform(replaceText("Recovered"), closeSoftKeyboard());
                scenario.onActivity(a -> {
                    ((RadioGroup) a.findViewById(R.id.rg_template)).check(R.id.rb_cpp);
                    ((CompoundButton) a.findViewById(R.id.switch_git_init)).setChecked(false);
                    ((CompoundButton) a.findViewById(R.id.switch_readme)).setChecked(false);
                });
                if (denied) {
                    onView(withId(R.id.btn_create)).perform(scrollTo(), click());
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    onView(withText("创建成功")).check(doesNotExist());
                    onView(withId(R.id.et_project_name)).check(matches(withText("Recovered")));
                    scenario.onActivity(a -> {
                        assertFalse("failed creation must keep the page available for retry", a.isFinishing());
                        assertTrue(a.findViewById(R.id.btn_create).isEnabled());
                        assertTrue(((TextView) a.findViewById(R.id.tv_project_path)).getText().toString().endsWith("/Recovered"));
                    });
                    assertFalse("denied create cannot produce a project", project.exists());
                    assertFalse("denied create cannot produce metadata", new File(project, ".akode/project.json").exists());

                    grantStorage(context, automation);
                    assertTrue("actual external write access must recover after the runtime grant", root.mkdir());
                }
                onView(withId(R.id.btn_create)).perform(scrollTo(), click());
                onView(withText("创建成功")).check(matches(isDisplayed()));
                assertTrue(project.isDirectory());
                String source = read(new File(project, "main.cpp"));
                assertTrue("real C++ source was created", source.contains("Hello, Recovered"));
                JSONObject config = new JSONObject(read(new File(project, ".akode/project.json")));
                assertEquals("Recovered", config.getString("name"));
                assertEquals("cpp", config.getString("template"));
                assertFalse(new File(project, ".git").exists());
                onView(withText("关闭")).perform(click());
            }
            assertPrivateEditorPermissionUi(context, false);
            // The ordinary home launch must initialize its real listeners after authorization.
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
                scenario.onActivity(a -> {
                    assertFalse(a.isFinishing());
                    assertNotNull(field(a, "editorModeCard"));
                    assertTrue(a.findViewById(R.id.app_mode_card).hasOnClickListeners());
                });
            }
        } finally {
            // Grants never revoke/kill this runner. The outer shell trap also restores them if
            // the app crashes before Java finally can execute.
            try { grantStorage(context, automation); }
            finally {
                serviceInfo.flags = originalFlags;
                automation.setServiceInfo(serviceInfo);
                SharedPreferences.Editor edit = preferences.edit();
                if (hadRoot) edit.putString(AppPreferences.KEY_PROJECT_ROOT, oldRoot);
                else edit.remove(AppPreferences.KEY_PROJECT_ROOT);
                assertTrue("restore project-root preference", edit.commit());
                removeTree(root);
            }
        }
    }

    private static void assertPrivateEditorPermissionUi(Context context, boolean denied) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, Boolean> previous = new HashMap<>();
        for (String key : new String[]{"enable_lsp", "auto_save", "remember_last_file"}) {
            previous.put(key, preferences.contains(key) ? preferences.getBoolean(key, false) : null);
        }
        assertTrue(preferences.edit().putBoolean("enable_lsp", false).putBoolean("auto_save", false)
                .putBoolean("remember_last_file", false).commit());
        File project = Files.createTempDirectory(context.getCacheDir().toPath(), "storage-permission-editor-").toFile();
        File source = new File(project, "main.cpp");
        String disk = "int main() { return 0; }";
        Files.write(source.toPath(), disk.getBytes(StandardCharsets.UTF_8));
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(
                new Intent(context, EditorActivity.class).putExtra("project_path", project.getPath()))) {
            if (denied) {
                onView(withText("需要存储权限")).check(matches(isDisplayed()));
                onView(withText("授予权限")).check(matches(isDisplayed()));
                onView(withText("取消")).perform(click());
            } else onView(withText("需要存储权限")).check(doesNotExist());
            java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
            long deadline = SystemClock.uptimeMillis() + 15_000;
            do {
                scenario.onActivity(a -> {
                    EditorSessionViewModel session = (EditorSessionViewModel) field(a, "editorSession");
                    io.github.rosemoe.sora.widget.CodeEditor editor = (io.github.rosemoe.sora.widget.CodeEditor) field(a, "codeEditor");
                    ready.set(Boolean.TRUE.equals(session.ready().getValue()) && editor.isEditable()
                            && disk.equals(editor.getText().toString()));
                });
                if (!ready.get()) Thread.sleep(25);
            } while (!ready.get() && SystemClock.uptimeMillis() < deadline);
            assertTrue("permission status must not block actual app-private file loading", ready.get());
            onView(withId(R.id.code_editor)).perform(click());
            scenario.onActivity(a -> {
                io.github.rosemoe.sora.widget.CodeEditor editor = (io.github.rosemoe.sora.widget.CodeEditor) field(a, "codeEditor");
                assertTrue("canceling shared-storage permission must leave internal files editable", editor.hasFocus());
                editor.setSelection(0, 0);
                android.view.inputmethod.InputConnection keyboard = editor.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
                assertNotNull(keyboard);
                assertTrue(keyboard.commitText("// private draft ", 1));
                assertEquals("// private draft " + disk, editor.getText().toString());
            });
            assertEquals("editing private draft must not silently save the source", disk, read(source));
        } finally {
            SharedPreferences.Editor edit = preferences.edit();
            for (Map.Entry<String, Boolean> value : previous.entrySet()) {
                if (value.getValue() == null) edit.remove(value.getKey());
                else edit.putBoolean(value.getKey(), value.getValue());
            }
            assertTrue(edit.commit());
            removeTree(project);
        }
    }

    private static void assertHomeRejectsDeniedPermission(Context context, UiAutomation automation) throws Exception {
        AtomicReference<MainActivity> main = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1), destroyed = new CountDownLatch(1);
        ActivityLifecycleCallback callback = (activity, stage) -> {
            if (!(activity instanceof MainActivity)) return;
            if (stage == Stage.CREATED) { main.set((MainActivity) activity); created.countDown(); }
            if (stage == Stage.DESTROYED) destroyed.countDown();
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback));
        try {
            // ActivityScenario waits for RESUMED, which cannot happen while the real system
            // permission/settings page covers the home activity. Observe the normal launch.
            context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            assertTrue("home did not launch", created.await(10, TimeUnit.SECONDS));
            assertNull("home must not initialize feature entry listeners before authorization", field(main.get(), "editorModeCard"));
            boolean handledSystemBoundary = false;
            long deadline = SystemClock.uptimeMillis() + 10_000;
            while (SystemClock.uptimeMillis() < deadline && !handledSystemBoundary) {
                AccessibilityNodeInfo window = automation.getRootInActiveWindow();
                if (window != null) {
                    try {
                        handledSystemBoundary = denyPermission(window);
                        assertFalse("target 28 must request ordinary runtime permissions, not all-files special access",
                                "com.android.settings".contentEquals(window.getPackageName()));
                    } finally { window.recycle(); }
                }
                if (!handledSystemBoundary) Thread.sleep(50);
            }
            assertTrue("real storage runtime permission prompt was not displayed", handledSystemBoundary);
            assertTrue("refused storage must close home instead of exposing initialized actions",
                    destroyed.await(10, TimeUnit.SECONDS));
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(READ));
            assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(WRITE));
            assertNull(field(main.get(), "editorModeCard"));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (main.get() != null && !main.get().isDestroyed()) main.get().finish();
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback);
            });
        }
    }
    private static boolean denyPermission(AccessibilityNodeInfo window) {
        // Android's repeat request can expose a real "Deny & don't ask again"
        // button while the ordinary deny button is GONE. Both cross the actual
        // system denial boundary; neither injects the app's permission callback.
        String[] ids = {"com.android.packageinstaller:id/permission_deny_button",
                "com.android.permissioncontroller:id/permission_deny_button",
                "com.android.packageinstaller:id/permission_deny_and_dont_ask_again_button",
                "com.android.permissioncontroller:id/permission_deny_and_dont_ask_again_button"};
        for (String id : ids) {
            List<AccessibilityNodeInfo> buttons = window.findAccessibilityNodeInfosByViewId(id);
            boolean clicked = false;
            for (AccessibilityNodeInfo button : buttons) {
                try { if (!clicked && button.isVisibleToUser()) clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                finally { button.recycle(); }
            }
            if (clicked) return true;
        }
        return false;
    }
    private static void grantStorage(Context context, UiAutomation automation) throws Exception {
        for (String permission : new String[]{READ, WRITE}) {
            // The package name is the fixed application ID; never interpolate user paths.
            assertEquals("com.vkyra.code", context.getPackageName());
            try (ParcelFileDescriptor.AutoCloseInputStream output = new ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("pm grant com.vkyra.code " + permission))) {
                byte[] buffer = new byte[1024];
                while (output.read(buffer) != -1) { /* drain until command completion */ }
            }
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(READ));
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(WRITE));
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static void removeTree(File root) throws Exception {
        if (!root.exists()) return;
        Files.walkFileTree(root.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path path,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path path,
                    java.io.IOException failure) throws java.io.IOException {
                if (failure != null) throw failure;
                Files.delete(path); return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
