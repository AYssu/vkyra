package com.vkyra.code.lsp;

import com.vkyra.code.toolchain.ClangInstaller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.vkyra.code.lsp.LspConnection;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Android lifecycle and legacy editor callback adapter around the typed LSP4J client. */
public final class ClangdClient implements AutoCloseable {
    private static final String TAG = "ClangdClient";
    private static final long REQUEST_TIMEOUT_MS = 15_000;
    // Use the library's enum/Either adapters here too. JSON exists only at the editor boundary.
    private static final Gson JSON = new MessageJsonHandler(Collections.emptyMap()).getGson();
    private final Context context;
    private final File workspaceRoot;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lifecycleLock = new Object();
    private volatile Run current;
    private volatile boolean disposed;
    private volatile DiagnosticsCallback diagnosticsCallback;
    private volatile WorkspaceEditCallback workspaceEditCallback;

    public interface ResponseCallback {
        void onResponse(JSONObject result);
        void onError(String error);
    }
    public interface ArrayResponseCallback {
        void onResponse(JSONArray results);
        void onError(String error);
    }
    public interface InitCallback {
        void onInitialized();
        void onError(String error);
    }
    public interface DiagnosticsCallback {
        void onDiagnostics(String uri, JSONArray diagnostics);
    }
    /** Must report completion after applying all edits, or false without partial application. */
    public interface WorkspaceEditCallback {
        void onApplyEdit(JSONObject workspaceEdit, java.util.function.Consumer<Boolean> result);
    }

    public ClangdClient(Context context) {
        this(context, context.getFilesDir());
    }

    public ClangdClient(Context context, File workspaceRoot) {
        this.context = context.getApplicationContext();
        this.workspaceRoot = workspaceRoot.getAbsoluteFile();
    }

    /** Idempotent while starting/running; a stopped client can start a fresh session. */
    public void start(InitCallback callback) {
        final Run run;
        synchronized (lifecycleLock) {
            if (disposed) {
                mainHandler.post(() -> callback.onError("Clangd client has been closed"));
                return;
            }
            if (current == null || current.stopped) {
                current = new Run();
                // Capture the run, not current: a subsequent stop/start may already replace it.
                Run starting = current;
                Thread starter = new Thread(() -> launch(starting), "vkyra-clangd-start");
                starter.setDaemon(true);
                starter.start();
            }
            run = current;
        }
        run.ready.whenComplete((unused, error) -> mainHandler.post(() -> {
            if (current != run || disposed) return;
            if (error == null) {
                if (!run.stopped) callback.onInitialized();
            } else if (!(error instanceof CancellationException)) callback.onError(message(error));
        }));
    }

