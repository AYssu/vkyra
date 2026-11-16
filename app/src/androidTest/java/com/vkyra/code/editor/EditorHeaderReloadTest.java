package com.vkyra.code.editor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.vkyra.code.lsp.ClangdClient;
import com.vkyra.code.lsp.ClangdCompilationDatabase;
import com.vkyra.code.lsp.LspConnection;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.HeaderDirManager;

import io.github.rosemoe.sora.widget.CodeEditor;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Actual Editor toggle -> new ClangdClient owner -> bundled clangd -> diagnostics/completion. */
@RunWith(AndroidJUnit4.class)
public class EditorHeaderReloadTest {
    private static final String HEADER = "vkyra_header_reload_unique.h";
    private static final String MEMBER = "vkyra_visible_member";
    private static final String TEXT = "#include <" + HEADER + ">\n"
            + "int main() { VkyraHeader value; return value." + MEMBER + "; }\n";

    @Test public void togglingHeaderDirectoryReloadsRunningEditorClangdAndChangesRealDiagnosticsAndCompletion() throws Exception {
        try (Fixture fixture = new Fixture(); ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
            ClangdClient initial = awaitClient(scenario, fixture, null);
            assertMissingHeader(awaitDiagnostics(initial, fixture, true));
            assertSemanticMember(initial, fixture, false);
            assertMemberDefinition(initial, fixture, false);
            assertFalse(fallbackArguments(fixture).contains("-I" + fixture.headers.getAbsolutePath()));
            Process initialProcess = process(initial);
            AtomicReference<EditorActivity> page = new AtomicReference<>();
            scenario.onActivity(activity -> {
                page.set(activity);
                toggle(activity, fixture.headers, false);
            });
            ClangdClient withHeader = awaitClient(scenario, fixture, initial);
            awaitStopped(initialProcess);
            assertTrue(HeaderDirManager.isHeaderDir(fixture.context, fixture.headers.getAbsolutePath()));
            assertNoErrors(awaitDiagnostics(withHeader, fixture, false));
            assertMemberDefinition(withHeader, fixture, true);
            assertSemanticMember(withHeader, fixture, true);
            assertTrue(fallbackArguments(fixture).contains("-I" + fixture.headers.getAbsolutePath()));
            Process includedProcess = process(withHeader);
            scenario.onActivity(activity -> {
                assertSame("Header changes must reload the server without replacing the editor", page.get(), activity);
                toggle(activity, fixture.headers, true);
            });
            ClangdClient withoutHeader = awaitClient(scenario, fixture, withHeader);
            awaitStopped(includedProcess);
            assertMissingHeader(awaitDiagnostics(withoutHeader, fixture, true));
            assertSemanticMember(withoutHeader, fixture, false);
            assertMemberDefinition(withoutHeader, fixture, false);
            assertFalse(fallbackArguments(fixture).contains("-I" + fixture.headers.getAbsolutePath()));
            scenario.onActivity(activity -> {
                assertSame(page.get(), activity);
                assertEquals(TEXT, ((CodeEditor) get(activity, "codeEditor")).getText().toString());
            });
            fixture.assertProjectConfigurationUnchanged();
            assertEquals(TEXT, read(fixture.source));
        }
    }

    @Test public void userClangdConfigurationRemainsByteIdenticalAcrossActualHeaderReload() throws Exception {
        verifyUserConfiguration(false);
    }

    @Test public void userCompilationDatabaseRemainsByteIdenticalAcrossActualHeaderReload() throws Exception {
        verifyUserConfiguration(true);
    }

    private static void verifyUserConfiguration(boolean database) throws Exception {
        try (Fixture fixture = new Fixture()) {
            File userFile = new File(fixture.project, database ? "compile_commands.json" : ".clangd");
            String contents;
            if (database) {
                Map<String, Object> command = new LinkedHashMap<>();
                command.put("directory", fixture.project.getAbsolutePath());
                command.put("file", fixture.source.getAbsolutePath());
                command.put("arguments", Arrays.asList(new File(ClangInstaller.getClangDir(fixture.context), "bin/clang++").getPath(),
                        "-std=c++17", "-I" + fixture.headers.getAbsolutePath(), "-c", fixture.source.getAbsolutePath()));
                contents = " \n" + new Gson().toJson(Collections.singletonList(command)) + "\n\n";
            } else {
                contents = "# User-owned formatting and include policy must survive.\nCompileFlags:\n"
                        + "  Add: ['-std=c++17', '-I" + fixture.headers.getAbsolutePath().replace("'", "''") + "']\n";
            }
            Files.write(userFile.toPath(), contents.getBytes(StandardCharsets.UTF_8));
            fixture.captureProjectConfiguration();
            assertTrue(ClangdCompilationDatabase.hasProjectConfiguration(fixture.project));
            // The same real FS policy is used by the client's background launcher below.
            assertNull(ClangdCompilationDatabase.prepare(fixture.context, fixture.project));
            try (ActivityScenario<EditorActivity> scenario = ActivityScenario.launch(fixture.intent())) {
                ClangdClient initial = awaitClient(scenario, fixture, null);
                assertNoErrors(awaitDiagnostics(initial, fixture, false));
                assertMemberDefinition(initial, fixture, true);
                assertSemanticMember(initial, fixture, true);
                Process firstProcess = process(initial);
                scenario.onActivity(activity -> toggle(activity, fixture.unrelatedHeaders, false));
                ClangdClient reloaded = awaitClient(scenario, fixture, initial);
                awaitStopped(firstProcess);
                assertNoErrors(awaitDiagnostics(reloaded, fixture, false));
                assertMemberDefinition(reloaded, fixture, true);
                assertSemanticMember(reloaded, fixture, true);
                Process secondProcess = process(reloaded);
                scenario.onActivity(activity -> toggle(activity, fixture.unrelatedHeaders, true));
                ClangdClient removed = awaitClient(scenario, fixture, reloaded);
                awaitStopped(secondProcess);
                assertNoErrors(awaitDiagnostics(removed, fixture, false));
                assertMemberDefinition(removed, fixture, true);
                assertSemanticMember(removed, fixture, true);
            }
            fixture.assertProjectConfigurationUnchanged();
            assertArrayEquals(contents.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(userFile.toPath()));
            assertFalse("User config must prevent generation of an app fallback DB for this unique project",
                    new File(fallbackDirectory(fixture), "compile_commands.json").exists());
        }
    }

