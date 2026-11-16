package com.vkyra.code.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Optional real-server regression. Set VKYRA_HOST_CLANGD to a host clangd executable. */
public class ClangdIntegrationTest {
    @Test public void actualClangdSupportsProjectDatabaseEditingAndRefactoring() throws Exception {
        String executable = System.getenv("VKYRA_HOST_CLANGD");
        Assume.assumeTrue("Set VKYRA_HOST_CLANGD to run the real-server suite",
                executable != null && new File(executable).canExecute());
        Path root = Files.createTempDirectory("vkyra-lsp-中文 ");
        Path source = root.resolve("测试 + #.cpp");
        String firstLine = "int add(int x, int y) { return x + y; }";
        String secondLine = "int main() { int value = add(1, 2); return value; }";
        String text = firstLine + "\n" + secondLine + "\n";
        Files.write(source, text.getBytes(StandardCharsets.UTF_8));
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.util.Map<String, Object> command = new java.util.HashMap<>();
        command.put("directory", root.toString());
        command.put("file", source.toString());
        command.put("arguments", java.util.Arrays.asList("clang++", "-std=c++17", "-c", source.toString()));
        Files.write(root.resolve("compile_commands.json"),
                gson.toJson(Collections.singletonList(command)).getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder(executable, "--log=error", "--background-index=false")
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        LinkedBlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();
        LspConnection connection = new LspConnection(new LanguageClient() {
            @Override public void telemetryEvent(Object object) { }
            @Override public void showMessage(MessageParams params) { }
            @Override public void logMessage(MessageParams params) { }
            @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public void publishDiagnostics(PublishDiagnosticsParams params) { diagnostics.offer(params); }
        }, process.getInputStream(), process.getOutputStream());
        String uri = LspConnection.fileUri(source.toFile());
        TextDocumentIdentifier document = new TextDocumentIdentifier(uri);
        try {
            InitializeResult initialized = connection.initialize(root.toFile(), 123, 10_000).get(12, TimeUnit.SECONDS);
            assertNotNull(initialized.getCapabilities().getDefinitionProvider());
            connection.notifyServer(server -> server.getTextDocumentService().didOpen(
                    new DidOpenTextDocumentParams(new TextDocumentItem(uri, "cpp", 1, text))));
            Position call = new Position(1, secondLine.indexOf("add") + 1);
            var definition = connection.request(server -> server.getTextDocumentService().definition(
                    new DefinitionParams(document, call)), 10_000).get(12, TimeUnit.SECONDS);
            assertNotNull(definition);
            assertEquals(uri, LspConnection.normalizeFileUri(definition.isLeft() ? definition.getLeft().get(0).getUri()
                    : definition.getRight().get(0).getTargetUri()));
            var references = connection.request(server -> server.getTextDocumentService().references(
                    new ReferenceParams(document, call, new ReferenceContext(true))), 10_000)
                    .get(12, TimeUnit.SECONDS);
            assertEquals(2, references.size());
            var prepared = connection.request(server -> server.getTextDocumentService().prepareRename(
                    new PrepareRenameParams(document, call)), 10_000).get(12, TimeUnit.SECONDS);
            assertNotNull(prepared);
            WorkspaceEdit rename = connection.request(server -> server.getTextDocumentService().rename(
                    new RenameParams(document, call, "sum")), 10_000).get(12, TimeUnit.SECONDS);
            List<TextEdit> edits = rename.getChanges().values().iterator().next();
            assertEquals(2, edits.size());
            assertEquals("sum", edits.get(0).getNewText());
            var completion = connection.request(server -> server.getTextDocumentService().completion(
                    new CompletionParams(document, new Position(1, secondLine.indexOf("add") + 2))), 10_000)
                    .get(12, TimeUnit.SECONDS);
            List<CompletionItem> items = completion.isLeft() ? completion.getLeft() : completion.getRight().getItems();
            assertTrue(items.stream().anyMatch(item -> item.getLabel().contains("add")));

            String invalid = "int main() { return 0 }\n";
            diagnostics.clear();
            connection.notifyServer(server -> server.getTextDocumentService().didChange(
                    new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, 2),
                            Collections.singletonList(new TextDocumentContentChangeEvent(invalid)))));
            PublishDiagnosticsParams published = null;
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < limit) {
                PublishDiagnosticsParams candidate = diagnostics.poll(1, TimeUnit.SECONDS);
                if (candidate != null && !candidate.getDiagnostics().isEmpty()) { published = candidate; break; }
            }
            assertNotNull("clangd must publish diagnostics after didChange", published);
            List<Diagnostic> problems = published.getDiagnostics();
            var actions = connection.request(server -> server.getTextDocumentService().codeAction(
                    new CodeActionParams(document, problems.get(0).getRange(), new CodeActionContext(problems))),
                    10_000).get(12, TimeUnit.SECONDS);
            assertFalse("expected a semicolon quick fix", actions.isEmpty());
            connection.notifyServer(server -> server.getTextDocumentService().didClose(
                    new DidCloseTextDocumentParams(document)));
        } finally {
            connection.closeAsync().get(3, TimeUnit.SECONDS);
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }
}
