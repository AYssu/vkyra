package com.vkyra.code.editor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.vkyra.code.R;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.settings.SettingsActivity;
import io.github.rosemoe.sora.lang.brackets.PairedBracket;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorStyleDelegate;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.*;

/** Actual editor widgets/dialogs; fixtures use app-private, unique project directories. */
@RunWith(AndroidJUnit4.class)
public class EditorInteractionTest {
    @Test public void toolbarUndoRedoRestoresEditedTextAndBranchingInvalidatesRedo() throws Exception {
        try (Fixture f = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            scenario.onActivity(a -> {
                CodeEditor e = editor(a);
                e.getText().beginBatchEdit();
                try { e.getText().insert(0, e.getText().getColumnCount(0), " // edited"); }
                finally { e.getText().endBatchEdit(); }
                assertEquals(Fixture.SOURCE + " // edited", e.getText().toString());
                assertTrue(symbol(a, 0).performClick());
                assertEquals(Fixture.SOURCE, e.getText().toString());
                assertTrue(symbol(a, 1).performClick());
                assertEquals(Fixture.SOURCE + " // edited", e.getText().toString());
                symbol(a, 0).performClick();
                e.getText().insert(0, e.getText().getColumnCount(0), " // branch");
                assertFalse(e.canRedo());
                symbol(a, 1).performClick();
                assertEquals(Fixture.SOURCE + " // branch", e.getText().toString());
            });
            assertEquals(Fixture.SOURCE, read(f.first));
        }
    }

    @Test public void searchButtonsWrapAndReplaceOnlyTheSelectedMatchBeforeReplaceAll() throws Exception {
        try (Fixture f = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            scenario.onActivity(a -> {
                editor(a).setText("猫 value\nvalue VALUE value");
                call(a, "showFindReplaceBar", new Class[]{String.class}, "value");
                assertCount(a, "1/3");
                assertEquals(2, editor(a).getCursor().getLeftColumn());
                a.findViewById(R.id.btn_find_prev).performClick();
                assertCount(a, "3/3");
                assertEquals(1, editor(a).getCursor().getLeftLine());
                assertEquals(12, editor(a).getCursor().getLeftColumn());
                a.findViewById(R.id.btn_find_next).performClick();
                assertCount(a, "1/3");
                a.findViewById(R.id.btn_find_next).performClick();
                a.findViewById(R.id.btn_toggle_replace).performClick();
                ((EditText)a.findViewById(R.id.et_replace)).setText("新");
                a.findViewById(R.id.btn_replace_one).performClick();
                assertEquals("猫 value\n新 VALUE value", editor(a).getText().toString());
                assertCount(a, "1/2");
                a.findViewById(R.id.btn_replace_all).performClick();
                assertEquals("猫 新\n新 VALUE 新", editor(a).getText().toString());
                assertCount(a, "0/0");
                a.findViewById(R.id.btn_close_find).performClick();
                assertEquals(View.GONE, a.findViewById(R.id.find_replace_bar).getVisibility());
            });
            assertEquals(Fixture.SOURCE, read(f.first));
        }
    }

    @Test public void insertedCppBlocksProduceBracketHighlightAndNestedGuides() throws Exception {
        String source = "int main() {\n    if (true) {\n        return 0;\n    }\n}\n";
        try (Fixture f = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            scenario.onActivity(a -> {
                CodeEditor e = editor(a);
                e.setText("");
                if (!e.isEditable()) {
                    android.view.inputmethod.InputConnection early = e.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
                    if (early != null) assertFalse("busy layout rejects real keyboard commits", early.commitText(source, 1));
                    assertEquals("rejected input must not mutate a busy layout", "", e.getText().toString());
                }
            });
            // Sora asynchronously initializes its line widths after setText. Its public
            // isEditable contract forbids external mutations while that layout is busy.
            await(scenario, a -> editor(a).isEditable(), "empty document layout did not become editable");
            scenario.onActivity(a -> {
                CodeEditor e = editor(a);
                e.requestFocus();
                android.view.inputmethod.InputConnection input = e.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
                assertNotNull("real keyboard input connection", input);
                assertTrue("keyboard commit accepted after layout completion", input.commitText(source, 1));
                assertEquals("multi-line keyboard input preserves source", source, e.getText().toString());
                int innerBrace = source.indexOf('{', source.indexOf('{') + 1);
                var cursor = e.getText().getIndexer().getCharPosition(innerBrace + 1);
                e.setSelection(cursor.line, cursor.column);
            });
            await(scenario, a -> {
                CodeEditor e = editor(a);
                Styles styles = e.getStyles();
                PairedBracket pair = ((EditorStyleDelegate)get(e, "styleDelegate")).getFoundBracketPair();
                if (styles == null || styles.blocks == null || pair == null) return false;
                boolean outer = false, nested = false;
                for (CodeBlock block : styles.blocks) {
                    // TextMate guides are indentation regions. They end at the bottom
                    // of the body line before the next equal-indent closing brace.
                    if (block.startLine == 0 && block.endLine == 3 && block.toBottomOfEndLine) outer = true;
                    if (block.startLine == 1 && block.endLine == 2 && block.toBottomOfEndLine) nested = true;
                }
                return outer && nested && pair.leftIndex == source.indexOf('{', source.indexOf('{') + 1)
                        && pair.rightIndex == source.indexOf('}');
            }, "C++ analysis did not deliver the nested guide and matching brace to the editor");
            scenario.onActivity(a -> {
                assertTrue(editor(a).isBlockLineEnabled());
                assertTrue(editor(a).isHighlightBracketPair());
                assertEquals(source, editor(a).getText().toString());
            });
        }
    }