    private static ClangdClient awaitClient(ActivityScenario<EditorActivity> scenario, Fixture fixture,
                                            ClangdClient previous) throws Exception {
        AtomicReference<ClangdClient> ready = new AtomicReference<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            scenario.onActivity(activity -> {
                ClangdClient candidate = (ClangdClient) get(activity, "clangdClient");
                if (candidate != null && candidate != previous && candidate.isInitialized()
                        && Boolean.TRUE.equals(get(activity, "useClangd"))
                        && fixture.source.equals(get(activity, "currentFile"))) ready.set(candidate);
            });
            if (ready.get() != null) return ready.get();
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Editor did not initialize a new real clangd owner after header change");
    }

    @SuppressWarnings("unchecked") // Run declares a ConcurrentMap<String,List<Diagnostic>>.
    private static List<Diagnostic> awaitDiagnostics(ClangdClient client, Fixture fixture, boolean missing) throws Exception {
        Object run = get(client, "current");
        Map<String, List<Diagnostic>> diagnostics = (Map<String, List<Diagnostic>>) get(run, "diagnostics");
        String uri = LspConnection.normalizeFileUri(LspConnection.fileUri(fixture.source));
        List<Diagnostic> last = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            List<Diagnostic> published = diagnostics.get(uri);
            if (published != null) {
                last = new ArrayList<>(published);
                boolean hasHeaderError = last.stream().anyMatch(problem -> message(problem).contains(HEADER)
                        && problem.getSeverity() == DiagnosticSeverity.Error);
                boolean hasAnyError = last.stream().anyMatch(problem -> problem.getSeverity() == DiagnosticSeverity.Error);
                if (missing ? hasHeaderError : !hasAnyError) return last;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected real published " + (missing ? "missing-header error" : "error-free AST") + ": " + last);
    }

    private static void assertSemanticMember(ClangdClient client, Fixture fixture, boolean expected) throws Exception {
        String line = TEXT.split("\n")[1];
        List<CompletionItem> items = client.completionItemsFuture(fixture.source.getAbsolutePath(),
                1, line.indexOf(MEMBER), ".").get(20, TimeUnit.SECONDS);
        // On API26 clangd can lexically recover MEMBER from the source even when its header is
        // missing. That candidate is Text with no type detail, not a resolved record field.
        boolean semanticMember = items.stream().anyMatch(item -> item.getKind() == CompletionItemKind.Field
                && "int".equals(item.getDetail()) && MEMBER.equals(insertion(item)));
        assertEquals("Expected " + (expected ? "resolved int field" : "no resolved field")
                + "; actual completion items: " + new Gson().toJson(items), expected, semanticMember);
    }

    private static String insertion(CompletionItem item) {
        if (item.getTextEdit() != null) return item.getTextEdit().isLeft()
                ? item.getTextEdit().getLeft().getNewText() : item.getTextEdit().getRight().getNewText();
        return item.getInsertText();
    }

    private static void assertMemberDefinition(ClangdClient client, Fixture fixture, boolean expected) throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<JSONObject> definition = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        String line = TEXT.split("\n")[1];
        client.gotoDefinition(fixture.source.getAbsolutePath(), 1, line.indexOf(MEMBER) + 2,
                new ClangdClient.ResponseCallback() {
                    @Override public void onResponse(JSONObject result) { definition.set(result); delivered.countDown(); }
                    @Override public void onError(String message) { error.set(message); delivered.countDown(); }
                });
        assertTrue("Semantic definition request did not finish", delivered.await(20, TimeUnit.SECONDS));
        assertNull(error.get());
        if (expected) {
            assertNotNull("No semantic member definition despite successful include", definition.get());
            File actual = new File(java.net.URI.create(definition.get().getString("uri")));
            assertEquals("Member definition must resolve to the actual external header",
                    new File(fixture.headers, HEADER).getCanonicalFile(), actual.getCanonicalFile());
        } else {
            assertNull("Unresolved include must not supply a semantic member definition", definition.get());
        }
    }

