package com.vkyra.code.lsp;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Runs the shipped/desugared LSP4J on Android, including the supported API 26 floor. */
@RunWith(AndroidJUnit4.class)
public class LspAndroidCompatibilityTest {
    @Test public void shippedLauncherSupportsUtf8RequestsAndCancellationOnAndroid() throws Exception {
        PipedInputStream clientInput = new PipedInputStream(65536);
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        PipedInputStream serverInput = new PipedInputStream(65536);
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        ExecutorService executor = Executors.newCachedThreadPool();
        FakeServer server = new FakeServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
                server, serverInput, serverOutput, executor, consumer -> consumer);
        Future<?> listening = launcher.startListening();
        LspConnection connection = new LspConnection(new QuietClient(), clientInput, clientOutput);
        try {
            connection.initialize(new File("/tmp/Vkyra 测试"), android.os.Process.myPid(), 5000)
                    .get(6, TimeUnit.SECONDS);
            CompletionList completion = connection.request(remote -> remote.getTextDocumentService()
                    .completion(new CompletionParams(new TextDocumentIdentifier("file:///tmp/test.cpp"),
                            new Position(0, 0))), 3000).get(4, TimeUnit.SECONDS).getRight();
            assertEquals("中文😀", completion.getItems().get(0).getLabel());
            CompletableFuture<Object> pending = connection.request(remote -> remote.getWorkspaceService()
                    .executeCommand(new ExecuteCommandParams("hang", Collections.emptyList())), 5000);
            assertTrue(server.started.await(3, TimeUnit.SECONDS));
            pending.cancel(true);
            assertTrue(server.cancelled.await(3, TimeUnit.SECONDS));
            assertTrue(server.hanging.isCancelled());
        } finally {
            connection.closeAsync().get(3, TimeUnit.SECONDS);
            listening.cancel(true);
            serverInput.close();
            serverOutput.close();
            executor.shutdownNow();
        }
    }

    private static final class QuietClient implements LanguageClient {
        @Override public void telemetryEvent(Object value) { }
        @Override public void publishDiagnostics(PublishDiagnosticsParams params) { }
        @Override public void showMessage(MessageParams params) { }
        @Override public void logMessage(MessageParams params) { }
        @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            return CompletableFuture.completedFuture(null);
        }
    }
    private static final class FakeServer implements LanguageServer, TextDocumentService, WorkspaceService {
        final CompletableFuture<Object> hanging = new CompletableFuture<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }
        @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
        @Override public void exit() { }
        @Override public TextDocumentService getTextDocumentService() { return this; }
        @Override public WorkspaceService getWorkspaceService() { return this; }
        @Override public void didOpen(DidOpenTextDocumentParams params) { }
        @Override public void didChange(DidChangeTextDocumentParams params) { }
        @Override public void didClose(DidCloseTextDocumentParams params) { }
        @Override public void didSave(DidSaveTextDocumentParams params) { }
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) { }
        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) { }
        @Override public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
            return CompletableFuture.completedFuture(Either.forRight(new CompletionList(false,
                    Collections.singletonList(new CompletionItem("中文😀")))));
        }
        @Override public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
            hanging.whenComplete((value, error) -> { if (hanging.isCancelled()) cancelled.countDown(); });
            started.countDown();
            return hanging;
        }
    }
}
