package com.vkyra.code.editor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.Gson;
import com.vkyra.code.settings.AppPreferences;

import io.github.rosemoe.sora.widget.CodeEditor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * Default execution proves durable restoration with a fresh Activity/ViewModel in one process.
 * The private adb harness kills the still-running prepare invocation externally, then runs restore.
 * A different PID alone is deliberately not presented as evidence of external force-stop.
 */
@RunWith(AndroidJUnit4.class)
public class EditorProcessDeathTest {
    private static final String TAG = "EditorProcessDeathTest";
    private static final Gson GSON = new Gson();
    private static final String DISK = "// original disk: 保留\nint main() { return 0; }\n";
    private static final String FILE_DRAFT = "// dirty source: 中文 😀\nint changed = 42;\n";
    private static final String UNTITLED_DRAFT = "// unsaved buffer: 中文 😀\nint unsaved = 7;\n";
    private static final int FILE_LINE = 1, FILE_COLUMN = 7;
    private static final int UNTITLED_LINE = 1, UNTITLED_COLUMN = 5;

    @Test public void dirtyTabsRecoverFromDurableStorage() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String phase = arguments.getString("phase", "");
        String runId = arguments.getString("run_id", "normal-" + java.util.UUID.randomUUID());
        assertTrue("Invalid run_id", runId.matches("[A-Za-z0-9_-]{1,80}"));
        if ("restore".equals(phase)) {
            Fixture fixture = readFixture(runId);
            assertNotEquals("restore must run in a newly started process", fixture.preparePid, Process.myPid());
            try { restore(fixture); }
            finally { fixture.restorePreferences(); }
            fixture.restorePid = Process.myPid();
            fixture.writeTo(new File(receipt(runId).getParentFile(), runId + ".restored.json"));
            Log.i(TAG, "NEW_PROCESS_RESTORED run_id=" + runId + " pid=" + Process.myPid()
                    + " prepared_pid=" + fixture.preparePid);
            return;
        }
        assertTrue("phase must be prepare, restore, or absent", phase.isEmpty() || "prepare".equals(phase));
        Fixture fixture = new Fixture(runId);
        ActivityScenario<EditorActivity> scenario = null;
        boolean handedToExternalStop = false;
        try {
            scenario = prepare(fixture);
            if ("prepare".equals(phase)) {
                // Do not close/recreate the page or end instrumentation before the external kill.
                // A ready receipt is written only after the real session writer has drained.
                fixture.writeReceipt();
                handedToExternalStop = true;
                Log.i(TAG, "PREPARED_WAITING_FOR_EXTERNAL_STOP run_id=" + runId + " pid=" + Process.myPid());
                if (!new CountDownLatch(1).await(180, TimeUnit.SECONDS)) {
                    handedToExternalStop = false;
                    fail("External force-stop did not arrive within 180 seconds");
                }
                fail("prepare must be interrupted by the external adb harness");
            }
            scenario.close();
            scenario = null;
            restore(fixture);
            Log.i(TAG, "NORMAL_DURABLE_RESTORE_PASS run_id=" + runId);
        } finally {
            if (!handedToExternalStop) {
                if (scenario != null) scenario.close();
                fixture.restorePreferences();
            }
        }
    }

    private static ActivityScenario<EditorActivity> prepare(Fixture fixture) throws Exception {
        ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent());
        boolean success = false;
        try {
            awaitReady(scenario);
            scenario.onActivity(activity -> {
                assertEquals(fixture.source(), get(activity, "currentFile"));
                assertEquals(DISK, editor(activity).getText().toString());
                editor(activity).setText(FILE_DRAFT);
                editor(activity).setSelection(FILE_LINE, FILE_COLUMN);
                call(activity, "createNewFile");
            });
            // displayDocument posts cursor restoration; let it finish before placing our cursor.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            CountDownLatch durable = new CountDownLatch(1);
            scenario.onActivity(activity -> {
                editor(activity).setText(UNTITLED_DRAFT);
                editor(activity).setSelection(UNTITLED_LINE, UNTITLED_COLUMN);
                call(activity, "persistEditorSession");
                EditorSessionViewModel owner = owner(activity);
                assertEquals(2, owner.documents.size());
                fixture.fileId = owner.documents.stream()
                        .filter(document -> fixture.source().getPath().equals(document.filePath))
                        .findFirst().orElseThrow(AssertionError::new).id;
                fixture.untitledId = owner.activeDocumentId;
                assertNotEquals(fixture.fileId, fixture.untitledId);
                owner.executeIo(durable::countDown);
            });
            assertTrue("Timed out waiting for durable session IO", durable.await(20, TimeUnit.SECONDS));
            assertSnapshot(fixture);
            assertDiskUnchanged(fixture);
            success = true;
            return scenario;
        } finally { if (!success) scenario.close(); }
    }

    private static void restore(Fixture fixture) throws Exception {
        assertSnapshot(fixture); // Uses a fresh store; cannot see an old ViewModel's memory.
        assertDiskUnchanged(fixture);
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            awaitReady(scenario);
            scenario.onActivity(activity -> {
                EditorSessionViewModel owner = owner(activity);
                assertEquals(2, owner.documents.size());
                assertEquals(fixture.untitledId, owner.activeDocumentId);
                assertNull(get(activity, "currentFile"));
                assertBuffer(activity, UNTITLED_DRAFT, UNTITLED_LINE, UNTITLED_COLUMN);
                assertDocuments(fixture, EditorSessionStore.Snapshot.copyOf(owner.documents, owner.activeDocumentId));
                int fileIndex = -1;
                for (int i = 0; i < owner.documents.size(); i++) {
                    if (fixture.fileId.equals(owner.documents.get(i).id)) fileIndex = i;
                }
                assertTrue(fileIndex >= 0);
                call(activity, "switchToFile", new Class<?>[]{int.class}, fileIndex);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(fixture.source(), get(activity, "currentFile"));
                assertEquals(fixture.fileId, owner(activity).activeDocumentId);
                assertBuffer(activity, FILE_DRAFT, FILE_LINE, FILE_COLUMN);
            });
        }
        // onPause must persist drafts, and must not silently save either buffer over the source.
        assertDiskUnchanged(fixture);
        assertFalse("Untitled must not become a real source file", new File(fixture.project, "untitled.cpp").exists());
    }

    private static void assertBuffer(EditorActivity activity, String text, int line, int column) {
        assertEquals(text, editor(activity).getText().toString());
        assertEquals(line, editor(activity).getCursor().getLeftLine());
        assertEquals(column, editor(activity).getCursor().getLeftColumn());
        assertEquals(Boolean.TRUE, get(activity, "isModified"));
        assertTrue(owner(activity).activeDocument().isModified);
    }

    private static void assertSnapshot(Fixture fixture) throws Exception {
        EditorSessionStore.Snapshot snapshot = new EditorSessionStore(
                new File(context().getNoBackupFilesDir(), "editor-sessions")).read("project:" + fixture.project);
        assertNotNull("No durable draft snapshot", snapshot);
        assertDocuments(fixture, snapshot);
    }

    private static void assertDocuments(Fixture fixture, EditorSessionStore.Snapshot snapshot) {
        assertEquals(2, snapshot.documents.size());
        assertEquals(fixture.untitledId, snapshot.activeDocumentId);
        EditorDocument file = snapshot.documents.stream().filter(document -> fixture.fileId.equals(document.id))
                .findFirst().orElseThrow(AssertionError::new);
        EditorDocument untitled = snapshot.documents.stream().filter(document -> fixture.untitledId.equals(document.id))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(fixture.fileId, file.id);
        assertEquals(fixture.source().getAbsolutePath(), file.filePath);
        assertEquals(FILE_DRAFT, file.content);
        assertTrue(file.isModified);
        assertEquals(FILE_LINE, file.cursorLine);
        assertEquals(FILE_COLUMN, file.cursorColumn);
        assertEquals(fixture.untitledId, untitled.id);
        assertNull(untitled.filePath);
        assertEquals(UNTITLED_DRAFT, untitled.content);
        assertTrue(untitled.isModified);
        assertEquals(UNTITLED_LINE, untitled.cursorLine);
        assertEquals(UNTITLED_COLUMN, untitled.cursorColumn);
    }

    private static void assertDiskUnchanged(Fixture fixture) throws Exception {
        assertEquals(DISK, new String(Files.readAllBytes(fixture.source().toPath()), StandardCharsets.UTF_8));
    }

    private static final class Fixture {
        String runId, project, fileId, untitledId;
        int preparePid, restorePid;
        long preparedElapsedMs;
        boolean hadAutoSave, previousAutoSave, hadLsp, previousLsp;
        Fixture(String runId) throws Exception {
            this.runId = runId;
            preparePid = Process.myPid();
            SharedPreferences prefs = preferences();
            hadAutoSave = prefs.contains("auto_save"); previousAutoSave = prefs.getBoolean("auto_save", false);
            hadLsp = prefs.contains("enable_lsp"); previousLsp = prefs.getBoolean("enable_lsp", true);
            assertTrue(prefs.edit().putBoolean("auto_save", false).putBoolean("enable_lsp", false).commit());
            project = Files.createTempDirectory(context().getNoBackupFilesDir().toPath(), "process-death-project-")
                    .toFile().getAbsolutePath();
            Files.write(source().toPath(), DISK.getBytes(StandardCharsets.UTF_8));
        }
        File source() { return new File(project, "main.cpp"); }
        Intent intent() { return new Intent(context(), EditorActivity.class).putExtra("project_path", project); }
        void writeReceipt() throws Exception {
            preparedElapsedMs = SystemClock.elapsedRealtime();
            writeTo(receipt(runId));
        }
        void writeTo(File receipt) throws Exception {
            File directory = receipt.getParentFile();
            assertTrue(directory.isDirectory() || directory.mkdirs());
            AtomicFile atomic = new AtomicFile(receipt);
            FileOutputStream output = atomic.startWrite();
            try {
                output.write(GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(output);
            } catch (Exception failure) { atomic.failWrite(output); throw failure; }
        }
        void restorePreferences() {
            SharedPreferences.Editor edit = preferences().edit();
            if (hadAutoSave) edit.putBoolean("auto_save", previousAutoSave); else edit.remove("auto_save");
            if (hadLsp) edit.putBoolean("enable_lsp", previousLsp); else edit.remove("enable_lsp");
            assertTrue("Cannot restore test preferences", edit.commit());
        }
    }

    private static Fixture readFixture(String runId) throws Exception {
        Fixture fixture = GSON.fromJson(new String(Files.readAllBytes(receipt(runId).toPath()),
                StandardCharsets.UTF_8), Fixture.class);
        assertNotNull(fixture);
        assertEquals(runId, fixture.runId);
        assertNotNull(fixture.fileId);
        assertNotNull(fixture.untitledId);
        assertTrue(fixture.preparePid > 0);
        String root = context().getNoBackupFilesDir().getCanonicalPath() + File.separator;
        assertTrue("Fixture must remain under app-private test storage", new File(fixture.project)
                .getCanonicalPath().startsWith(root + "process-death-project-"));
        return fixture;
    }

    private static File receipt(String runId) {
        return new File(context().getNoBackupFilesDir(), "editor-process-death/" + runId + ".json");
    }
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static SharedPreferences preferences() { return context().getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE); }
    private static EditorSessionViewModel owner(EditorActivity activity) { return (EditorSessionViewModel) get(activity, "editorSession"); }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor) get(activity, "codeEditor"); }
    private static void awaitReady(ActivityScenario<EditorActivity> scenario) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            scenario.onActivity(activity -> ready.set(Boolean.TRUE.equals(owner(activity).ready().getValue())
                    && owner(activity).activeDocument() != null));
            if (ready.get()) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return; }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        fail("Editor did not display its restored/initial document");
    }
    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void call(Object owner, String name) { call(owner, name, new Class<?>[0]); }
    private static void call(Object owner, String name, Class<?>[] types, Object... args) {
        try { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(owner, args); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
}