    private static void assertMissingHeader(List<Diagnostic> diagnostics) {
        assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(problem ->
                message(problem).contains(HEADER) && problem.getSeverity() == DiagnosticSeverity.Error));
    }
    private static void assertNoErrors(List<Diagnostic> diagnostics) {
        assertFalse(diagnostics.toString(), diagnostics.stream().anyMatch(problem -> problem.getSeverity() == DiagnosticSeverity.Error));
    }
    private static String message(Diagnostic diagnostic) {
        if (diagnostic.getMessage() == null) return "";
        return diagnostic.getMessage().isLeft() ? diagnostic.getMessage().getLeft()
                : diagnostic.getMessage().getRight().getValue();
    }

    private static Process process(ClangdClient client) {
        Process process = (Process) get(get(client, "current"), "process");
        assertNotNull("A protocol fake cannot establish actual launch/reload", process);
        assertTrue(process.isAlive());
        return process;
    }
    private static void awaitStopped(Process process) throws Exception {
        assertTrue("Prior clangd process survived owner cleanup", process.waitFor(10, TimeUnit.SECONDS));
    }
    private static void toggle(EditorActivity activity, File directory, boolean remove) {
        try {
            Method method = EditorActivity.class.getDeclaredMethod("toggleHeaderDir", File.class, boolean.class);
            method.setAccessible(true);
            method.invoke(activity, directory, remove);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static List<String> fallbackArguments(Fixture fixture) throws Exception {
        JsonArray arguments = JsonParser.parseString(read(new File(fallbackDirectory(fixture), "compile_commands.json")))
                .getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("arguments");
        List<String> result = new ArrayList<>();
        arguments.forEach(argument -> result.add(argument.getAsString()));
        return result;
    }
    private static File fallbackDirectory(Fixture fixture) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(fixture.project.getCanonicalPath().getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new File(fixture.context.getCacheDir(), "clangd-databases/" + hash);
    }

    private static final class Fixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences preferences = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        final Map<String, ?> previous = new HashMap<>(preferences.getAll());
        final File project, source, headers, unrelatedHeaders;
        final Map<File, byte[]> configuration = new LinkedHashMap<>();
        Fixture() throws Exception {
            assertTrue("Coordinator must install the bundled real Clang/clangd toolchain first", ClangInstaller.isInstalled(context));
            project = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "header-reload-project-").toFile();
            headers = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "header-reload-include-").toFile();
            unrelatedHeaders = Files.createTempDirectory(context.getNoBackupFilesDir().toPath(), "header-reload-unrelated-").toFile();
            source = new File(project, "main.cpp");
            Files.write(source.toPath(), TEXT.getBytes(StandardCharsets.UTF_8));
            Files.write(new File(headers, HEADER).toPath(), ("#pragma once\nstruct VkyraHeader { int " + MEMBER + "; };\n")
                    .getBytes(StandardCharsets.UTF_8));
            assertFalse(HeaderDirManager.isHeaderDir(context, headers.getAbsolutePath()));
            assertFalse(HeaderDirManager.isHeaderDir(context, unrelatedHeaders.getAbsolutePath()));
            assertTrue(preferences.edit().putBoolean("enable_lsp", true).putBoolean("auto_save", false).commit());
            captureProjectConfiguration();
        }
        Intent intent() {
            return new Intent(context, EditorActivity.class).putExtra("project_path", project.getAbsolutePath())
                    .putExtra("file_path", source.getAbsolutePath());
        }
        void captureProjectConfiguration() throws Exception {
            configuration.clear();
            for (String name : new String[]{".clangd", "compile_commands.json", "compile_flags.txt",
                    "build/compile_commands.json", ".akode/compile_commands.json"}) {
                File file = new File(project, name);
                configuration.put(file, file.isFile() ? Files.readAllBytes(file.toPath()) : null);
            }
        }
        void assertProjectConfigurationUnchanged() throws Exception {
            for (Map.Entry<File, byte[]> entry : configuration.entrySet()) {
                if (entry.getValue() == null) assertFalse("LSP wrote project configuration: " + entry.getKey(), entry.getKey().exists());
                else assertArrayEquals("LSP overwrote user configuration: " + entry.getKey(),
                        entry.getValue(), Files.readAllBytes(entry.getKey().toPath()));
            }
        }
        @Override public void close() {
            // Remove only the two unique entries this fixture can add; preserve unrelated preferences.
            HeaderDirManager.removeHeaderDir(context, headers.getAbsolutePath());
            HeaderDirManager.removeHeaderDir(context, unrelatedHeaders.getAbsolutePath());
            SharedPreferences.Editor edit = preferences.edit();
            for (String key : new String[]{"enable_lsp", "auto_save"}) {
                if (previous.containsKey(key)) edit.putBoolean(key, (Boolean) previous.get(key));
                else edit.remove(key);
            }
            assertTrue(edit.commit());
        }
    }
    private static String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static Object get(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
