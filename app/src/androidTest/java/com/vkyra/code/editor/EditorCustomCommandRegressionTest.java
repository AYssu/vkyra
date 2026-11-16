package com.vkyra.code.editor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.vkyra.code.plugin.PluginHookManager;
import com.vkyra.code.plugin.PluginManager;
import com.vkyra.code.settings.AppPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Exercises the actual editor command entry points; no bundled compiler is required. */
@RunWith(AndroidJUnit4.class)
public class EditorCustomCommandRegressionTest {
    @Test public void customCompileDrainsLargeStderrPreservesHooksAndDispatchesRunCommand() throws Exception {
        try (Fixture fixture = new Fixture()) {
            LinkedBlockingQueue<String> runs = new LinkedBlockingQueue<>();
            Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    String command = intent.getStringExtra("RUN_COMMAND");
                    if (command == null) return null;
                    runs.add(command);
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
                }
            };
            fixture.instrumentation.addMonitor(monitor);
            try (ActivityScenario<EditorActivity> scenario = fixture.launch()) {
                fixture.awaitReady(scenario);
                String runTemplate = "printf '{source}::{output}'";
                File stderrProof = new File(fixture.project, "custom-stderr-proof");
                AppPreferences.CompileCommand command = new AppPreferences.CompileCommand("regression",
                        "printf '%204800s' '' | tr ' ' E | tee '"
                                + stderrProof.getAbsolutePath().replace("'", "'\\''")
                                + "' >&2; printf '编译成功'", runTemplate, true);
                scenario.onActivity(activity -> call(activity, "executeCustomCompileCommand",
                        new Class<?>[]{AppPreferences.CompileCommand.class}, command));
                String output = new File(fixture.context.getFilesDir(), "tmp/a.out").getAbsolutePath();
                assertEquals("printf '" + fixture.source.getAbsolutePath() + "::" + output + "'",
                        runs.poll(20, TimeUnit.SECONDS));
                assertEquals(output, fixture.hooks.before.poll(5, TimeUnit.SECONDS));
                assertEquals(output, fixture.hooks.success.poll(5, TimeUnit.SECONDS));
                assertNull(fixture.hooks.errors.poll());
                assertEquals("The real shell must actually produce 200 KiB before success",204800L,stderrProof.length());
            } finally {
                fixture.instrumentation.removeMonitor(monitor);
            }
        }
    }

    @Test public void symbolShellExpandsCapturedFileProjectAndWorkingDirectory() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = fixture.launch()) {
            fixture.awaitReady(scenario);
            scenario.onActivity(activity -> call(activity, "executeShellCommand", new Class<?>[]{String.class},
                    "printf '%s\\n' '${FILE}' '${DIR}' '${PROJECT}' > shell-result; pwd >> shell-result"));
            File result = new File(fixture.project, "shell-result");
            awaitFile(result);
            String expected = fixture.source.getAbsolutePath() + "\n" + fixture.project.getAbsolutePath()
                    + "\n" + fixture.project.getAbsolutePath() + "\n" + fixture.project.getCanonicalPath() + "\n";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String actual;
            do {
                actual = new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8);
                if (expected.equals(actual)) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertEquals(expected, actual);
        }
    }

    @Test public void destroyingEditorStopsBothCommandEntryPointsBeforeDelayedWritesOrHooks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            LinkedBlockingQueue<String> runs = new LinkedBlockingQueue<>();
            Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    String command = intent.getStringExtra("RUN_COMMAND");
                    if (command == null) return null;
                    runs.add(command);
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
                }
            };
            fixture.instrumentation.addMonitor(monitor);
            try {
                ActivityScenario<EditorActivity> scenario = fixture.launch();
                try {
                    fixture.awaitReady(scenario);
                    scenario.onActivity(activity -> {
                        call(activity, "executeCustomCompileCommandInternal",
                                new Class<?>[]{String.class, String.class, String.class, String.class},
                                fixture.source.getAbsolutePath(), new File(fixture.project, "unused").getPath(),
                                "printf ready > '" + new File(fixture.project, "compile-ready").getPath()
                                        + "'; while [ ! -f '" + new File(fixture.project,"release-command-gate").getPath()
                                        + "' ]; do sleep 0.05; done; printf late > '" + new File(fixture.project, "compile-forbidden").getPath() + "'",
                                "printf forbidden-navigation");
                        call(activity, "executeShellCommand", new Class<?>[]{String.class},
                                "printf ready > shell-ready; while [ ! -f release-command-gate ]; do sleep 0.05; done; printf late > shell-forbidden");
                    });
                    awaitFile(new File(fixture.project, "compile-ready"));
                    awaitFile(new File(fixture.project, "shell-ready"));
                } finally {
                    // Actual Activity destruction triggers the shared command owner's ON_DESTROY path.
                    scenario.close();
                }
                // Destruction requests non-blocking cancellation. Keep the gate closed
                // until the workers have reaped their processes, off the main thread.
                awaitCommandWorkersStopped();
                Files.write(new File(fixture.project,"release-command-gate").toPath(),new byte[]{1});
                Thread.sleep(1000);
                fixture.instrumentation.waitForIdleSync();
                assertFalse(new File(fixture.project, "compile-forbidden").exists());
                assertFalse(new File(fixture.project, "shell-forbidden").exists());
                assertNull("Late compile success hook", fixture.hooks.success.poll());
                assertNull("Cancellation must not show an error on a dead page", fixture.hooks.errors.poll());
                assertNull("Late terminal navigation", runs.poll());
            } finally {
                fixture.instrumentation.removeMonitor(monitor);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final SharedPreferences prefs = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        final Map<String, ?> preferences = new HashMap<>(prefs.getAll());
        final File project;
        final File source;
        final RecordingHooks hooks = new RecordingHooks(context);
        final Field hookField;
        final Object[] previousHooks = new Object[1];

        Fixture() throws Exception {
            project = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "custom-command-ui-").toFile();
            source = new File(project, "main.cpp");
            Files.write(source.toPath(), "int main(){return 0;}\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(prefs.edit().putBoolean("enable_lsp", false).putBoolean("auto_save", false).commit());
            hookField = PluginManager.class.getDeclaredField("hookManager");
            hookField.setAccessible(true);
            instrumentation.runOnMainSync(() -> {
                try { previousHooks[0] = hookField.get(null); hookField.set(null, hooks); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
        }

        ActivityScenario<EditorActivity> launch() {
            return ActivityScenario.launch(new Intent(context, EditorActivity.class)
                    .putExtra("project_path", project.getAbsolutePath()));
        }

        void awaitReady(ActivityScenario<EditorActivity> scenario) throws Exception {
            AtomicBoolean ready = new AtomicBoolean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            do {
                scenario.onActivity(activity -> {
                    EditorSessionViewModel owner = (EditorSessionViewModel) get(activity, "editorSession");
                    ready.set(Boolean.TRUE.equals(owner.ready().getValue()) && source.equals(get(activity, "currentFile")));
                });
                if (ready.get()) { instrumentation.waitForIdleSync(); return; }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            fail("Editor did not load command fixture");
        }

        @Override public void close() {
            instrumentation.runOnMainSync(() -> {
                try { hookField.set(null, previousHooks[0]); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            SharedPreferences.Editor edit = prefs.edit();
            for (String key : new String[]{"enable_lsp", "auto_save"}) {
                if (preferences.containsKey(key)) edit.putBoolean(key, (Boolean) preferences.get(key));
                else edit.remove(key);
            }
            assertTrue(edit.commit());
        }
    }

    private static final class RecordingHooks extends PluginHookManager {
        final LinkedBlockingQueue<String> before = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> success = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> errors = new LinkedBlockingQueue<>();
        RecordingHooks(Context context) { super(context); }
        @Override public void executeEvent(Activity activity, String event, Map<String, String> vars, Runnable done) {
            assertEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
            if (EVENT_COMPILE_BEFORE.equals(event)) before.add(vars.get("output_file"));
            if (EVENT_COMPILE_SUCCESS.equals(event)) success.add(vars.get("output_file"));
            if (EVENT_COMPILE_ERROR.equals(event)) errors.add(vars.get("error_message"));
            if (done != null) done.run();
        }
    }

    private static void awaitFile(File file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("Shell never created " + file, file.exists());
    }

    private static void awaitCommandWorkersStopped() throws Exception {
        Field field = com.vkyra.code.toolchain.CustomCommandTask.class.getDeclaredField("WORKERS");
        field.setAccessible(true);
        java.util.concurrent.ThreadPoolExecutor workers = (java.util.concurrent.ThreadPoolExecutor) field.get(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((workers.getActiveCount() != 0 || !workers.getQueue().isEmpty()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("Destroyed editor's commands must finish process cleanup", 0, workers.getActiveCount());
        assertTrue("Destroyed editor's commands must leave no queued work", workers.getQueue().isEmpty());
    }

    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static void call(Object owner, String name, Class<?>[] parameters, Object... values) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            method.invoke(owner, values);
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
