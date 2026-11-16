package com.vkyra.code.editor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.vkyra.code.R;
import com.vkyra.code.settings.AppPreferences;

import io.github.rosemoe.sora.widget.CodeEditor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Blocks the real session IO queue before onCreate/load or before the first source read.
 * No production test hook and no reflective call to showSessionLoading is used.
 */
@RunWith(AndroidJUnit4.class)
public class EditorRecoveryLoadingTest {
    private static final String DISK = "// disk remains unchanged\nint original = 1;\n";
    private static final String DRAFT = "// 完整恢复的草稿 😀\nint draft = 42;\n// trailing line\n";

    @Test public void delayedDraftRecoveryRejectsInputAndToolbarTouchesThenRestoresEditableDraft() throws Exception {
        try (Fixture fixture = new Fixture(true); DelayedIo delay = new DelayedIo(fixture, false)) {
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
                delay.awaitBlocked();
                scenario.onActivity(activity -> {
                    assertSame("onCreate must reuse the pre-created real owner", delay.owner.get(), owner(activity));
                    assertFalse(Boolean.TRUE.equals(owner(activity).ready().getValue()));
                    assertTrue(owner(activity).documents.isEmpty());
                    assertLoadingRejectsInteraction(activity);
                });
                delay.release.countDown();
                awaitDisplayed(scenario);
                scenario.onActivity(activity -> {
                    assertEquals(DRAFT, editor(activity).getText().toString());
                    assertEquals(DRAFT, owner(activity).activeDocument().content);
                    assertEquals(fixture.source.getAbsolutePath(), owner(activity).activeDocument().filePath);
                    assertTrue(owner(activity).activeDocument().isModified);
                    assertEditableInputWorks(activity);
                });
                assertEquals(DISK, read(fixture.source));
            } finally { delay.release.countDown(); }
        }
    }

    @Test public void delayedInitialFileReadKeepsLoadingUntilSourceIsActuallyDisplayed() throws Exception {
        try (Fixture fixture = new Fixture(false); DelayedIo delay = new DelayedIo(fixture, true)) {
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
                delay.awaitBlocked();
                awaitInitialReadQueued(scenario);
                scenario.onActivity(activity -> {
                    assertTrue(Boolean.TRUE.equals(owner(activity).ready().getValue()));
                    assertNull("snapshot ready is not yet a displayed source", owner(activity).activeDocument());
                    assertLoadingRejectsInteraction(activity);
                });
                delay.release.countDown();
                awaitDisplayed(scenario);
                scenario.onActivity(activity -> {
                    assertEquals(DISK, editor(activity).getText().toString());
                    assertEquals(fixture.source, get(activity, "currentFile"));
                    assertEditableInputWorks(activity);
                });
                assertEquals(DISK, read(fixture.source));
            } finally { delay.release.countDown(); }
        }
    }

    @Test public void failedInitialReadCreatesEditableFallbackInsteadOfPermanentLoading() throws Exception {
        try (Fixture fixture = new Fixture(false); DelayedIo delay = new DelayedIo(fixture, true)) {
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
                delay.awaitBlocked();
                awaitInitialReadQueued(scenario);
                scenario.onActivity(EditorRecoveryLoadingTest::assertLoadingRejectsInteraction);
                // Selection/existence checks already happened; the real queued read now fails.
                Files.delete(fixture.source.toPath());
                delay.release.countDown();
                awaitDisplayed(scenario);
                scenario.onActivity(activity -> {
                    assertNull("failed path must not become the fallback's save target", get(activity, "currentFile"));
                    assertNull(owner(activity).activeDocument().filePath);
                    assertTrue(owner(activity).activeDocument().isModified);
                    assertEquals(1, owner(activity).documents.size());
                    assertEditableInputWorks(activity);
                });
                assertFalse("fallback must not silently recreate or overwrite the missing source", fixture.source.exists());
            } finally { delay.release.countDown(); }
        }
    }

    private static void assertLoadingRejectsInteraction(EditorActivity activity) {
        CodeEditor editor = editor(activity);
        View overlay = (View) get(activity, "sessionLoadingOverlay");
        assertFalse("loading editor must reject input", editor.isEditable());
        assertNotNull("loading must shield the whole page", overlay);
        assertTrue(overlay.isShown());
        assertTrue(overlay.isClickable());
        assertTrue("loading layer owns keyboard focus", overlay.hasFocus());
        ViewGroup parent = (ViewGroup) overlay.getParent();
        assertSame("shield is above editor and toolbar", overlay, parent.getChildAt(parent.getChildCount() - 1));
        String before = editor.getText().toString();
        InputConnection connection = editor.onCreateInputConnection(new EditorInfo());
        if (connection != null) connection.commitText("EARLY_IME_INPUT", 1);
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
        assertEquals("early keyboard input must not create a lost draft", before, editor.getText().toString());

        // Dispatch genuine page coordinates. A direct performClick would bypass the shield.
        View toolbar = activity.findViewById(R.id.tv_title);
        AtomicInteger reachedToolbar = new AtomicInteger();
        toolbar.setOnTouchListener((view, event) -> { reachedToolbar.incrementAndGet(); return true; });
        try { tap(activity, toolbar); }
        finally { toolbar.setOnTouchListener(null); }
        assertEquals("loading must intercept toolbar actions", 0, reachedToolbar.get());
        assertTrue("loading must not create a replacement document", owner(activity).documents.isEmpty());
    }

    private static void assertEditableInputWorks(EditorActivity activity) {
        CodeEditor editor = editor(activity);
        assertNull("loading shield must be removed", get(activity, "sessionLoadingOverlay"));
        assertTrue("editor must unlock after success or fallback", editor.isEditable());
        // Positive control: the same real toolbar touch reaches its target once unshielded.
        View toolbarTitle = activity.findViewById(R.id.tv_title);
        AtomicInteger reachedToolbar = new AtomicInteger();
        toolbarTitle.setOnTouchListener((view, event) -> { reachedToolbar.incrementAndGet(); return true; });
        try { tap(activity, toolbarTitle); }
        finally { toolbarTitle.setOnTouchListener(null); }
        assertTrue("toolbar actions become reachable after loading", reachedToolbar.get() > 0);
        String before = editor.getText().toString();
        editor.setSelection(0, 0);
        editor.commitText("// typed after loading\n");
        assertEquals("// typed after loading\n" + before, editor.getText().toString());
    }

    private static void tap(Activity activity, View target) {
        int[] targetPosition = new int[2], decorPosition = new int[2];
        target.getLocationOnScreen(targetPosition);
        activity.getWindow().getDecorView().getLocationOnScreen(decorPosition);
        assertTrue("toolbar must be laid out for a real touch", target.getWidth() > 0 && target.getHeight() > 0);
        float x = targetPosition[0] - decorPosition[0] + target.getWidth() / 2f;
        float y = targetPosition[1] - decorPosition[1] + target.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x, y, 0);
        try { activity.dispatchTouchEvent(down); activity.dispatchTouchEvent(up); }
        finally { down.recycle(); up.recycle(); }
    }

    private static final class DelayedIo implements AutoCloseable, ActivityLifecycleCallback {
        final Fixture fixture;
        final boolean beforeSourceRead;
        final CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicReference<EditorSessionViewModel> owner = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Observer<Boolean> readyObserver;
        DelayedIo(Fixture fixture, boolean beforeSourceRead) {
            this.fixture = fixture; this.beforeSourceRead = beforeSourceRead;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(this));
        }
        @Override public void onActivityLifecycleChanged(Activity activity, Stage stage) {
            if (stage != Stage.PRE_ON_CREATE || !(activity instanceof EditorActivity)
                    || !fixture.project.getAbsolutePath().equals(activity.getIntent().getStringExtra("project_path"))) return;
            try {
                EditorSessionViewModel model = new ViewModelProvider((EditorActivity) activity,
                        new ViewModelProvider.AndroidViewModelFactory(activity.getApplication()))
                        .get(fixture.sessionKey(), EditorSessionViewModel.class);
                assertTrue("one owner per launch", owner.compareAndSet(null, model));
                if (beforeSourceRead) {
                    readyObserver = ready -> {
                        if (Boolean.TRUE.equals(ready)) {
                            model.ready().removeObserver(readyObserver);
                            model.executeIo(this::blockIo);
                        }
                    };
                    model.ready().observeForever(readyObserver);
                } else model.executeIo(this::blockIo);
            } catch (Throwable error) { failure.set(error); blocked.countDown(); }
        }
        private void blockIo() {
            blocked.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) failure.compareAndSet(null, new AssertionError("Controlled IO delay expired"));
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); failure.compareAndSet(null, error); }
        }
        void awaitBlocked() throws Exception {
            assertTrue("real session IO did not reach controlled delay", blocked.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Cannot control real restoration IO", failure.get());
            assertNotNull(owner.get());
        }
        @Override public void close() {
            release.countDown();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(this);
                EditorSessionViewModel model = owner.get();
                if (model != null && readyObserver != null) model.ready().removeObserver(readyObserver);
            });
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences preferences = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        final java.util.Map<String, Boolean> previous = new java.util.HashMap<>();
        final File project, source;
        Fixture(boolean recoveredDraft) throws Exception {
            for (String key : new String[]{"enable_lsp", "auto_save", "remember_last_file"}) {
                previous.put(key, preferences.contains(key) ? preferences.getBoolean(key, false) : null);
            }
            assertTrue(preferences.edit().putBoolean("enable_lsp", false).putBoolean("auto_save", false)
                    .putBoolean("remember_last_file", false).commit());
            project = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "recovery-loading-").toFile();
            source = new File(project, "main.cpp");
            Files.write(source.toPath(), DISK.getBytes(StandardCharsets.UTF_8));
            if (recoveredDraft) {
                EditorDocument document = new EditorDocument(source.getAbsolutePath());
                document.content = DRAFT; document.isModified = true;
                new EditorSessionStore(new File(context.getNoBackupFilesDir(), "editor-sessions"))
                        .write(sessionKey(), EditorSessionStore.Snapshot.copyOf(Collections.singletonList(document), document.id));
            }
        }
        String sessionKey() { return "project:" + project.getAbsolutePath(); }
        Intent intent() { return new Intent(context, EditorActivity.class).putExtra("project_path", project.getAbsolutePath()); }
        @Override public void close() {
            SharedPreferences.Editor edit = preferences.edit();
            for (java.util.Map.Entry<String, Boolean> entry : previous.entrySet()) {
                if (entry.getValue() == null) edit.remove(entry.getKey()); else edit.putBoolean(entry.getKey(), entry.getValue());
            }
            assertTrue("restore test preferences", edit.commit());
        }
    }

    private static void awaitInitialReadQueued(ActivityScenario<EditorActivity> scenario) throws Exception {
        await(scenario, activity -> Boolean.TRUE.equals(owner(activity).ready().getValue())
                && ((Long) get(activity, "openRequestVersion")) > 0, "initial source read was not queued");
    }
    private static void awaitDisplayed(ActivityScenario<EditorActivity> scenario) throws Exception {
        await(scenario, activity -> owner(activity).activeDocument() != null && editor(activity).isEditable()
                && get(activity, "sessionLoadingOverlay") == null, "editor remained locked after IO completed");
    }
    private static void await(ActivityScenario<EditorActivity> scenario, java.util.function.Predicate<EditorActivity> condition,
            String message) throws Exception {
        AtomicBoolean complete = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            scenario.onActivity(activity -> complete.set(condition.test(activity)));
            if (complete.get()) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return; }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail(message);
    }
    private static EditorSessionViewModel owner(EditorActivity activity) { return (EditorSessionViewModel) get(activity, "editorSession"); }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor) get(activity, "codeEditor"); }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static Object get(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
