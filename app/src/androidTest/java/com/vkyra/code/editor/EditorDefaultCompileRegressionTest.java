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
import com.vkyra.code.terminal.ManagedProcessRunner;
import com.vkyra.code.toolchain.ClangInstaller;

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

/** Real bundled Clang + editor run dispatch; only terminal navigation and hook side effects are observed. */
@RunWith(AndroidJUnit4.class)
public class EditorDefaultCompileRegressionTest {
    private static final String SOURCE = "#include <stdio.h>\nint main(){puts(\"fresh-source\");return 0;}\n";

    @Test public void twoRunsNeverUseLegacyBinaryAndHooksNameTheActualArtifact() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        assertTrue("Install bundled Clang before this runtime regression", ClangInstaller.isInstalled(context));
        File project = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "compile-regression-").toFile();
        File source = new File(project, "main.cpp"); Files.write(source.toPath(), SOURCE.getBytes(StandardCharsets.UTF_8));
        File tmp = new File(context.getFilesDir(), "tmp"); Files.createDirectories(tmp.toPath());
        SavedFile legacyOutput = new SavedFile(new File(tmp, "a.out"));
        SavedFile legacyCache = new SavedFile(new File(tmp, "temp.c"));
        SharedPreferences prefs = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> previousPreferences = new HashMap<>(prefs.getAll());
        assertTrue(prefs.edit().putBoolean("enable_lsp", false).putBoolean("auto_save", false).commit());
        RecordingHooks hooks = new RecordingHooks(context);
        Field hookField = PluginManager.class.getDeclaredField("hookManager"); hookField.setAccessible(true);
        Object[] previousHooks = new Object[1];
        instrumentation.runOnMainSync(() -> {
            try { previousHooks[0] = hookField.get(null); hookField.set(null, hooks); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
        });
        LinkedBlockingQueue<String> requestedRuns = new LinkedBlockingQueue<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                String output = intent.getStringExtra("RUN_FILE");
                if (output == null) return null;
                requestedRuns.add(output);
                return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            Files.write(legacyOutput.file.toPath(), "#!/system/bin/sh\nprintf stale-binary\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(legacyOutput.file.setExecutable(true));
            // The old implementation would consider this cache valid and run stale-binary.
            Files.write(legacyCache.file.toPath(), SOURCE.getBytes(StandardCharsets.UTF_8));
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(
                    new Intent(context, EditorActivity.class).putExtra("project_path", project.getPath()))) {
                awaitReady(scenario, source);
                String previousOutput = null;
                for (int run = 0; run < 2; run++) {
                    scenario.onActivity(activity -> call(activity, "doDefaultCompile"));
                    String actualOutput = requestedRuns.poll(60, TimeUnit.SECONDS);
                    assertNotNull("No compiled RUN_FILE dispatch for run " + run, actualOutput);
                    assertNotEquals(legacyOutput.file.getAbsolutePath(), actualOutput);
                    assertNotEquals(previousOutput, actualOutput);
                    assertEquals(actualOutput, hooks.before.poll(5, TimeUnit.SECONDS));
                    assertEquals(actualOutput, hooks.success.poll(5, TimeUnit.SECONDS));
                    assertNull("Compilation reported an error", hooks.errors.poll());
                    ManagedProcessRunner.Result executed = new ManagedProcessRunner(null).run(
                            new ProcessBuilder(actualOutput).directory(project), 10000);
                    assertEquals(executed.output + executed.errorOutput, 0, executed.exitCode);
                    assertEquals("fresh-source\n", executed.output);
                    previousOutput = actualOutput;
                }
            }
            assertEquals("#!/system/bin/sh\nprintf stale-binary\n", new String(
                    Files.readAllBytes(legacyOutput.file.toPath()), StandardCharsets.UTF_8));
        } finally {
            instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(() -> {
                try { hookField.set(null, previousHooks[0]); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            SharedPreferences.Editor edit = prefs.edit();
            restoreBoolean(edit, previousPreferences, "enable_lsp");
            restoreBoolean(edit, previousPreferences, "auto_save");
            assertTrue(edit.commit());
            legacyOutput.restore(); legacyCache.restore();
        }
    }

    private static final class RecordingHooks extends PluginHookManager {
        final LinkedBlockingQueue<String> before = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> success = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> errors = new LinkedBlockingQueue<>();
        RecordingHooks(Context context) { super(context); }
        @Override public void executeEvent(Activity activity, String event, Map<String, String> vars, Runnable done) {
            if (EVENT_COMPILE_BEFORE.equals(event)) before.add(vars.get("output_file"));
            if (EVENT_COMPILE_SUCCESS.equals(event)) success.add(vars.get("output_file"));
            if (EVENT_COMPILE_ERROR.equals(event)) errors.add(vars.get("error_message"));
            if (done != null) done.run();
        }
    }
    private static final class SavedFile {
        final File file;
        final byte[] bytes;
        final boolean executable;
        SavedFile(File file) throws Exception {
            this.file = file; bytes = file.exists() ? Files.readAllBytes(file.toPath()) : null;
            executable = file.canExecute();
        }
        void restore() throws Exception {
            if (bytes == null) Files.deleteIfExists(file.toPath());
            else { Files.write(file.toPath(), bytes); assertTrue(file.setExecutable(executable)); }
        }
    }
    private static void restoreBoolean(SharedPreferences.Editor edit, Map<String, ?> previous, String key) {
        if (previous.containsKey(key)) edit.putBoolean(key, (Boolean) previous.get(key)); else edit.remove(key);
    }
    private static void awaitReady(ActivityScenario<EditorActivity> scenario, File source) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            scenario.onActivity(activity -> {
                EditorSessionViewModel owner = (EditorSessionViewModel) get(activity, "editorSession");
                ready.set(Boolean.TRUE.equals(owner.ready().getValue()) && source.equals(get(activity, "currentFile")));
            });
            if (ready.get()) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return; }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Editor did not open regression source");
    }
    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void call(Object owner, String name) {
        try { Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
