package com.vkyra.code.lsp;

import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real clangd proves that writable process-local temp storage is required for typed completion. */
public class ClangdProcessEnvironmentTest {
    private static final String MEMBER = "vkyra_semantic_only_member";
    private static final String TEXT = "#include \"record.h\"\n"
            + "int main() { VkyraRecord value; return value." + MEMBER + "; }\n";

    @Test public void invalidDesktopTempStillAllowsDefinitionButLauncherEnvironmentRestoresSemanticCompletion() throws Exception {
        Path directory = Files.createTempDirectory("vkyra-clangd-temp-");
        Path cache = Files.createDirectory(directory.resolve("cache"));
        Path source = directory.resolve("main.cpp");
        Files.write(source, TEXT.getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("record.h"), ("#pragma once\nstruct VkyraRecord { int " + MEMBER + "; };\n")
                .getBytes(StandardCharsets.UTF_8));
        String executable = System.getenv("VKYRA_HOST_CLANGD");
        if (executable == null) executable = "/usr/bin/clangd";
        assertTrue("Install host clangd or set VKYRA_HOST_CLANGD for this real-process regression",
                new File(executable).canExecute());
        try {
            List<CompletionItem> broken = complete(executable, directory, source, cache, false);
            assertFalse("Negative control must reproduce missing semantic PCH completion: " + broken,
                    hasSemanticMember(broken));
            List<CompletionItem> repaired = complete(executable, directory, source, cache, true);
            assertTrue("App-owned process temporary storage must restore the actual typed record field: " + repaired,
                    hasSemanticMember(repaired));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test public void invalidAppCacheFailsBeforeLaunchingAndNeverFallsBackToDesktopTemp() throws Exception {
        Path parent = Files.createTempDirectory("vkyra-clangd-invalid-cache-");
        Path file = Files.write(parent.resolve("not-a-directory"), new byte[]{1});
        try {
            try {
                ClangdProcessEnvironment.prepare(new ProcessBuilder("unused"), file.toFile());
                fail("An unusable app temporary directory must fail explicitly");
            } catch (IOException expected) {
                assertArrayEquals(new byte[]{1}, Files.readAllBytes(file));
            }
        } finally { Files.delete(file); Files.delete(parent); }
    }

    @Test public void repeatedLaunchersReuseAppDirectoryAndDoNotChangeAnotherProcessEnvironment() throws Exception {
        Path cache = Files.createTempDirectory("vkyra-clangd-shared-temp-");
        try {
            ProcessBuilder other = new ProcessBuilder("unused");
            other.environment().put("TMPDIR", "other-session-temp");
            ProcessBuilder first = new ProcessBuilder("unused"), second = new ProcessBuilder("unused");
            File temporary = ClangdProcessEnvironment.prepare(first, cache.toFile());
            assertEquals(temporary, ClangdProcessEnvironment.prepare(second, cache.toFile()));
            assertEquals(temporary.getAbsolutePath(), first.environment().get("TMPDIR"));
            assertEquals(temporary.getAbsolutePath(), second.environment().get("TEMP"));
            assertEquals(temporary.getAbsolutePath(), second.environment().get("TMP"));
            assertEquals("other-session-temp", other.environment().get("TMPDIR"));
            try (java.util.stream.Stream<Path> entries = Files.list(cache)) { assertEquals(1, entries.count()); }
            Files.write(temporary.toPath().resolve("writable-proof"), new byte[]{1});
            Files.delete(temporary.toPath().resolve("writable-proof"));
            Files.delete(temporary.toPath());
        } finally { Files.delete(cache); }
    }

    private static List<CompletionItem> complete(String executable, Path directory, Path source, Path cache,
                                                  boolean repaired) throws Exception {
        Path stderr = directory.resolve(repaired ? "repaired.log" : "negative-control.log");
        ProcessBuilder builder = new ProcessBuilder(executable, "--log=error", "--background-index=false")
                .directory(directory.toFile()).redirectError(stderr.toFile());
        String absent = directory.resolve("desktop-tmp-does-not-exist").toString();
        for (String variable : new String[]{"TMPDIR", "TMP", "TEMP"}) builder.environment().put(variable, absent);
        if (repaired) ClangdProcessEnvironment.prepare(builder, cache.toFile());
        Process process = builder.start();
        LinkedBlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();
        LspConnection connection = new LspConnection(new LanguageClient() {
            @Override public void telemetryEvent(Object value) { }
            @Override public void showMessage(MessageParams value) { }
            @Override public void logMessage(MessageParams value) { }
            @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams value) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public void publishDiagnostics(PublishDiagnosticsParams value) { diagnostics.offer(value); }
        }, process.getInputStream(), process.getOutputStream());
        String uri = LspConnection.fileUri(source.toFile());
        TextDocumentIdentifier document = new TextDocumentIdentifier(uri);
        int column = TEXT.split("\n")[1].indexOf(MEMBER);
        try {
            connection.initialize(directory.toFile(), 123, 10000).get(12, TimeUnit.SECONDS);
            connection.notifyServer(server -> server.getTextDocumentService().didOpen(
                    new DidOpenTextDocumentParams(new TextDocumentItem(uri, "cpp", 1, TEXT))));
            PublishDiagnosticsParams published = diagnostics.poll(15, TimeUnit.SECONDS);
            assertNotNull("Real clangd did not publish its initial AST diagnostics", published);
            assertTrue(published.getDiagnostics().toString(), published.getDiagnostics().isEmpty());
            // Both configurations resolve the field definition; that alone misses the PCH defect.
            var definition = connection.request(server -> server.getTextDocumentService().definition(
                    new DefinitionParams(document, new Position(1, column + 2))), 10000).get(12, TimeUnit.SECONDS);
            assertNotNull(definition);
            String target = definition.isLeft() ? definition.getLeft().get(0).getUri()
                    : definition.getRight().get(0).getTargetUri();
            assertEquals(directory.resolve("record.h").toFile().getCanonicalFile(),
                    new File(java.net.URI.create(target)).getCanonicalFile());
            var completion = connection.request(server -> server.getTextDocumentService().completion(
                    new CompletionParams(document, new Position(1, column),
                            new CompletionContext(CompletionTriggerKind.TriggerCharacter, "."))), 10000)
                    .get(12, TimeUnit.SECONDS);
            List<CompletionItem> items = completion.isLeft() ? completion.getLeft() : completion.getRight().getItems();
            if (!repaired) assertTrue("Negative control must actually hit the missing-PCH-temp mechanism",
                    new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8).contains("Could not create temporary file for PCH"));
            return items;
        } finally {
            try { connection.closeAsync().get(3, TimeUnit.SECONDS); }
            finally {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue("Owned host clangd did not stop", process.waitFor(2, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static boolean hasSemanticMember(List<CompletionItem> items) {
        return items.stream().anyMatch(item -> item.getKind() == CompletionItemKind.Field && "int".equals(item.getDetail())
                && item.getTextEdit() != null && MEMBER.equals(item.getTextEdit().isLeft()
                ? item.getTextEdit().getLeft().getNewText() : item.getTextEdit().getRight().getNewText()));
    }
}
