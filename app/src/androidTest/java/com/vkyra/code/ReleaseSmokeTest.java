package com.vkyra.code;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import androidx.appcompat.widget.Toolbar;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.termux.view.TerminalView;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

/**
 * Black-box contract for both debug and a genuinely shrunk release APK signed with the
 * instrumentation certificate. Uses manifest components, resource names and public UI only.
 * Bundled tools must already be installed, for example by the coordinator's toolchain gate.
 */
@RunWith(AndroidJUnit4.class)
public class ReleaseSmokeTest {
    private static final String PACKAGE = "com.vkyra.code";
    private static final String MAIN = PACKAGE + ".home.MainActivity";
    private static final String SETTINGS = PACKAGE + ".settings.SettingsActivity";
    private static final String PLUGINS = PACKAGE + ".plugin.PluginActivity";
    private static final String NEW_PROJECT = PACKAGE + ".project.NewProjectActivity";
    private static final String EDITOR = PACKAGE + ".editor.EditorActivity";
    private static final String TERMINAL = PACKAGE + ".terminal.TerminalActivity";

    @Test public void manifestPagesRecreateAndRenderActualThemedDrawables() throws Exception {
        Context context = context();
        assertStoragePrepared(context);
        try (PreferencesScope settings = new PreferencesScope(context)) {
            checkPage(context, MAIN, "top_app_bar", "app_mode_card");
            checkPage(context, SETTINGS, "toolbar", "settings_container");
            checkPage(context, PLUGINS, "toolbar", "fab_install");
            checkPage(context, NEW_PROJECT, "toolbar", "et_project_name");
        }
    }