    @Test public void themeChosenOnSettingsPageIsAppliedWhenEditorResumes() throws Exception {
        try (Fixture f = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            await(scenario, a -> editor(a).isEditable(), "editor layout did not become editable");
            AtomicInteger darkBackground = new AtomicInteger(), editorResumeCount = new AtomicInteger();
            AtomicReference<EditorActivity> originalEditor = new AtomicReference<>();
            AtomicReference<SettingsActivity> settingsActivity = new AtomicReference<>();
            CountDownLatch settingsResumed = new CountDownLatch(1);
            String draft = "// unsaved theme draft\n" + Fixture.SOURCE;
            ActivityLifecycleCallback callback = (activity, stage) -> {
                if (stage == Stage.RESUMED && activity instanceof SettingsActivity) {
                    settingsActivity.set((SettingsActivity) activity);
                    settingsResumed.countDown();
                }
                if (stage == Stage.RESUMED && activity instanceof EditorActivity
                        && f.project.getPath().equals(activity.getIntent().getStringExtra("project_path"))) {
                    editorResumeCount.incrementAndGet();
                }
            };
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback));
            try {
                scenario.onActivity(a -> {
                    originalEditor.set(a);
                    darkBackground.set(editor(a).getColorScheme().getColor(EditorColorScheme.WHOLE_BACKGROUND));
                    editor(a).getText().insert(0, 0, "// unsaved theme draft\n");
                    // Launch from the actual editor task, preserving the real back stack.
                    a.startActivity(new Intent(a, SettingsActivity.class));
                });
                assertTrue("settings resumed in editor's task", settingsResumed.await(10, TimeUnit.SECONDS));
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    SettingsActivity a = settingsActivity.get();
                    assertNotNull(a);
                    a.getSupportFragmentManager().executePendingTransactions();
                    SettingsActivity.SettingsFragment fragment = (SettingsActivity.SettingsFragment)
                            a.getSupportFragmentManager().findFragmentById(R.id.settings_container);
                    assertNotNull(fragment);
                    assertNotNull(fragment.findPreference(AppPreferences.KEY_EDITOR_THEME));
                    fragment.findPreference(AppPreferences.KEY_EDITOR_THEME).performClick();
                });
                onView(withText("亮色主题")).perform(click());
                assertEquals("light", AppPreferences.getEditorTheme(f.context));
                androidx.test.espresso.Espresso.pressBack();
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                awaitReady(scenario);
                await(scenario, a -> Boolean.FALSE.equals(get(a, "currentThemeIsDark")) && editor(a).isEditable(),
                        "light theme was not applied after returning from settings");
                scenario.onActivity(a -> {
                    assertTrue("editor actually resumed from settings", editorResumeCount.get() > 0);
                    assertNotSame("theme change recreates the editor through its normal onResume", originalEditor.get(), a);
                    assertNotEquals(darkBackground.get(), editor(a).getColorScheme().getColor(EditorColorScheme.WHOLE_BACKGROUND));
                    assertEquals(draft, editor(a).getText().toString());
                    assertTrue(((EditorSessionViewModel)get(a, "editorSession")).activeDocument().isModified);
                });
                assertEquals("theme navigation must preserve unsaved disk contents", Fixture.SOURCE, read(f.first));
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback));
            }
        }
    }

    @Test public void cleanSourceReachesExportOverwritePromptAndCancelPreservesExistingApk() throws Exception {
        try (Fixture f = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(f.intent())) {
            awaitReady(scenario);
            File destination = new File(f.project, "UiExport_v1.0.apk");
            byte[] original = {1, 7, 9};
            Files.write(destination.toPath(), original);
            scenario.onActivity(a -> {
                EditorSessionViewModel session = (EditorSessionViewModel)get(a, "editorSession");
                for (EditorDocument document : session.documents) document.isModified = false;
                set(a, "isModified", false);
                call(a, "generateApk", new Class[]{String.class,String.class,int.class,String.class,android.graphics.Bitmap.class,String.class},
                        "UiExport", "com.vkyra.exporttest", 1, "1.0", null, null);
            });
            onView(withText("覆盖已有 APK？")).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText("取消")).inRoot(isDialog()).perform(click());
            assertArrayEquals(original, Files.readAllBytes(destination.toPath()));
        }
    }

    private static void assertCount(EditorActivity a, String expected) {
        assertEquals(expected, ((TextView)a.findViewById(R.id.tv_find_count)).getText().toString());
    }
    private static View symbol(EditorActivity a, int index) {
        return ((ViewGroup)a.findViewById(R.id.symbol_input)).getChildAt(index);
    }

    static final class Fixture implements AutoCloseable {
        static final String SOURCE = "int main() { return 0; }";
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final PrefsBackup settings = new PrefsBackup(context, AppPreferences.PREFS_NAME,
                "enable_lsp", "auto_save", "editor_theme", "symbol_rows", "remember_last_file", "last_file_path", "last_dir_path");
        final PrefsBackup symbols = new PrefsBackup(context, "akode_settings", "symbol_key_configs");
        final File project, first;
        Fixture() throws Exception {
            settings.prefs.edit().putBoolean("enable_lsp",false).putBoolean("auto_save",false)
                    .putString("editor_theme","dark").putInt("symbol_rows",1).putBoolean("remember_last_file",false).commit();
            SymbolKeyConfig.saveConfigs(context, Arrays.asList(
                    new SymbolKeyConfig.KeyConfig("undo", SymbolKeyConfig.ACTION_UNDO, ""),
                    new SymbolKeyConfig.KeyConfig("redo", SymbolKeyConfig.ACTION_REDO, ""),
                    new SymbolKeyConfig.KeyConfig("{", SymbolKeyConfig.ACTION_INSERT, "{")));
            project = Files.createTempDirectory(context.getCacheDir().toPath(), "editor-interaction-").toFile();
            first = new File(project,"main.cpp");
            Files.write(first.toPath(),SOURCE.getBytes(StandardCharsets.UTF_8));
            EditorDocument document = new EditorDocument(first.getPath());
            document.content = SOURCE;
            new EditorSessionStore(new File(context.getNoBackupFilesDir(),"editor-sessions"))
                    .write("project:"+project.getAbsolutePath(), EditorSessionStore.Snapshot.copyOf(Arrays.asList(document),document.id));
        }
        Intent intent() { return new Intent(context,EditorActivity.class).putExtra("project_path",project.getPath()); }
        @Override public void close() throws Exception { settings.close(); symbols.close(); removeTree(project); }
    }
    static final class PrefsBackup implements AutoCloseable {
        final SharedPreferences prefs;
        final Map<String,Object> old = new HashMap<>();
        PrefsBackup(Context context,String name,String...keys) {
            prefs = context.getSharedPreferences(name,Context.MODE_PRIVATE);
            Map<String,?> all = prefs.getAll();
            for(String key:keys) old.put(key,all.get(key));
        }
        @Override public void close() {
            SharedPreferences.Editor edit=prefs.edit();
            for(Map.Entry<String,Object> entry:old.entrySet()) {
                String key=entry.getKey();Object value=entry.getValue();
                if(value==null) edit.remove(key);
                else if(value instanceof String) edit.putString(key,(String)value);
                else if(value instanceof Boolean) edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer) edit.putInt(key,(Integer)value);
                else throw new AssertionError("Unexpected preference type for "+key);
            }
            assertTrue(edit.commit());
        }
    }
    static void awaitReady(ActivityScenario<EditorActivity> scenario) throws Exception {
        await(scenario,a -> Boolean.TRUE.equals(((EditorSessionViewModel)get(a,"editorSession")).ready().getValue())
                && get(a,"currentFile")!=null,"editor session did not become ready");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    static void await(ActivityScenario<EditorActivity> scenario, Predicate<EditorActivity> condition,String message) throws Exception {
        AtomicBoolean result=new AtomicBoolean();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do {
            scenario.onActivity(a -> result.set(condition.test(a)));
            if(result.get()) return;
            Thread.sleep(25);
        } while(System.nanoTime()<deadline);
        fail(message);
    }
    static CodeEditor editor(EditorActivity activity) { return (CodeEditor)get(activity,"codeEditor"); }
    static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8); }
    static Object get(Object owner,String name) {
        try {
            Class<?> type=owner.getClass();
            while(type!=null) {
                try { Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner); }
                catch(NoSuchFieldException absent) { type=type.getSuperclass(); }
            }
            throw new NoSuchFieldException(name);
        } catch(Exception error) { throw new AssertionError(error); }
    }
    static void set(Object owner,String name,Object value) {
        try { Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    static void call(Object owner,String name,Class<?>[] types,Object...args) {
        try { Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(owner,args); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    static void removeTree(File root) throws Exception {
        if(!root.exists()) return;
        Files.walkFileTree(root.toPath(),new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path path,java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path path,java.io.IOException failure) throws java.io.IOException {
                if(failure!=null) throw failure;Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