    private void launch(Run run) {
        try {
            String directory = ClangInstaller.getClangDirName(context);
            if (directory == null) throw new IllegalStateException("不支持的设备架构");
            File clang = new File(context.getFilesDir(), directory);
            File executable = new File(clang, "bin/clangd");
            if (!executable.isFile()) throw new IllegalStateException("clangd 未找到，请先安装 Clang 编译器");
            if (!executable.canExecute() && !executable.setExecutable(true)) {
                throw new IllegalStateException("无法执行 clangd");
            }
            if (!workspaceRoot.isDirectory()) throw new IllegalStateException("项目目录不存在");
            ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(),
                    "--background-index", "--log=error");
            try {
                File generated = com.vkyra.code.lsp.ClangdCompilationDatabase.prepare(context, workspaceRoot);
                if (generated != null) builder.command().add("--compile-commands-dir=" + generated.getAbsolutePath());
            } catch (Exception unavailable) {
                // Optional app-owned defaults must not prevent clangd's normal project discovery.
                Log.w(TAG, "Unable to prepare fallback compilation database");
            }
            builder.directory(workspaceRoot);
            Map<String, String> environment = builder.environment();
            String path = environment.get("PATH");
            environment.put("PATH", new File(clang, "bin").getAbsolutePath() + (path == null ? "" : ":" + path));
            environment.put("HOME", context.getFilesDir().getAbsolutePath());
            environment.put("XDG_CACHE_HOME", context.getCacheDir().getAbsolutePath());
            // PCH creation otherwise targets desktop /tmp and semantic completion falls back
            // to plain source words, even while diagnostics/definition requests appear healthy.
            ClangdProcessEnvironment.prepare(builder, context.getCacheDir());
            if (run.stopped) return;
            Process process = builder.start();
            synchronized (run) {
                run.process = process;
                if (run.stopped) {
                    process.destroy();
                    return;
                }
                run.connection = new LspConnection(languageClient(run),
                        process.getInputStream(), process.getOutputStream());
                run.connection.whenClosed().whenComplete((unused, error) -> {
                    if (!run.stopped) {
                        run.ready.completeExceptionally(new IllegalStateException("clangd 已退出"));
                        run.release();
                    }
                });
            }
            drainErrors(run, process);
            run.connection.initialize(workspaceRoot, android.os.Process.myPid(), REQUEST_TIMEOUT_MS)
                    .whenComplete((result, error) -> {
                        if (error == null && !run.stopped) run.ready.complete(null);
                        else fail(run, error == null ? new CancellationException() : error);
                    });
        } catch (Exception error) {
            fail(run, error);
        }
    }

    private void fail(Run run, Throwable error) {
        // Complete before detaching so an initialization error reaches the active editor.
        run.ready.completeExceptionally(error);
        run.release();
    }

    private void drainErrors(Run run, Process process) {
        Thread reader = new Thread(() -> {
            try (BufferedReader errors = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                while (!run.stopped && errors.readLine() != null) {
                    // Drain stderr to prevent process deadlock; never log source text or paths.
                }
            } catch (Exception ignored) { }
        }, "vkyra-clangd-stderr");
        reader.setDaemon(true);
        reader.start();
    }

    private LanguageClient languageClient(Run run) {
        return new LanguageClient() {
            @Override public void telemetryEvent(Object object) { }
            @Override public void showMessage(MessageParams params) { }
            @Override public void logMessage(MessageParams params) { }
            @Override public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
                return CompletableFuture.completedFuture(Collections.singletonList(
                        new WorkspaceFolder(LspConnection.fileUri(workspaceRoot), workspaceRoot.getName())));
            }
            @Override public void publishDiagnostics(PublishDiagnosticsParams params) {
                if (!isCurrent(run)) return;
                run.diagnostics.put(LspConnection.normalizeFileUri(params.getUri()), params.getDiagnostics());
                postCurrent(run, () -> {
                    DiagnosticsCallback callback = diagnosticsCallback;
                    if (callback != null) {
                        try {
                            callback.onDiagnostics(params.getUri(), array(params.getDiagnostics()));
                        } catch (Exception error) {
                            Log.w(TAG, "Invalid diagnostics response", error);
                        }
                    }
                });
            }
            @Override public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
                CompletableFuture<ApplyWorkspaceEditResponse> result = new CompletableFuture<>();
                run.editReplies.add(result);
                try {
                    ScheduledFuture<?> timeout = run.replyDeadlines.schedule(() ->
                                    result.complete(new ApplyWorkspaceEditResponse(false)),
                            REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    result.whenComplete((value, error) -> {
                        timeout.cancel(false);
                        run.editReplies.remove(result);
                    });
                } catch (RejectedExecutionException stopped) {
                    result.complete(new ApplyWorkspaceEditResponse(false));
                    run.editReplies.remove(result);
                }
                postCurrent(run, () -> {
                    WorkspaceEditCallback callback = workspaceEditCallback;
                    if (callback == null) {
                        result.complete(new ApplyWorkspaceEditResponse(false));
                        return;
                    }
                    try {
                        callback.onApplyEdit(object(params.getEdit()), applied ->
                                result.complete(new ApplyWorkspaceEditResponse(Boolean.TRUE.equals(applied))));
                    } catch (Exception error) {
                        result.complete(new ApplyWorkspaceEditResponse(false));
                    }
                });
                return result;
            }
        };
    }

    public void setDiagnosticsCallback(DiagnosticsCallback callback) { diagnosticsCallback = callback; }
    public void setWorkspaceEditCallback(WorkspaceEditCallback callback) { workspaceEditCallback = callback; }

    public void openDocument(String filePath, String content) {
        Run run = active();
        if (run == null) return;
        String uri = uri(filePath);
        run.connection.notifyServer(server -> {
            Integer version = run.versions.get(uri);
            if (version != null) {
                change(server, run, uri, content, version + 1);
                return;
            }
            run.versions.put(uri, 1);
            server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                    new TextDocumentItem(uri, languageId(filePath), 1, content)));
        });
    }

    public void updateDocument(String filePath, String content, int requestedVersion) {
        Run run = active();
        if (run == null) return;
        String uri = uri(filePath);
        run.connection.notifyServer(server -> {
            Integer previous = run.versions.get(uri);
            if (previous == null) {
                run.versions.put(uri, 1);
                server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
                        new TextDocumentItem(uri, languageId(filePath), 1, content)));
            } else {
                // Callers previously used wrapped wall-clock timestamps; LSP versions must increase.
                change(server, run, uri, content, Math.max(previous + 1, requestedVersion));
            }
        });
    }

    private static void change(LanguageServer server, Run run, String uri, String content, int version) {
        run.versions.put(uri, version);
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
                new VersionedTextDocumentIdentifier(uri, version), Collections.singletonList(
                        new TextDocumentContentChangeEvent(content))));
    }

    public void closeDocument(String filePath) {
        Run run = active();
        if (run == null) return;
        String uri = uri(filePath);
        run.connection.notifyServer(server -> {
            if (run.versions.remove(uri) != null) {
                run.diagnostics.remove(uri);
                server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
            }
        });
    }

    /** Last version actually queued to this server session; null means the document is not open. */
    public Integer getDocumentVersion(String filePath) {
        Run run = active();
        return run == null ? null : run.versions.get(uri(filePath));
    }

    /** Sora calls language completion on a cancellable worker; its lifetime owns this future. */
    public CompletableFuture<JSONObject> completionFuture(String path, int line, int character, String trigger) {
        var items = completionItemsFuture(path, line, character, trigger);
        CompletableFuture<JSONObject> result = items.thenApply(value -> {
            try { return object(new CompletionList(false, value)); }
            catch (Exception invalid) { throw new java.util.concurrent.CompletionException(invalid); }
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) items.cancel(true); });
        return result;
    }

    public CompletableFuture<List<org.eclipse.lsp4j.CompletionItem>> completionItemsFuture(
            String path, int line, int character, String trigger) {
        Run run = active();
        CompletableFuture<List<org.eclipse.lsp4j.CompletionItem>> result = new CompletableFuture<>();
        if (run == null) {
            result.completeExceptionally(new IllegalStateException("Clangd not initialized"));
            return result;
        }
        CompletionContext completionContext = trigger == null || trigger.isEmpty()
                ? new CompletionContext(CompletionTriggerKind.Invoked)
                : new CompletionContext(CompletionTriggerKind.TriggerCharacter,
                        trigger.substring(trigger.length() - 1));
        var wire = run.connection.request(server -> server.getTextDocumentService().completion(
                new CompletionParams(document(path), position(line, character), completionContext)), REQUEST_TIMEOUT_MS);
        wire.whenComplete((value, error) -> {
            if (error != null) result.completeExceptionally(error);
            else {
                try {
                    CompletionList items = value == null ? new CompletionList(false, Collections.emptyList())
                            : value.isRight() ? value.getRight() : new CompletionList(false, value.getLeft());
                    result.complete(items == null || items.getItems() == null
                            ? Collections.emptyList() : items.getItems());
                } catch (Exception invalid) {
                    result.completeExceptionally(invalid);
                }
            }
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) wire.cancel(true); });
        return result;
    }

    public void gotoDefinition(String path, int line, int character, ResponseCallback callback) {
        request(server -> server.getTextDocumentService().definition(
                new DefinitionParams(document(path), position(line, character))), value -> {
            if (value == null) return null;
            if (value.isLeft()) return first(value.getLeft());
            if (value.getRight() == null || value.getRight().isEmpty()) return null;
            LocationLink link = value.getRight().get(0);
            return object(new Location(link.getTargetUri(), link.getTargetSelectionRange()));
        }, callback, false, false);
    }

    public void findReferences(String path, int line, int character, ResponseCallback callback) {
        request(server -> server.getTextDocumentService().references(
                new ReferenceParams(document(path), position(line, character), new ReferenceContext(true))),
                ClangdClient::first, callback, false, false);
    }

    public void findAllReferences(String path, int line, int character, ArrayResponseCallback callback) {
        requestArray(server -> server.getTextDocumentService().references(
                new ReferenceParams(document(path), position(line, character), new ReferenceContext(false))),
                ClangdClient::array, callback);
    }

    public void completion(String path, int line, int character, ResponseCallback callback) {
        completion(path, line, character, null, callback, false);
    }

    public void completionSync(String path, int line, int character, ResponseCallback callback) {
        completionSync(path, line, character, null, callback);
    }

    /** Callback runs on the session's response worker, never on its JSON-RPC reader or Android UI. */
    public void completionSync(String path, int line, int character, String trigger, ResponseCallback callback) {
        completion(path, line, character, trigger, callback, true);
    }

    private void completion(String path, int line, int character, String trigger,
                            ResponseCallback callback, boolean workerCallback) {
        CompletionContext context = trigger == null || trigger.isEmpty()
                ? new CompletionContext(CompletionTriggerKind.Invoked)
                : new CompletionContext(CompletionTriggerKind.TriggerCharacter,
                        trigger.substring(trigger.length() - 1));
        request(server -> server.getTextDocumentService().completion(new CompletionParams(
                document(path), position(line, character), context)), value -> {
            CompletionList list = value == null ? new CompletionList(false, Collections.emptyList())
                    : value.isRight() ? value.getRight() : new CompletionList(false, value.getLeft());
            return object(list);
        }, callback, workerCallback, true);
    }

    public void getCodeActions(String path, int startLine, int startColumn, int endLine, int endColumn,
                               ArrayResponseCallback callback) {
        Run run = active();
        List<Diagnostic> diagnostics = run == null ? Collections.emptyList()
                : run.diagnostics.getOrDefault(uri(path), Collections.emptyList());
        CodeActionParams params = new CodeActionParams(document(path),
                new Range(position(startLine, startColumn), position(endLine, endColumn)),
                new CodeActionContext(diagnostics));
        requestArray(server -> server.getTextDocumentService().codeAction(params), value -> {
            List<Object> actions = new ArrayList<>();
            if (value != null) for (Either<Command, CodeAction> action : value) actions.add(action.get());
            return array(actions);
        }, callback);
    }

    public void prepareRename(String path, int line, int character, ResponseCallback callback) {
        request(server -> server.getTextDocumentService().prepareRename(
                new PrepareRenameParams(document(path), position(line, character))),
                value -> {
                    if (value == null || (value.isThird() && !value.getThird().isDefaultBehavior())) {
                        throw new IllegalStateException("当前位置不能重命名");
                    }
                    return object(value.get());
                }, callback, false, false);
    }

    public void rename(String path, int line, int character, String name, ResponseCallback callback) {
        request(server -> server.getTextDocumentService().rename(
                new RenameParams(document(path), position(line, character), name)),
                ClangdClient::object, callback, false, false);
    }

    public void executeCommand(String command, JSONArray arguments, ResponseCallback callback) {
        List<Object> values = arguments == null ? Collections.emptyList()
                : JSON.fromJson(arguments.toString(), new com.google.gson.reflect.TypeToken<List<Object>>() { }.getType());
        request(server -> server.getWorkspaceService().executeCommand(
                new ExecuteCommandParams(command, values)), ClangdClient::object, callback, false, false);
    }

    private interface JsonObjectMapping<T> { JSONObject map(T value) throws Exception; }
    private interface JsonArrayMapping<T> { JSONArray map(T value) throws Exception; }

    private <T> void request(Function<LanguageServer, CompletableFuture<T>> call,
                             JsonObjectMapping<T> mapping, ResponseCallback callback,
                             boolean worker, boolean replaceCompletion) {
        Run run = active();
        if (run == null) {
            mainHandler.post(() -> { if (!disposed) callback.onError("Clangd not initialized"); });
            return;
        }
        CompletableFuture<T> future = run.connection.request(call, REQUEST_TIMEOUT_MS);
        if (replaceCompletion) {
            CompletableFuture<?> previous = run.completion;
            run.completion = future;
            if (previous != null && !previous.isDone()) previous.cancel(true);
        }
        future.whenComplete((value, error) -> dispatch(run, worker, () -> {
            if (replaceCompletion && run.completion != future) return;
            if (error instanceof CancellationException) return;
            if (error != null) callback.onError(message(error));
            else {
                try {
                    callback.onResponse(mapping.map(value));
                } catch (Exception invalid) {
                    callback.onError(message(invalid));
                }
            }
        }));
    }

    private <T> void requestArray(Function<LanguageServer, CompletableFuture<T>> call,
                                  JsonArrayMapping<T> mapping, ArrayResponseCallback callback) {
        Run run = active();
        if (run == null) {
            mainHandler.post(() -> { if (!disposed) callback.onError("Clangd not initialized"); });
            return;
        }
        run.connection.request(call, REQUEST_TIMEOUT_MS).whenComplete((value, error) ->
                postCurrent(run, () -> {
                    if (error instanceof CancellationException) return;
                    if (error != null) callback.onError(message(error));
                    else {
                        try { callback.onResponse(mapping.map(value)); }
                        catch (Exception invalid) { callback.onError(message(invalid)); }
                    }
                }));
    }

    private void dispatch(Run run, boolean worker, Runnable action) {
        if (!worker) { postCurrent(run, action); return; }
        try {
            run.callbacks.execute(() -> { if (isCurrent(run)) action.run(); });
        } catch (RejectedExecutionException ignored) { }
    }

    private void postCurrent(Run run, Runnable action) {
        mainHandler.post(() -> { if (isCurrent(run)) action.run(); });
    }
    private boolean isCurrent(Run run) { return current == run && !run.stopped; }
    private Run active() {
        Run run = current;
        return run != null && !run.stopped && run.connection != null
                && run.connection.isInitialized() ? run : null;
    }
    public boolean isInitialized() {
        Run run = current;
        return run != null && !run.stopped && run.connection != null && run.connection.isInitialized();
    }

    /** Nonblocking and restartable; all per-session threads, streams and requests are released. */
    public void stop() {
        Run run;
        synchronized (lifecycleLock) { run = current; current = null; }
        if (run != null) run.release();
    }

    /** Permanent disposal also drops callbacks that may otherwise retain an Activity. */
    public void cleanup() {
        synchronized (lifecycleLock) { disposed = true; }
        diagnosticsCallback = null;
        workspaceEditCallback = null;
        stop();
    }
    @Override public void close() { cleanup(); }

    private static TextDocumentIdentifier document(String path) { return new TextDocumentIdentifier(uri(path)); }
    private static Position position(int line, int character) { return new Position(Math.max(0, line), Math.max(0, character)); }
    private static String uri(String path) { return LspConnection.fileUri(new File(path)); }
    private static String languageId(String path) { return path.endsWith(".c") ? "c" : "cpp"; }
    private static JSONObject object(Object value) throws Exception {
        if (value == null) return null;
        // executeCommand may legally return a scalar; the legacy callback accepts objects only.
        com.google.gson.JsonElement json = JSON.toJsonTree(value);
        return json.isJsonObject() ? new JSONObject(json.toString()) : new JSONObject().put("value", new org.json.JSONTokener(json.toString()).nextValue());
    }
    private static JSONArray array(Object value) throws Exception { return new JSONArray(JSON.toJson(value == null ? Collections.emptyList() : value)); }
    private static JSONObject first(List<?> values) throws Exception { return values == null || values.isEmpty() ? null : object(values.get(0)); }
    private static String message(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class Run {
        volatile boolean stopped;
        volatile Process process;
        volatile LspConnection connection;
        volatile CompletableFuture<?> completion;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final Map<String, Integer> versions = new ConcurrentHashMap<>();
        final Map<String, List<Diagnostic>> diagnostics = new ConcurrentHashMap<>();
        final java.util.Set<CompletableFuture<ApplyWorkspaceEditResponse>> editReplies = ConcurrentHashMap.newKeySet();
        final ScheduledThreadPoolExecutor replyDeadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "vkyra-lsp-edit-deadline");
            thread.setDaemon(true);
            return thread;
        });
        final ExecutorService callbacks = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "vkyra-lsp-callbacks");
            thread.setDaemon(true);
            return thread;
        });

        Run() {
            replyDeadlines.setRemoveOnCancelPolicy(true);
        }

        synchronized void release() {
            if (stopped) return;
            stopped = true;
            ready.cancel(true);
            if (completion != null) completion.cancel(true);
            callbacks.shutdownNow();
            replyDeadlines.shutdownNow();
            // The server request is abandoned along with its transport; completing its future
            // here could synchronously write an applyEdit response on Android's main thread.
            editReplies.clear();
            versions.clear();
            diagnostics.clear();
            if (connection != null) connection.closeAsync();
            Process owned = process;
            if (owned != null) {
                // A second bound also covers a blocked server stdin, independently of RPC shutdown.
                Thread closer = new Thread(() -> {
                    try {
                        if (!owned.waitFor(900, TimeUnit.MILLISECONDS)) {
                            owned.destroy();
                            if (!owned.waitFor(300, TimeUnit.MILLISECONDS)) owned.destroyForcibly();
                        }
                    } catch (InterruptedException interrupted) {
                        owned.destroy();
                        Thread.currentThread().interrupt();
                    }
                }, "vkyra-clangd-process-close");
                closer.setDaemon(true);
                closer.start();
            }
        }
    }
}
