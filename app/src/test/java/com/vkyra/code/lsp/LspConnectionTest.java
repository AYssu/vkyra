package com.vkyra.code.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.Test;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

/** Real LSP4J launchers on both ends: these are protocol tests, not a replacement parser test. */
public class LspConnectionTest {
    private static final File ROOT = new File("/tmp/Vkyra 测试 project");
    private static final String DOCUMENT = LspConnection.fileUri(new File(ROOT, "中文 + #.cpp"));

    @Test public void initializationUsesProjectRootAndUtf16Columns() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            assertEquals(LspConnection.fileUri(ROOT), f.server.initialization.getRootUri());
            assertEquals(LspConnection.fileUri(ROOT), f.server.initialization.getWorkspaceFolders().get(0).getUri());
            assertEquals(Collections.singletonList("utf-16"),
                    f.server.initialization.getCapabilities().getGeneral().getPositionEncodings());
            assertFalse(f.server.initialization.getCapabilities().getTextDocument()
                    .getCompletion().getCompletionItem().getSnippetSupport());
            assertTrue(f.server.initialized.await(2, TimeUnit.SECONDS));
            assertEquals(DOCUMENT, LspConnection.fileUri(new File(ROOT, "中文 + #.cpp")));
            assertTrue(DOCUMENT.contains("%23"));
            assertTrue(DOCUMENT.contains("%20"));
        }
    }

    @Test public void fragmentedUtf8AndAdjacentMessagesRemainSeparate() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            CompletableFuture<Either<List<CompletionItem>, CompletionList>> one =
                    f.connection.request(server -> server.getTextDocumentService().completion(completion()), 2000);
            CompletableFuture<Either<List<CompletionItem>, CompletionList>> two =
                    f.connection.request(server -> server.getTextDocumentService().completion(completion()), 2000);
            assertEquals("中文😀补全", one.get(2, TimeUnit.SECONDS).getRight().getItems().get(0).getLabel());
            assertEquals("中文😀补全", two.get(2, TimeUnit.SECONDS).getRight().getItems().get(0).getLabel());
            PublishDiagnosticsParams diagnostics = new PublishDiagnosticsParams(DOCUMENT,
                    Collections.singletonList(new Diagnostic(range(), "缺少分号😀")));
            f.remoteClient.publishDiagnostics(diagnostics);
            assertTrue(f.diagnosticsReceived.await(2, TimeUnit.SECONDS));
            assertEquals("缺少分号😀", f.diagnostics.getDiagnostics().get(0).getMessage().getLeft());
        }
    }

    @Test public void documentNotificationsAreOrderedBeforeRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            f.connection.notifyServer(server -> server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(DOCUMENT, "cpp", 1, "int 中文;"))));
            f.connection.notifyServer(server -> server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                    new VersionedTextDocumentIdentifier(DOCUMENT, 2), Collections.singletonList(
                    new TextDocumentContentChangeEvent("int 中文 = 2;")))));
            f.connection.request(server -> server.getTextDocumentService().completion(completion()), 2000)
                    .get(2, TimeUnit.SECONDS);
            f.connection.notifyServer(server -> server.getTextDocumentService().didClose(
                    new DidCloseTextDocumentParams(new TextDocumentIdentifier(DOCUMENT))));
            assertTrue(f.server.documentsClosed.await(2, TimeUnit.SECONDS));
            assertEquals(java.util.Arrays.asList("open:1:int 中文;", "change:2:int 中文 = 2;", "completion", "close"),
                    f.server.events);
        }
    }

    @Test public void typedDefinitionReferencesRenameActionsAndCommandsRoundTrip() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            assertEquals(DOCUMENT, f.connection.request(server -> server.getTextDocumentService().definition(
                    new DefinitionParams(new TextDocumentIdentifier(DOCUMENT), new Position())), 2000)
                    .get(2, TimeUnit.SECONDS).getRight().get(0).getTargetUri());
            assertEquals(2, f.connection.request(server -> server.getTextDocumentService().references(
                    new ReferenceParams(new TextDocumentIdentifier(DOCUMENT), new Position(), new ReferenceContext(false))),
                    2000).get(2, TimeUnit.SECONDS).size());
            assertEquals("中文", f.connection.request(server -> server.getTextDocumentService().prepareRename(
                    new PrepareRenameParams(new TextDocumentIdentifier(DOCUMENT), new Position())), 2000)
                    .get(2, TimeUnit.SECONDS).getSecond().getPlaceholder());
            assertEquals("新名称", f.connection.request(server -> server.getTextDocumentService().rename(
                    new RenameParams(new TextDocumentIdentifier(DOCUMENT), new Position(), "新名称")), 2000)
                    .get(2, TimeUnit.SECONDS).getChanges().get(DOCUMENT).get(0).getNewText());
            List<Either<Command, CodeAction>> actions = f.connection.request(server ->
                    server.getTextDocumentService().codeAction(new CodeActionParams(new TextDocumentIdentifier(DOCUMENT),
                            range(), new CodeActionContext(Collections.emptyList()))), 2000).get(2, TimeUnit.SECONDS);
            assertEquals("修复", actions.get(0).getRight().getTitle());
            assertEquals("命令", actions.get(1).getLeft().getTitle());
            assertNotNull(f.connection.request(server -> server.getWorkspaceService().executeCommand(
                    new ExecuteCommandParams("test.command", Collections.singletonList("参数😀"))), 2000)
                    .get(2, TimeUnit.SECONDS));
        }
    }

    @Test public void timeoutCancelsTheActualServerRequest() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            CompletableFuture<Object> result = f.connection.request(server -> server.getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams("hang", Collections.emptyList())), 60);
            assertTrue(f.server.hangStarted.await(2, TimeUnit.SECONDS));
            try {
                result.get(2, TimeUnit.SECONDS);
                fail("Expected request deadline");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof TimeoutException);
            }
            assertTrue(f.server.hangCancelled.await(2, TimeUnit.SECONDS));
            assertTrue(f.server.hanging.isCancelled());
        }
    }

    @Test public void cancellingCompletionSendsLibraryCancellation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            CompletableFuture<Object> result = f.connection.request(server -> server.getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams("hang", Collections.emptyList())), 2000);
            assertTrue(f.server.hangStarted.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertTrue(f.server.hangCancelled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test public void closeIsImmediateEvenWhenServerIgnoresShutdown() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            f.server.ignoreShutdown = true;
            CompletableFuture<Object> request = f.connection.request(server -> server.getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams("hang", Collections.emptyList())), 5000);
            assertTrue(f.server.hangStarted.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            CompletableFuture<Void> stopped = f.connection.closeAsync();
            assertTrue("close must not block an Android lifecycle callback",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200);
            stopped.get(2, TimeUnit.SECONDS);
            assertFalse(f.connection.isInitialized());
            assertTrue(request.isCancelled());
            assertSame(stopped, f.connection.closeAsync());
        }
    }

    @Test public void disconnectedTransportInvalidatesSessionAndPendingRequests() throws Exception {
        try (Fixture f = new Fixture()) {
            f.initialize();
            CompletableFuture<Object> request = f.connection.request(server -> server.getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams("hang", Collections.emptyList())), 5000);
            assertTrue(f.server.hangStarted.await(2, TimeUnit.SECONDS));
            f.serverOutput.close();
            f.connection.whenClosed().get(2, TimeUnit.SECONDS);
            assertFalse(f.connection.isInitialized());
            assertTrue(request.isDone());
        }
    }

    @Test public void requestsBeforeInitializationAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            try {
                f.connection.request(server -> server.shutdown(), 2000).get();
                fail("Uninitialized session must reject editor requests");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        }
    }

    @Test public void repeatedSessionsReleaseAllOwnedWorkers() throws Exception {
        for (int i = 0; i < 5; i++) {
            try (Fixture f = new Fixture()) { f.initialize(); }
        }
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (hasLspWorkers() && System.nanoTime() < limit) Thread.sleep(10);
        assertFalse("session shutdown must release RPC, notification and deadline executors", hasLspWorkers());
    }

    private static boolean hasLspWorkers() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName().startsWith("vkyra-lsp-")) return true;
        }
        return false;
    }
    private static Range range() { return new Range(new Position(0, 0), new Position(0, 2)); }
    private static CompletionParams completion() { return new CompletionParams(new TextDocumentIdentifier(DOCUMENT), new Position(0, 2)); }

    private static final class Fixture implements AutoCloseable {
        final FakeServer server = new FakeServer();
        final ExecutorService executor = Executors.newCachedThreadPool();
        final PipedInputStream clientInput = new PipedInputStream(65536);
        final PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        final PipedInputStream serverInput = new PipedInputStream(65536);
        final PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        final CountDownLatch diagnosticsReceived = new CountDownLatch(1);
        volatile PublishDiagnosticsParams diagnostics;
        final LspConnection connection;
        final LanguageClient remoteClient;
        final Future<?> listening;
        Fixture() throws Exception {
            Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server,
                    serverInput, serverOutput, executor, consumer -> consumer);
            remoteClient = launcher.getRemoteProxy();
            listening = launcher.startListening();
            InputStream fragmented = new FilterInputStream(clientInput) {
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    return super.read(bytes, offset, Math.min(length, 1));
                }
            };
            connection = new LspConnection(new LanguageClient() {
                @Override public void telemetryEvent(Object value) { }
                @Override public void publishDiagnostics(PublishDiagnosticsParams params) {
                    diagnostics = params;
                    diagnosticsReceived.countDown();
                }
                @Override public void showMessage(MessageParams params) { }
                @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
                    return CompletableFuture.completedFuture(null);
                }
                @Override public void logMessage(MessageParams params) { }
            }, fragmented, clientOutput);
        }
        void initialize() throws Exception { connection.initialize(ROOT, 123, 2000).get(3, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            connection.closeAsync().get(3, TimeUnit.SECONDS);
            listening.cancel(true);
            serverOutput.close();
            serverInput.close();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static final class FakeServer implements LanguageServer, TextDocumentService, WorkspaceService {
        volatile InitializeParams initialization;
        volatile boolean ignoreShutdown;
        final CountDownLatch initialized = new CountDownLatch(1);
        final CountDownLatch documentsClosed = new CountDownLatch(1);
        final CountDownLatch hangStarted = new CountDownLatch(1);
        final CountDownLatch hangCancelled = new CountDownLatch(1);
        final CompletableFuture<Object> hanging = new CompletableFuture<>();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            initialization = params;
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }
        @Override public void initialized(InitializedParams params) { initialized.countDown(); }
        @Override public CompletableFuture<Object> shutdown() {
            return ignoreShutdown ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
        }
        @Override public void exit() { }
        @Override public TextDocumentService getTextDocumentService() { return this; }
        @Override public WorkspaceService getWorkspaceService() { return this; }
        @Override public void didOpen(DidOpenTextDocumentParams params) {
            events.add("open:" + params.getTextDocument().getVersion() + ":" + params.getTextDocument().getText());
        }
        @Override public void didChange(DidChangeTextDocumentParams params) {
            events.add("change:" + params.getTextDocument().getVersion() + ":" + params.getContentChanges().get(0).getText());
        }
        @Override public void didClose(DidCloseTextDocumentParams params) { events.add("close"); documentsClosed.countDown(); }
        @Override public void didSave(DidSaveTextDocumentParams params) { }
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) { }
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) { }
        @Override public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
            events.add("completion");
            return CompletableFuture.completedFuture(Either.forRight(new CompletionList(false,
                    Collections.singletonList(new CompletionItem("中文😀补全")))));
        }
        @Override public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
            return CompletableFuture.completedFuture(Either.forRight(Collections.singletonList(new LocationLink(DOCUMENT, range(), range()))));
        }
        @Override public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
            return CompletableFuture.completedFuture(java.util.Arrays.asList(new Location(DOCUMENT, range()), new Location(DOCUMENT, range())));
        }
        @Override public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> prepareRename(PrepareRenameParams params) {
            return CompletableFuture.completedFuture(org.eclipse.lsp4j.jsonrpc.messages.Either3.forSecond(new PrepareRenameResult(range(), "中文")));
        }
        @Override public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
            return CompletableFuture.completedFuture(new WorkspaceEdit(Collections.singletonMap(DOCUMENT,
                    Collections.singletonList(new TextEdit(range(), params.getNewName())))));
        }
        @Override public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
            return CompletableFuture.completedFuture(java.util.Arrays.asList(Either.forRight(new CodeAction("修复")),
                    Either.forLeft(new Command("命令", "test.command"))));
        }
        @Override public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
            if (!"hang".equals(params.getCommand())) return CompletableFuture.completedFuture(Collections.singletonMap("ok", true));
            hanging.whenComplete((result, error) -> { if (hanging.isCancelled()) hangCancelled.countDown(); });
            hangStarted.countDown();
            return hanging;
        }
    }
}