    @Test public void editorRunButtonCompilesCppAndRealTerminalDisplaysItsUniqueStdout() throws Exception {
        Context context = context();
        assertStoragePrepared(context);
        File project = Files.createTempDirectory(context.getFilesDir().toPath(), "release-smoke-").toFile();
        String marker = "VKYRA_RELEASE_STDOUT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        File source = new File(project, "main.cpp");
        String program = "#include <stdio.h>\nint main() { puts(\"" + marker + "\"); return 0; }\n";
        Files.write(source.toPath(), program.getBytes(StandardCharsets.UTF_8));
        AtomicBoolean runRequested = new AtomicBoolean();
        AtomicReference<Activity> ownedTerminal = new AtomicReference<>();
        ActivityLifecycleCallback callback = (activity, stage) -> {
            if (runRequested.get() && TERMINAL.equals(activity.getComponentName().getClassName())
                    && (stage == Stage.CREATED || stage == Stage.RESUMED)) ownedTerminal.set(activity);
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback));
        ActivityScenario<Activity> editor = null;
        PreferencesScope settings = null;
        try {
            settings = new PreferencesScope(context);
            editor = ActivityScenario.launch(component(context, EDITOR)
                    .putExtra("project_path", project.getPath()).putExtra("file_path", source.getPath()));
            awaitDisplayed(allOf(withId(id(context, "tv_title")), withText("main.cpp")), 20_000);
            awaitAbsent(withText("正在恢复编辑会话…"), 20_000);
            onView(withText("需要存储权限")).check(doesNotExist());
            onView(withId(id(context, "code_editor"))).check(matches(isDisplayed()));

            // Select the actual run option through its UI, independently of the previous
            // user's selected run mode. Then tap the real split-button's main action.
            onView(withId(id(context, "btn_run_dropdown"))).perform(click());
            onView(withText("运行")).inRoot(isPlatformPopup()).perform(click());
            onView(allOf(withId(id(context, "tv_run_label")), withText("运行"))).check(matches(isDisplayed()));
            runRequested.set(true);
            onView(withId(id(context, "btn_run_main"))).perform(click());

            AtomicReference<String> transcript = new AtomicReference<>("");
            long deadline = SystemClock.uptimeMillis() + 90_000;
            do {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    Activity terminal = ownedTerminal.get();
                    if (terminal == null || terminal.isDestroyed()) return;
                    View view = terminal.findViewById(id(context, "terminal_view"));
                    if (!(view instanceof TerminalView)) return;
                    TerminalView terminalView = (TerminalView) view;
                    if (terminalView.getCurrentSession() != null && terminalView.getCurrentSession().getEmulator() != null) {
                        transcript.set(terminalView.getCurrentSession().getEmulator().getScreen().getTranscriptText());
                    }
                });
                if (transcript.get().contains(marker)) break;
                Thread.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
            assertNotNull("run button never opened the real manifest TerminalActivity", ownedTerminal.get());
            assertTrue("compiled program's unique stdout missing from the real terminal; last screen: "
                    + tail(transcript.get(), 1600), transcript.get().contains(marker));
            assertEquals("compile/run must not rewrite source bytes", program,
                    new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
        } finally {
            // Only the terminal launched after this test's own run tap is finished.
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    try {
                        Activity terminal = ownedTerminal.get();
                        if (terminal != null && !terminal.isDestroyed()) terminal.finish();
                    } finally { ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback); }
                });
            } finally {
                try { if (editor != null) editor.close(); }
                finally {
                    try { if (settings != null) settings.close(); }
                    finally { removeTree(project); }
                }
            }
        }
    }

    private static void checkPage(Context context, String component, String toolbarName, String widgetName) throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(component(context, component))) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            verifyPage(scenario, context, component, toolbarName, widgetName);
            if (NEW_PROJECT.equals(component)) {
                onView(withId(id(context, "et_project_name"))).perform(replaceText("ReleaseSmokeProject"), closeSoftKeyboard());
            }
            scenario.recreate();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            verifyPage(scenario, context, component, toolbarName, widgetName);
            if (NEW_PROJECT.equals(component)) {
                onView(withId(id(context, "et_project_name"))).check(matches(withText("ReleaseSmokeProject")));
            }
        }
    }
    private static void verifyPage(ActivityScenario<Activity> scenario, Context context,
            String component, String toolbarName, String widgetName) {
        scenario.onActivity(activity -> {
            assertEquals(component, activity.getComponentName().getClassName());
            assertFalse(activity.isFinishing());
            View widget = activity.findViewById(id(context, widgetName));
            assertNotNull("real page widget missing: " + widgetName, widget);
            assertTrue(widget.isShown());
            Toolbar toolbar = activity.findViewById(id(context, toolbarName));
            assertNotNull(toolbar);
            Drawable icon = MAIN.equals(component) ? toolbar.getOverflowIcon() : toolbar.getNavigationIcon();
            assertNotNull("actual toolbar's themed icon must inflate", icon);
            assertDrawableRenders(icon);
            if (MAIN.equals(component)) assertTrue("home actually initialized its actions", widget.hasOnClickListeners());
            if (PLUGINS.equals(component)) {
                assertTrue(widget instanceof ImageView);
                assertNotNull(((ImageView) widget).getDrawable());
                assertDrawableRenders(((ImageView) widget).getDrawable());
            }
            if (NEW_PROJECT.equals(component)) assertTrue(widget instanceof EditText);
        });
        onView(withId(id(context, widgetName))).check(matches(isDisplayed()));
    }
    private static void assertDrawableRenders(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        Rect previous = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, 40, 40);
            drawable.draw(new Canvas(bitmap));
            boolean painted = false;
            for (int y = 0; y < 40 && !painted; y++) for (int x = 0; x < 40; x++) {
                if ((bitmap.getPixel(x, y) >>> 24) != 0) { painted = true; break; }
            }
            assertTrue("inflated themed drawable must render visible pixels", painted);
        } finally { drawable.setBounds(previous); bitmap.recycle(); }
    }
    private static Intent component(Context context, String name) {
        return new Intent().setComponent(new ComponentName(context.getPackageName(), name));
    }
    private static int id(Context context, String name) {
        int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertNotEquals("runtime resource missing: " + name, 0, id);
        return id;
    }
    private static Context context() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(PACKAGE, context.getPackageName());
        return context;
    }
    private static void assertStoragePrepared(Context context) {
        for (String permission : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}) {
            assertEquals("coordinator must prepare storage grants before release smoke", PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(permission));
        }
    }
    private static void awaitDisplayed(Matcher<View> matcher, long timeoutMillis) {
        awaitView(matcher, timeoutMillis, false);
    }
    private static void awaitAbsent(Matcher<View> matcher, long timeoutMillis) {
        awaitView(matcher, timeoutMillis, true);
    }
    private static void awaitView(Matcher<View> matcher, long timeoutMillis, boolean absent) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        Throwable last = null;
        do {
            try {
                if (absent) onView(matcher).check(doesNotExist());
                else onView(matcher).check(matches(isDisplayed()));
                return;
            } catch (androidx.test.espresso.NoMatchingViewException | AssertionError pending) { last = pending; }
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("actual page did not reach its ready UI state", last);
    }
    private static String tail(String text, int limit) { return text.substring(Math.max(0, text.length() - limit)); }

    /** Stable persisted settings schema only; no references to potentially renamed app classes. */
    private static final class PreferencesScope implements AutoCloseable {
        private final SharedPreferences preferences;
        private final Map<String, Object> old = new HashMap<>();
        PreferencesScope(Context context) {
            preferences = context.getSharedPreferences("CppIDESettings", Context.MODE_PRIVATE);
            Map<String, ?> all = preferences.getAll();
            for (String key : new String[]{"enable_lsp", "auto_save", "remember_last_file", "editor_theme",
                    "compile_commands", "clang_args", "run_mode", "last_file_path", "last_dir_path", "enable_root_run"}) {
                old.put(key, all.get(key));
            }
            assertTrue(preferences.edit().putBoolean("enable_lsp", false).putBoolean("auto_save", false)
                    .putBoolean("remember_last_file", false).putBoolean("enable_root_run", false)
                    .putString("editor_theme", "dark").putString("compile_commands", "[]")
                    .putString("clang_args", "-std=c++17 -O0").commit());
        }
        @Override public void close() {
            SharedPreferences.Editor edit = preferences.edit();
            for (Map.Entry<String, Object> entry : old.entrySet()) {
                Object value = entry.getValue();
                if (value == null) edit.remove(entry.getKey());
                else if (value instanceof String) edit.putString(entry.getKey(), (String) value);
                else if (value instanceof Boolean) edit.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) edit.putInt(entry.getKey(), (Integer) value);
                else throw new AssertionError("unexpected persisted setting type: " + entry.getKey());
            }
            assertTrue("restore release smoke settings", edit.commit());
        }
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
