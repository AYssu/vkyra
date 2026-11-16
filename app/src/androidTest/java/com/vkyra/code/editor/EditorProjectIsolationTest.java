package com.vkyra.code.editor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.vkyra.code.settings.AppPreferences;

import io.github.rosemoe.sora.widget.CodeEditor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real singleTask intent delivery, followed by fresh Activity/ViewModel recovery for both projects. */
@RunWith(AndroidJUnit4.class)
public class EditorProjectIsolationTest {
    @Test public void newIntentSwitchesProjectsWithoutMixingDirtyTabsAndFinishedProjectsReopenIndependently() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicReference<EditorSessionViewModel> firstOwner = new AtomicReference<>();
            AtomicReference<EditorSessionViewModel> secondOwner = new AtomicReference<>();
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.first.intent())) {
                awaitProject(scenario, fixture.first);
                scenario.onActivity(activity -> {
                    firstOwner.set(owner(activity));
                    editBothTabs(activity, fixture.first);
                    // The Android framework invokes onNewIntent on the existing singleTask page.
                    activity.startActivity(fixture.second.intent().addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                });
                awaitProject(scenario, fixture.second);
                scenario.onActivity(activity -> {
                    secondOwner.set(owner(activity));
                    assertNotSame("Projects must not share a retained document owner", firstOwner.get(), owner(activity));
                    assertProject(activity, fixture.second, false);
                    editBothTabs(activity, fixture.second);
                    activity.startActivity(fixture.first.intent().addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                });
                awaitProject(scenario, fixture.first);
                scenario.onActivity(activity -> {
                    assertSame("Returning through onNewIntent must recover A's keyed retained owner", firstOwner.get(), owner(activity));
                    assertProject(activity, fixture.first, true);
                });
                drain(firstOwner.get());
                drain(secondOwner.get());
                assertDurable(fixture, fixture.first);
                assertDurable(fixture, fixture.second);
            }
            // A closed ActivityScenario clears its ViewModelStore. These checks must come from disk.
            try (ActivityScenario<EditorActivity> reopened = ActivityScenario.launch(fixture.second.intent())) {
                awaitProject(reopened, fixture.second);
                reopened.onActivity(activity -> {
                    assertNotSame(secondOwner.get(), owner(activity));
                    assertProject(activity, fixture.second, true);
                });
            }
            try (ActivityScenario<EditorActivity> reopened = ActivityScenario.launch(fixture.first.intent())) {
                awaitProject(reopened, fixture.first);
                reopened.onActivity(activity -> {
                    assertNotSame(firstOwner.get(), owner(activity));
                    assertProject(activity, fixture.first, true);
                });
            }
            assertEquals(fixture.first.disk, read(fixture.first.source));
            assertEquals(fixture.second.disk, read(fixture.second.source));
        }
    }

    private static void editBothTabs(EditorActivity activity, Project project) {
        call(activity, "switchToFile", new Class<?>[]{int.class}, index(owner(activity), project.fileId));
        editor(activity).setText(project.editedFile);
        editor(activity).setSelection(1, 5);
        call(activity, "switchToFile", new Class<?>[]{int.class}, index(owner(activity), project.untitledId));
        editor(activity).setText(project.editedUntitled);
        editor(activity).setSelection(1, project.cursor);
        call(activity, "persistEditorSession", new Class<?>[0]);
        assertProject(activity, project, true);
    }

    private static void assertProject(EditorActivity activity, Project project, boolean edited) {
        EditorSessionViewModel model = owner(activity);
        assertEquals(project.directory.getAbsolutePath(), get(activity, "currentProjectPath"));
        assertEquals(2, model.documents.size());
        EditorDocument source = model.documents.get(index(model, project.fileId));
        EditorDocument untitled = model.documents.get(index(model, project.untitledId));
        assertEquals(project.source.getAbsolutePath(), source.filePath);
        assertNull(untitled.filePath);
        assertTrue(source.isModified);
        assertTrue(untitled.isModified);
        assertEquals(edited ? project.editedFile : project.initialFile, source.content);
        assertEquals(edited ? project.editedUntitled : project.initialUntitled, untitled.content);
        assertEquals(project.untitledId, model.activeDocumentId);
        assertNull(get(activity, "currentFile"));
        assertEquals(untitled.content, editor(activity).getText().toString());
        assertEquals(1, editor(activity).getCursor().getLeftLine());
        assertEquals(project.cursor, editor(activity).getCursor().getLeftColumn());
    }

    private static void assertDurable(Fixture fixture, Project project) throws Exception {
        EditorSessionStore.Snapshot snapshot = fixture.store.read(project.key());
        assertNotNull(snapshot);
        assertEquals(2, snapshot.documents.size());
        assertEquals(project.untitledId, snapshot.activeDocumentId);
        for (EditorDocument document : snapshot.documents) {
            assertTrue(document.isModified);
            if (project.fileId.equals(document.id)) assertEquals(project.editedFile, document.content);
            else {
                assertEquals(project.untitledId, document.id);
                assertNull(document.filePath);
                assertEquals(project.editedUntitled, document.content);
                assertEquals(1, document.cursorLine);
                assertEquals(project.cursor, document.cursorColumn);
            }
        }
    }

    private static void awaitProject(ActivityScenario<EditorActivity> scenario, Project project) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            scenario.onActivity(activity -> ready.set(
                    Boolean.TRUE.equals(owner(activity).ready().getValue())
                            && project.directory.getAbsolutePath().equals(get(activity, "currentProjectPath"))
                            && project.untitledId.equals(owner(activity).activeDocumentId)
                            && get(activity, "sessionLoadingOverlay") == null
                            && editor(activity).getCursor().getLeftLine() == 1
                            && editor(activity).getCursor().getLeftColumn() == project.cursor));
            if (ready.get()) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Project intent/recovery did not reach its own active tab: " + project.directory.getName());
    }

    private static void drain(EditorSessionViewModel owner) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        owner.executeIo(done::countDown);
        assertTrue("Project's durable writer did not finish", done.await(15, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private static int index(EditorSessionViewModel model, String id) {
        for (int i = 0; i < model.documents.size(); i++) if (id.equals(model.documents.get(i).id)) return i;
        throw new AssertionError("Project lost its document identity: " + id);
    }

    private static final class Project {
        final Context context;
        final File directory, source;
        final String disk, initialFile, initialUntitled, editedFile, editedUntitled, fileId, untitledId;
        final int cursor;
        Project(Context context, EditorSessionStore store, String label, int cursor) throws Exception {
            this.context = context;
            this.cursor = cursor;
            directory = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "isolation-" + label + "-").toFile();
            source = new File(directory, "main.cpp");
            disk = "// " + label + " original disk\nint disk_only;\n";
            initialFile = "// " + label + " initial source\nint dirty_source;\n";
            initialUntitled = "// " + label + " initial untitled\nint dirty_untitled;\n";
            editedFile = "// " + label + " changed source 中文 😀\nint changed_source;\n";
            editedUntitled = "// " + label + " changed untitled 中文 😀\nint changed_untitled;\n";
            Files.write(source.toPath(), disk.getBytes(StandardCharsets.UTF_8));
            EditorDocument file = new EditorDocument(source.getAbsolutePath());
            file.content = initialFile; file.isModified = true;
            EditorDocument untitled = new EditorDocument(null);
            untitled.content = initialUntitled; untitled.isModified = true;
            untitled.cursorLine = 1; untitled.cursorColumn = cursor;
            fileId = file.id; untitledId = untitled.id;
            store.write(key(), EditorSessionStore.Snapshot.copyOf(Arrays.asList(file, untitled), untitled.id));
        }
        String key() { return "project:" + directory.getAbsolutePath(); }
        Intent intent() { return new Intent(context, EditorActivity.class).putExtra("project_path", directory.getAbsolutePath()); }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences preferences = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        final Map<String, ?> previous = new HashMap<>(preferences.getAll());
        final EditorSessionStore store = new EditorSessionStore(new File(context.getNoBackupFilesDir(), "editor-sessions"));
        final Project first, second;
        Fixture() throws Exception {
            assertTrue(preferences.edit().putBoolean("enable_lsp", false).putBoolean("auto_save", false).commit());
            first = new Project(context, store, "A", 4);
            second = new Project(context, store, "B", 8);
        }
        @Override public void close() {
            SharedPreferences.Editor edit = preferences.edit();
            for (String key : new String[]{"enable_lsp", "auto_save"}) {
                if (previous.containsKey(key)) edit.putBoolean(key, (Boolean) previous.get(key));
                else edit.remove(key);
            }
            assertTrue(edit.commit());
        }
    }

    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor) get(activity, "codeEditor"); }
    private static EditorSessionViewModel owner(EditorActivity activity) { return (EditorSessionViewModel) get(activity, "editorSession"); }
    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void call(Object owner, String name, Class<?>[] types, Object... values) {
        try { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(owner, values); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
