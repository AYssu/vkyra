package com.vkyra.code.editor;

import com.vkyra.code.editor.EditorActivity;
import com.vkyra.code.settings.AppPreferences;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.vkyra.code.editor.EditorDocument;
import com.vkyra.code.editor.EditorSessionStore;
import com.vkyra.code.editor.EditorSessionViewModel;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EditorSessionRegressionTest {
    @Test public void dirtyTabsAndRunArgumentsSurviveRecreation() throws Exception {
        Fixture f = new Fixture();
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            scenario.onActivity(activity -> {
                assertEquals("int draft_one;", editor(activity).getText().toString());
                set(activity, "runArgs", "one two");
                call(activity, "switchToFile", new Class[]{int.class}, 1);
                assertEquals("int draft_two;", editor(activity).getText().toString());
                editor(activity).setText("int newer_draft;");
            });
            scenario.recreate(); awaitReady(scenario);
            scenario.onActivity(activity -> {
                assertEquals("int newer_draft;", editor(activity).getText().toString());
                assertEquals("one two", get(activity, "runArgs"));
                call(activity, "switchToFile", new Class[]{int.class}, 0);
                assertEquals("int draft_one;", editor(activity).getText().toString());
            });
        }
        assertEquals("int disk_one;", new String(Files.readAllBytes(f.first.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void explicitNavigationWinsOverRecoveredActiveTab() throws Exception {
        Fixture f = new Fixture();
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent().putExtra("file_path", f.second.getPath()))) {
            awaitReady(scenario);
            scenario.onActivity(activity -> {
                assertEquals(f.second, get(activity, "currentFile"));
                assertEquals("int draft_two;", editor(activity).getText().toString());
            });
        }
    }

    @Test public void lateOpenCannotReplaceTheTabSelectedWhileDiskReadWasQueued() throws Exception {
        Fixture f = new Fixture();
        File third = new File(f.project, "third.cpp");
        Files.write(third.toPath(), "int late;".getBytes(StandardCharsets.UTF_8));
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), drained = new CountDownLatch(1);
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            scenario.onActivity(activity -> {
                EditorSessionViewModel owner = (EditorSessionViewModel) get(activity, "editorSession");
                owner.executeIo(() -> { blocked.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
                call(activity, "doOpenFile", new Class[]{File.class}, third);
                call(activity, "switchToFile", new Class[]{int.class}, 1);
                owner.executeIo(drained::countDown);
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(drained.await(10, TimeUnit.SECONDS));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                assertEquals(f.second, get(activity, "currentFile"));
                assertEquals("int draft_two;", editor(activity).getText().toString());
            });
        } finally { release.countDown(); }
    }

    @Test public void savingAnInactiveUntitledDraftPreservesOriginalBuildTarget() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch continued = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<File> built = new java.util.concurrent.atomic.AtomicReference<>();
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            scenario.onActivity(activity -> {
                EditorSessionViewModel owner = (EditorSessionViewModel)get(activity,"editorSession");
                for(EditorDocument document:owner.documents) document.isModified=false;
                set(activity,"isModified",false);
                EditorDocument untitled=new EditorDocument(null); untitled.content="int unsaved;"; untitled.isModified=true;
                owner.documents.add(untitled);
                call(activity,"saveDirtyDocumentsBefore",new Class[]{Runnable.class}, (Runnable)() -> {
                    built.set((File)get(activity,"currentFile")); continued.countDown();
                });
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(com.google.android.material.textfield.TextInputEditText.class))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.replaceText("saved-draft.cpp"),
                            androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1))
                    .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                    .perform(androidx.test.espresso.action.ViewActions.click());
            assertTrue(continued.await(10,TimeUnit.SECONDS));
            assertEquals(f.first,built.get());
            assertEquals("int unsaved;", new String(Files.readAllBytes(new File(f.project,"saved-draft.cpp").toPath()),StandardCharsets.UTF_8));
        }
    }

    @Test public void cancellingSaveAllNeverStartsTheBuild() throws Exception {
        Fixture f = new Fixture(); AtomicBoolean continued = new AtomicBoolean();
        try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            scenario.onActivity(activity -> {
                EditorSessionViewModel owner=(EditorSessionViewModel)get(activity,"editorSession");
                for(EditorDocument document:owner.documents) document.isModified=false;
                set(activity,"isModified",false);
                EditorDocument untitled=new EditorDocument(null);untitled.content="int keep;";untitled.isModified=true;owner.documents.add(untitled);
                call(activity,"saveDirtyDocumentsBefore",new Class[]{Runnable.class},(Runnable)() -> continued.set(true));
            });
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("取消"))
                    .perform(androidx.test.espresso.action.ViewActions.click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertFalse(continued.get());
            scenario.onActivity(activity -> assertEquals("int keep;", editor(activity).getText().toString()));
        }
    }

    private static final class Fixture {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File project, first, second;
        Fixture() throws Exception {
            context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean("enable_lsp", false).putBoolean("auto_save", false).commit();
            project = Files.createTempDirectory(context.getCacheDir().toPath(), "editor-regression-").toFile();
            first = new File(project, "first.cpp"); second = new File(project, "second.cpp");
            Files.write(first.toPath(), "int disk_one;".getBytes(StandardCharsets.UTF_8));
            Files.write(second.toPath(), "int disk_two;".getBytes(StandardCharsets.UTF_8));
            EditorDocument one = new EditorDocument(first.getPath()); one.content="int draft_one;"; one.isModified=true;
            EditorDocument two = new EditorDocument(second.getPath()); two.content="int draft_two;"; two.isModified=true;
            new EditorSessionStore(new File(context.getNoBackupFilesDir(), "editor-sessions"))
                    .write("project:" + project.getAbsolutePath(), EditorSessionStore.Snapshot.copyOf(Arrays.asList(one,two), one.id));
        }
        Intent intent() { return new Intent(context, EditorActivity.class).putExtra("project_path", project.getPath()); }
    }
    private static void awaitReady(ActivityScenario<EditorActivity> scenario) throws Exception {
        AtomicBoolean ready = new AtomicBoolean();
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do {
            scenario.onActivity(activity -> ready.set(Boolean.TRUE.equals(((EditorSessionViewModel)get(activity,"editorSession")).ready().getValue())
                    && get(activity,"currentFile") != null));
            if (ready.get()) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return; }
            Thread.sleep(20);
        } while (System.nanoTime()<deadline);
        fail("editor session was not displayed");
    }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor)get(activity,"codeEditor"); }
    private static Object get(Object owner, String name) { try { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch(Exception e) { throw new AssertionError(e); } }
    private static void set(Object owner, String name,Object value) { try { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner,value); } catch(Exception e) { throw new AssertionError(e); } }
    private static void call(Object owner,String name,Class<?>[] types,Object...args) { try { Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(owner,args); } catch(Exception e) {throw new AssertionError(e);} }
}
