package com.vkyra.code.lsp;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.GeneralClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns one LSP4J transport. Android UI and JSON presentation do not belong here.
 * LSP4J owns framing, UTF-8 decoding, response IDs and $/cancelRequest messages.
 */
public final class LspConnection implements AutoCloseable {
    private final InputStream input;
    private final OutputStream output;
    private final ExecutorService rpcExecutor = Executors.newCachedThreadPool(threads("rpc"));
    private final ExecutorService commands = Executors.newSingleThreadExecutor(threads("commands"));
    private final ScheduledThreadPoolExecutor deadlines =
            new ScheduledThreadPoolExecutor(1, threads("deadline"));
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
    private final LanguageServer server;
    private final Future<?> listening;
    private volatile boolean initialized;

    public LspConnection(LanguageClient client, InputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
        deadlines.setRemoveOnCancelPolicy(true);
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                client, input, output, rpcExecutor, consumer -> consumer);
        server = launcher.getRemoteProxy();
        listening = launcher.startListening();
        rpcExecutor.execute(() -> {
            try {
                listening.get();
            } catch (Exception ignored) {
                // An EOF or failed stream invalidates this session and every pending request.
            } finally {
                closeAsync();
            }
        });
    }

    public CompletableFuture<InitializeResult> initialize(File root, int processId, long timeoutMillis) {
        InitializeParams params = new InitializeParams();
        params.setProcessId(processId);
        params.setClientInfo(new ClientInfo("Vkyra", "1"));
        params.setRootUri(fileUri(root));
        params.setWorkspaceFolders(Collections.singletonList(
                new WorkspaceFolder(fileUri(root), root.getName())));
        params.setCapabilities(capabilities());
        CompletableFuture<InitializeResult> result = submit(
                remote -> remote.initialize(params), timeoutMillis, false);
        // The initialized notification must precede any didOpen/completion request.
        return result.thenApply(value -> {
            if (closed.get()) throw new CancellationException("LSP session closed");
            server.initialized(new InitializedParams());
            initialized = true;
            return value;
        });
    }

    public static String fileUri(File file) {
        try {
            return new URI("file", "", file.getAbsolutePath(), null).toASCIIString();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Invalid document path", invalid);
        }
    }

    /** Servers may encode reserved characters or add an empty authority differently. */
    public static String normalizeFileUri(String uri) {
        URI parsed = URI.create(uri);
        if (!"file".equalsIgnoreCase(parsed.getScheme())) return uri;
        return fileUri(new File(parsed));
    }

    public static ClientCapabilities capabilities() {
        TextDocumentClientCapabilities text = new TextDocumentClientCapabilities();
        CompletionCapabilities completion = new CompletionCapabilities();
        completion.setDynamicRegistration(false);
        completion.setCompletionItem(new CompletionItemCapabilities(false));
        completion.setContextSupport(true);
        text.setCompletion(completion);
        text.setPublishDiagnostics(new PublishDiagnosticsCapabilities(true));
        text.setRename(new RenameCapabilities(false, true));
        CodeActionCapabilities actions = new CodeActionCapabilities();
        actions.setDynamicRegistration(false);
        actions.setCodeActionLiteralSupport(new CodeActionLiteralSupportCapabilities(
                new CodeActionKindCapabilities(Arrays.asList(
                        CodeActionKind.QuickFix, CodeActionKind.Refactor, CodeActionKind.Source))));
        text.setCodeAction(actions);
        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setWorkspaceFolders(true);
        // applyEdit can be rejected explicitly by the UI instead of hanging the server request.
        workspace.setApplyEdit(true);
        workspace.setConfiguration(false);
        GeneralClientCapabilities general = new GeneralClientCapabilities();
        // Sora's columns and Java strings are UTF-16, including supplementary characters.
        general.setPositionEncodings(Collections.singletonList("utf-16"));
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setTextDocument(text);
        capabilities.setWorkspace(workspace);
        capabilities.setGeneral(general);
        return capabilities;
    }

    public boolean isInitialized() {
        return initialized && !closed.get();
    }

    public <T> CompletableFuture<T> request(
            Function<LanguageServer, CompletableFuture<T>> request, long timeoutMillis) {
        return submit(request, timeoutMillis, true);
    }

    private <T> CompletableFuture<T> submit(
            Function<LanguageServer, CompletableFuture<T>> request, long timeoutMillis,
            boolean requiresInitialization) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get() || (requiresInitialization && !isInitialized())) {
            result.completeExceptionally(new IllegalStateException("Clangd not initialized"));
            return result;
        }
        AtomicReference<CompletableFuture<T>> upstream = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();
        pending.add(result);
        result.whenComplete((value, error) -> {
            pending.remove(result);
            ScheduledFuture<?> timer = deadline.get();
            if (timer != null) timer.cancel(false);
            CompletableFuture<T> wire = upstream.get();
            if (wire != null && error != null) {
                try {
                    // LSP4J sends $/cancelRequest from cancel(); never write to a process pipe
                    // on the caller's thread (the caller may be an Android lifecycle callback).
                    commands.execute(() -> wire.cancel(true));
                } catch (RejectedExecutionException ignored) { }
            }
        });
        try {
            deadline.set(deadlines.schedule(() -> result.completeExceptionally(
                    new TimeoutException("Clangd request timed out")), timeoutMillis, TimeUnit.MILLISECONDS));
            if (result.isDone()) deadline.get().cancel(false);
            commands.execute(() -> {
                if (result.isDone() || closed.get()) {
                    result.cancel(false);
                    return;
                }
                try {
                    CompletableFuture<T> wire = request.apply(server);
                    upstream.set(wire);
                    if (result.isDone()) wire.cancel(true);
                    wire.whenComplete((value, error) -> {
                        if (error == null) result.complete(value);
                        else result.completeExceptionally(error);
                    });
                } catch (Exception error) {
                    result.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(new CancellationException("LSP session closed"));
        }
        return result;
    }

    /** Queued with requests so didOpen/didChange always precede their completion request. */
    public void notifyServer(Consumer<LanguageServer> notification) {
        if (!isInitialized()) return;
        try {
            commands.execute(() -> {
                if (isInitialized()) notification.accept(server);
            });
        } catch (RejectedExecutionException ignored) {
            // A UI event can race session shutdown.
        }
    }

    /** Returns immediately, including when clangd ignores shutdown. */
    public CompletableFuture<Void> closeAsync() {
        if (!closed.compareAndSet(false, true)) return closedFuture;
        boolean wasInitialized = initialized;
        initialized = false;
        for (CompletableFuture<?> request : pending) request.cancel(true);
        commands.shutdownNow();
        deadlines.shutdownNow();
        Thread closer = threads("close").newThread(() -> {
            try {
                if (wasInitialized) {
                    try {
                        server.shutdown().get(700, TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {
                        // The transport will be closed even for an unresponsive server.
                    }
                    try {
                        server.exit();
                    } catch (Exception ignored) { }
                }
            } finally {
                closeQuietly(output);
                closeQuietly(input);
                listening.cancel(true);
                rpcExecutor.shutdownNow();
                closedFuture.complete(null);
            }
        });
        closer.start();
        return closedFuture;
    }

    /** Observation only: unlike closeAsync(), this does not initiate shutdown. */
    public CompletableFuture<Void> whenClosed() {
        return closedFuture;
    }

    @Override public void close() {
        closeAsync();
    }

    private static void closeQuietly(Closeable resource) {
        try {
            resource.close();
        } catch (Exception ignored) { }
    }

    private static ThreadFactory threads(String purpose) {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "vkyra-lsp-" + purpose + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
