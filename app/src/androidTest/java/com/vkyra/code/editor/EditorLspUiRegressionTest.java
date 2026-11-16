package com.vkyra.code.editor;

import com.vkyra.code.lsp.ClangdClient;

import com.vkyra.code.editor.EditorActivity;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.toolchain.ClangFormatter;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.vkyra.code.editor.EditorDocument;
import com.vkyra.code.editor.EditorSessionStore;
import com.vkyra.code.editor.EditorSessionViewModel;
import com.vkyra.code.lsp.LspConnection;
import io.github.rosemoe.sora.widget.CodeEditor;
import java.io.File;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real editor interactions and real LSP4J wire messages; no installed compiler/server required. */
@RunWith(AndroidJUnit4.class)
public class EditorLspUiRegressionTest {
    @Test public void saveAsClosesOldLspUriBeforeOpeningTheNewPath() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            awaitReady(scenario);
            try (FakeSession lsp = FakeSession.attach(fixture, scenario)) {
                WireEvent initial = lsp.server.await("open", fixture.first);
                assertEquals(fixture.firstDraft, initial.text);
                DialogTestUi.awaitActivityFocus(scenario);
                scenario.onActivity(activity -> call(activity,"showSaveAsDialog",new Class<?>[0]));
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(
                        com.google.android.material.textfield.TextInputEditText.class))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(
                        androidx.test.espresso.action.ViewActions.replaceText("saved+copy.cpp"),
                        androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
                androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("保存"))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                        .perform(androidx.test.espresso.action.ViewActions.click());
                File saved = new File(fixture.project,"saved+copy.cpp");
                WireEvent closed = lsp.server.await("close",fixture.first);
                WireEvent opened = lsp.server.await("open",saved);
                assertTrue("didClose(old) must precede didOpen(new)",closed.sequence < opened.sequence);
                assertEquals(fixture.firstDraft,opened.text);
                assertEquals(fixture.firstDraft,read(saved));
                assertEquals(fixture.firstDisk,read(fixture.first));
                scenario.onActivity(activity -> {
                    assertEquals(saved,get(activity,"currentFile"));
                    assertEquals(fixture.firstDraft,editor(activity).getText().toString());
                });
            }
        }
    }

    @Test public void delayedFormatCannotOverwriteTheTabSelectedAfterRequest() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            awaitReady(scenario);
            AtomicReference<ClangFormatter.FormatCallback> pending = new AtomicReference<>();
            scenario.onActivity(activity -> {
                injectFormatter(activity,pending);
                call(activity,"formatCode",new Class<?>[0]);
                assertNotNull("Actual format action must request work",pending.get());
                call(activity,"switchToFile",new Class<?>[]{int.class},1);
                assertEquals(fixture.secondDraft,editor(activity).getText().toString());
                pending.getAndSet(null).onSuccess("int formatted_from_first;\n");
                assertEquals(fixture.secondDraft,editor(activity).getText().toString());
                assertEquals(fixture.firstDraft,owner(activity).documents.get(0).content);
                // Positive control: the same completion mechanism applies when identity/content still match.
                call(activity,"formatCode",new Class<?>[0]);
                pending.getAndSet(null).onSuccess("int formatted_second;\n");
                assertEquals("int formatted_second;\n",editor(activity).getText().toString());
            });
        }
    }

    @Test public void delayedFormatCannotOverwriteNewInputInTheSameDocument() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            awaitReady(scenario);
            AtomicReference<ClangFormatter.FormatCallback> pending = new AtomicReference<>();
            scenario.onActivity(activity -> {
                injectFormatter(activity,pending);
                call(activity,"formatCode",new Class<?>[0]);
                assertNotNull(pending.get());
                String newer = fixture.firstDraft + "int typed_after_request;\n";
                int lastLine = editor(activity).getText().getLineCount() - 1;
                editor(activity).getText().insert(lastLine, editor(activity).getText().getColumnCount(lastLine), "int typed_after_request;\n");
                pending.getAndSet(null).onSuccess("int stale_format;\n");
                assertEquals(newer,editor(activity).getText().toString());
                call(activity,"formatCode",new Class<?>[0]);
                pending.getAndSet(null).onSuccess("int current_format;\n");
                assertEquals("int current_format;\n",editor(activity).getText().toString());
            });
        }
    }

    @Test public void pendingDiagnosticsAndOldTooltipsCannotCrossTheTabBoundary() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            awaitReady(scenario);
            try (FakeSession lsp = FakeSession.attach(fixture,scenario)) {
                lsp.server.await("open",fixture.first);
                lsp.publish(fixture.first,"first-visible");
                awaitMessage(scenario,"first-visible");
                CountDownLatch pendingThenSwitched = new CountDownLatch(1);
                lsp.afterDiagnostics.set(() -> {
                    // Runs in the actual client's main-thread diagnostics delivery, before debounce expires.
                    lsp.afterDiagnostics.set(null);
                    scenarioActivity(lsp,activity -> call(activity,"switchToFile",new Class<?>[]{int.class},1));
                    pendingThenSwitched.countDown();
                });
                lsp.publish(fixture.first,"first-late");
                assertTrue(pendingThenSwitched.await(5,TimeUnit.SECONDS));
                awaitDebounceFence();
                scenario.onActivity(activity -> {
                    assertEquals(fixture.second,get(activity,"currentFile"));
                    assertTrue("A's old tooltip or debounced diagnostics leaked into B",messages(activity).isEmpty());
                });
                lsp.publish(fixture.second,"second-current");
                awaitMessage(scenario,"second-current");
                scenario.onActivity(activity -> assertEquals(1,messages(activity).size()));
            }
        }
    }

    @Test public void serverWorkspaceEditUsesDirtyBuffersUtf16AndPlusUrisAcrossFiles() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            awaitReady(scenario);
            try (FakeSession lsp = FakeSession.attach(fixture,scenario)) {
                lsp.server.await("open",fixture.first);
                Map<String,List<TextEdit>> changes = new LinkedHashMap<>();
                // UTF-16 column 6 follows the supplementary character at columns 3..5.
                changes.put(LspConnection.fileUri(fixture.first),Collections.singletonList(edit(0,6,11,"omega")));
                // An encoded '+' denotes the same literal '+' path; form-style URL decoding is wrong here.
                changes.put(LspConnection.fileUri(fixture.third).replace("+","%2B"),Collections.singletonList(edit(0,6,10,"gamma")));
                ApplyWorkspaceEditResponse reply = lsp.remote.applyEdit(new ApplyWorkspaceEditParams(new WorkspaceEdit(changes)))
                        .get(8,TimeUnit.SECONDS);
                assertTrue("Editor rejected valid multi-file edit",reply.isApplied());
                scenario.onActivity(activity -> {
                    assertEquals("// 😀 omega\nint draft_first;\n",editor(activity).getText().toString());
                    assertEquals(3,owner(activity).documents.size());
                    EditorDocument first = document(activity,fixture.first), third = document(activity,fixture.third);
                    assertTrue(first.isModified); assertTrue(third.isModified);
                    assertEquals("// 😀 gamma\nint disk_third;\n",third.content);
                    assertEquals(fixture.secondDraft,document(activity,fixture.second).content);
                    call(activity,"switchToFile",new Class<?>[]{int.class},owner(activity).documents.indexOf(third));
                    assertEquals(third.content,editor(activity).getText().toString());
                });
                assertEquals(fixture.firstDisk,read(fixture.first)); assertEquals(fixture.thirdDisk,read(fixture.third));
                // Recovery proves the result belongs to recoverable dirty documents, not only the displayed widget.
                fixture.enableLsp(false);
                scenario.recreate(); awaitReady(scenario);
                scenario.onActivity(activity -> {
                    assertEquals("// 😀 gamma\nint disk_third;\n",editor(activity).getText().toString());
                    assertTrue(document(activity,fixture.first).isModified);
                    assertEquals("// 😀 omega\nint draft_first;\n",document(activity,fixture.first).content);
                });
            }
        }
    }

    @Test public void workspaceEditSplittingSurrogateRejectsTheWholeTransaction() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            awaitReady(scenario);
            try (FakeSession lsp = FakeSession.attach(fixture,scenario)) {
                lsp.server.await("open",fixture.first);
                Map<String,List<TextEdit>> changes = new LinkedHashMap<>();
                changes.put(LspConnection.fileUri(fixture.first),Collections.singletonList(edit(0,6,11,"would_change")));
                changes.put(LspConnection.fileUri(fixture.third),Collections.singletonList(edit(0,4,5,"broken")));
                ApplyWorkspaceEditResponse reply = lsp.remote.applyEdit(new ApplyWorkspaceEditParams(new WorkspaceEdit(changes)))
                        .get(8,TimeUnit.SECONDS);
                assertFalse("Half of a surrogate pair was accepted",reply.isApplied());
                scenario.onActivity(activity -> {
                    assertEquals(fixture.firstDraft,editor(activity).getText().toString());
                    assertEquals(fixture.firstDraft,document(activity,fixture.first).content);
                    assertEquals(2,owner(activity).documents.size());
                });
                assertEquals(fixture.firstDisk,read(fixture.first)); assertEquals(fixture.thirdDisk,read(fixture.third));
            }
        }
    }

    private static TextEdit edit(int line,int start,int end,String replacement) {
        return new TextEdit(new Range(new Position(line,start),new Position(line,end)),replacement);
    }
    private static void injectFormatter(EditorActivity activity,AtomicReference<ClangFormatter.FormatCallback> pending) {
        set(activity,"formatRequest",(BiConsumer<String,ClangFormatter.FormatCallback>)(text,callback) -> {
            assertEquals(editor(activity).getText().toString(),text); pending.set(callback);
        });
    }
    private static void scenarioActivity(FakeSession lsp,java.util.function.Consumer<EditorActivity> action) {
        EditorActivity activity = lsp.activity.get(); assertNotNull(activity); action.accept(activity);
    }
    private static EditorSessionViewModel owner(EditorActivity activity) { return (EditorSessionViewModel)get(activity,"editorSession"); }
    private static CodeEditor editor(EditorActivity activity) { return (CodeEditor)get(activity,"codeEditor"); }
    private static List<?> messages(EditorActivity activity) { return (List<?>)get(activity,"diagnosticMessages"); }
    private static EditorDocument document(EditorActivity activity,File file) {
        for(EditorDocument document:owner(activity).documents) if(file.getPath().equals(document.filePath)) return document;
        throw new AssertionError("Missing editor document: "+file.getName());
    }
    private static void awaitMessage(ActivityScenario<EditorActivity> scenario,String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); AtomicBoolean found=new AtomicBoolean();
        do {
            scenario.onActivity(activity -> {
                for(Object message:messages(activity)) if(expected.equals(get(message,"message"))) found.set(true);
            });
            if(found.get()) return;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        fail("Expected real debounced diagnostic: "+expected);
    }
    private static void awaitDebounceFence() throws Exception {
        CountDownLatch elapsed=new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).postDelayed(elapsed::countDown,200);
        assertTrue(elapsed.await(3,TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static void awaitReady(ActivityScenario<EditorActivity> scenario) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15); AtomicBoolean ready=new AtomicBoolean();
        do {
            scenario.onActivity(activity -> ready.set(Boolean.TRUE.equals(owner(activity).ready().getValue())&&get(activity,"currentFile")!=null));
            if(ready.get()) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return; }
            Thread.sleep(20);
        } while(System.nanoTime()<deadline);
        fail("Editor did not restore the fixture session");
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8); }
    private static Object get(Object owner,String name) {
        try { Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    private static void set(Object owner,String name,Object value) {
        try { Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    private static Object call(Object owner,String name,Class<?>[] types,Object...arguments) {
        try { Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,arguments); }
        catch(InvocationTargetException error) { throw new AssertionError(error.getCause()); }
        catch(Exception error) { throw new AssertionError(error); }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences preferences=context.getSharedPreferences(AppPreferences.PREFS_NAME,Context.MODE_PRIVATE);
        final Map<String,?> oldPreferences=preferences.getAll();
        final File project,first,second,third;
        final String firstDisk="// 😀 disk\nint disk_first;\n", firstDraft="// 😀 alpha\nint draft_first;\n";
        final String secondDraft="int draft_second;\n", thirdDisk="// 😀 beta\nint disk_third;\n";
        Fixture() throws Exception {
            preferences.edit().putBoolean("enable_lsp",false).putBoolean("auto_save",false).commit();
            project=Files.createTempDirectory(context.getCacheDir().toPath(),"editor-lsp-ui-").toFile();
            first=new File(project,"first+文.cpp");second=new File(project,"second.cpp");third=new File(project,"third+文.h");
            Files.write(first.toPath(),firstDisk.getBytes(StandardCharsets.UTF_8));
            Files.write(second.toPath(),"int disk_second;\n".getBytes(StandardCharsets.UTF_8));
            Files.write(third.toPath(),thirdDisk.getBytes(StandardCharsets.UTF_8));
            EditorDocument one=new EditorDocument(first.getPath());one.content=firstDraft;one.isModified=true;
            EditorDocument two=new EditorDocument(second.getPath());two.content=secondDraft;two.isModified=true;
            new EditorSessionStore(new File(context.getNoBackupFilesDir(),"editor-sessions"))
                    .write("project:"+project.getAbsolutePath(),EditorSessionStore.Snapshot.copyOf(Arrays.asList(one,two),one.id));
        }
        Intent intent() { return new Intent(context,EditorActivity.class).putExtra("project_path",project.getPath()); }
        void enableLsp(boolean enabled) { assertTrue(preferences.edit().putBoolean("enable_lsp",enabled).commit()); }
        @Override public void close() throws Exception {
            SharedPreferences.Editor edit=preferences.edit();
            for(String key:new String[]{"enable_lsp","auto_save"}) {
                if(oldPreferences.containsKey(key)) edit.putBoolean(key,(Boolean)oldPreferences.get(key)); else edit.remove(key);
            }
            assertTrue(edit.commit());
            // Only this test's uniquely allocated project tree is removed; no real projects/toolchains are changed.
            java.nio.file.Files.walkFileTree(project.toPath(),new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    Files.delete(file);return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path directory,java.io.IOException error) throws java.io.IOException {
                    if(error!=null)throw error;Files.delete(directory);return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    /** Only transport construction is reflected; notifications/requests use the shipped protocol code. */
    private static final class FakeSession implements AutoCloseable {
        final FakeServer server=new FakeServer();
        final ExecutorService executor=Executors.newCachedThreadPool();
        final AtomicReference<EditorActivity> activity=new AtomicReference<>();
        final AtomicReference<Runnable> afterDiagnostics=new AtomicReference<>();
        final PipedInputStream clientInput=new PipedInputStream(65536),serverInput=new PipedInputStream(65536);
        final PipedOutputStream serverOutput=new PipedOutputStream(clientInput),clientOutput=new PipedOutputStream(serverInput);
        final Launcher<LanguageClient> launcher;
        final LanguageClient remote;
        final Future<?> listening;
        final ClangdClient client;
        final LspConnection connection;
        @SuppressWarnings("unchecked") // Reflected field is declared CompletableFuture<Void> by ClangdClient.Run.
        FakeSession(Fixture fixture) throws Exception {
            launcher=LSPLauncher.createServerLauncher(server,serverInput,serverOutput,executor,consumer -> consumer);
            remote=launcher.getRemoteProxy();listening=launcher.startListening();
            client=new ClangdClient(fixture.context,fixture.project);
            Class<?> runType=Class.forName(ClangdClient.class.getName()+"$Run");
            Constructor<?> constructor=runType.getDeclaredConstructor();constructor.setAccessible(true);Object run=constructor.newInstance();
            set(client,"current",run);
            LanguageClient callbacks=(LanguageClient)call(client,"languageClient",new Class<?>[]{runType},run);
            connection=new LspConnection(callbacks,clientInput,clientOutput);set(run,"connection",connection);
            connection.initialize(fixture.project,android.os.Process.myPid(),5000).get(6,TimeUnit.SECONDS);
            ((CompletableFuture<Void>)get(run,"ready")).complete(null);
        }
        static FakeSession attach(Fixture fixture,ActivityScenario<EditorActivity> scenario) throws Exception {
            FakeSession fake=new FakeSession(fixture);
            fixture.enableLsp(true);
            scenario.onActivity(current -> {
                fake.activity.set(current);
                ClangdClient previous=(ClangdClient)get(current,"clangdClient");if(previous!=null)previous.close();
                set(current,"clangdClient",fake.client);set(current,"useClangd",true);
                fake.client.setDiagnosticsCallback((uri,diagnostics) -> {
                    assertSame(Looper.getMainLooper(),Looper.myLooper());
                    call(current,"handleClangdDiagnostics",new Class<?>[]{String.class,JSONArray.class},uri,diagnostics);
                    Runnable after=fake.afterDiagnostics.get();if(after!=null)after.run();
                });
                fake.client.setWorkspaceEditCallback((edit,completion) -> current.runOnUiThread(() ->
                        call(current,"applyWorkspaceEdit",new Class<?>[]{JSONObject.class,java.util.function.Consumer.class},edit,completion)));
                call(current,"notifyClangdFileOpened",new Class<?>[0]);
            });
            return fake;
        }
        void publish(File file,String message) {
            Diagnostic diagnostic=new Diagnostic(new Range(new Position(0,0),new Position(0,2)),message);
            diagnostic.setSeverity(DiagnosticSeverity.Error);
            remote.publishDiagnostics(new PublishDiagnosticsParams(LspConnection.fileUri(file),Collections.singletonList(diagnostic)));
        }
        @Override public void close() throws Exception {
            afterDiagnostics.set(null);activity.set(null);client.close();
            try { connection.closeAsync().get(3,TimeUnit.SECONDS); }
            finally { listening.cancel(true);serverInput.close();serverOutput.close();executor.shutdownNow(); }
        }
    }
    private static final class WireEvent {
        final String kind,uri,text;final long sequence;
        WireEvent(String kind,String uri,String text,long sequence) { this.kind=kind;this.uri=uri;this.text=text;this.sequence=sequence; }
    }
    private static final class FakeServer implements LanguageServer,TextDocumentService,WorkspaceService {
        final LinkedBlockingQueue<WireEvent> events=new LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicLong sequence=new java.util.concurrent.atomic.AtomicLong();
        WireEvent await(String kind,File file) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);String uri=LspConnection.fileUri(file);
            while(System.nanoTime()<deadline) {
                WireEvent event=events.poll(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if(event!=null&&kind.equals(event.kind)&&uri.equals(event.uri))return event;
            }
            throw new AssertionError("Missing wire notification "+kind+" for "+file.getName());
        }
        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }
        @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
        @Override public void exit() { }
        @Override public TextDocumentService getTextDocumentService() { return this; }
        @Override public WorkspaceService getWorkspaceService() { return this; }
        @Override public void didOpen(DidOpenTextDocumentParams params) {
            events.add(new WireEvent("open",params.getTextDocument().getUri(),params.getTextDocument().getText(),sequence.incrementAndGet()));
        }
        @Override public void didClose(DidCloseTextDocumentParams params) {
            events.add(new WireEvent("close",params.getTextDocument().getUri(),null,sequence.incrementAndGet()));
        }
        @Override public void didChange(DidChangeTextDocumentParams params) { }
        @Override public void didSave(DidSaveTextDocumentParams params) { }
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) { }
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) { }
    }
}
