package com.vkyra.code.terminal;

import com.vkyra.code.Log;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.toolchain.CompilerArguments;
import com.vkyra.code.toolchain.ZshInstaller;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.lang.ref.WeakReference;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** The Activity is a replaceable display. Config changes keep this PTY; finishing closes it.
 * Ordinary backgrounding keeps it while the app process lives; no permanent service is started. */
public final class TerminalSessionViewModel extends ViewModel implements TerminalSessionClient {
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> new Thread(r, "Vkyra-terminal-cleanup"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService preparation = Executors.newSingleThreadExecutor(r -> new Thread(r, "Vkyra-terminal-build"));
    private WeakReference<TerminalSessionClient> display = new WeakReference<>(null);
    private TerminalSession session;
    private int sessionId;
    private long startTicks;
    private boolean rootRun;
    private String customSu;
    private boolean launchDispatched;
    private boolean finished;
    private boolean shellPreparationStarted;
    private final MutableLiveData<ShellPreparation> shellPreparation = new MutableLiveData<>();
    public static final class ShellPreparation {
        public final boolean ready;
        public final String detail;
        public final String warning;
        ShellPreparation(boolean ready, String detail, String warning) { this.ready = ready; this.detail = detail; this.warning = warning; }
    }
    public LiveData<ShellPreparation> shellPreparation() { return shellPreparation; }
    public void prepareShell(Context context) {
        if (shellPreparationStarted) return;
        shellPreparationStarted = true;
        Context application = context.getApplicationContext();
        preparation.execute(() -> {
            String warning = null;
            try {
                ShellConfiguration.initialize(application);
                synchronized (ZshInstaller.class) {
                    if (!ZshInstaller.isInstalled(application) && ZshInstaller.hasArchiveInAssets(application)) {
                        shellPreparation.postValue(new ShellPreparation(false, "正在安装 zsh...", null));
                        ZshInstaller.install(application);
                    } else if (ZshInstaller.isInstalled(application)) ZshInstaller.ensureZshrcSymlink(application);
                }
            } catch (Exception error) { warning = "zsh 安装失败，使用系统 shell: " + error.getMessage(); }
            shellPreparation.postValue(new ShellPreparation(true, "shell 已就绪", warning));
        });
    }

    public TerminalSession getSession() { return session; }
    public boolean isSessionFinished() { return finished; }
    public boolean isLaunchDispatched() { return launchDispatched; }
    public void markLaunchDispatched() { launchDispatched = true; }
    public void markLaunchPending() { launchDispatched = false; }
    public void allowRootCleanup(String customSu) { rootRun = true; this.customSu = customSu; }

    public void attach(TerminalSessionClient client) { display = new WeakReference<>(client); }
    public void detach(TerminalSessionClient client) {
        if (display.get() == client) display.clear();
    }

    public void own(TerminalSession session, boolean rootRun, String customSu) {
        this.session = session;
        this.rootRun = rootRun;
        this.customSu = customSu;
        sessionId = session.getPid();
        ProcessOwnership.Identity identity = ProcessOwnership.read(sessionId);
        startTicks = identity == null ? -1 : identity.startTicks;
        finished = false;
    }

    public void compileAndRun(Context context, String file, String runArgs) {
        Context application = context.getApplicationContext();
        TerminalSession target = session;
        if (target == null) return;
        preparation.execute(() -> {
            String command;
            try { command = TerminalCommands.compileAndRun(application, file, target.mHandle, runArgs); }
            catch (Exception error) { command = "printf '%s\n' " + CompilerArguments.shellQuote("编译准备失败: " + error.getMessage()) + "; exit\n"; }
            final String ready = command;
            boolean rootRequested = AppPreferences.getEnableRootRun(application);
            String su = AppPreferences.getCustomSu(application);
            main.post(() -> {
                if (session == target && target.isRunning()) {
                    if (rootRequested) allowRootCleanup(su);
                    target.write(ready);
                }
            });
        });
    }

    public void closeSession() {
        TerminalSession old = session;
        if (old == null) return;
        session = null;
        int ownedSession = sessionId;
        long ownedBirth = startTicks;
        boolean explicitRoot = rootRun;
        String su = customSu;
        // Scan/close children before killing the leader; PTY process groups may differ under job control.
        CLEANUP.execute(() -> {
            AndroidProcessSignals.terminateSession(ownedSession, ownedBirth, explicitRoot, su);
            ProcessOwnership.Identity remaining = ProcessOwnership.read(ownedSession);
            if (remaining != null && remaining.startTicks == ownedBirth) old.finishIfRunning();
        });
        launchDispatched = false;
        finished = false;
    }

    @Override protected void onCleared() {
        display.clear();
        preparation.shutdownNow();
        main.removeCallbacksAndMessages(null);
        closeSession();
    }

    @Override public void onTextChanged(TerminalSession s) { if (s != session) return; TerminalSessionClient c = display.get(); if (c != null) c.onTextChanged(s); }
    @Override public void onTitleChanged(TerminalSession s) { if (s != session) return; TerminalSessionClient c = display.get(); if (c != null) c.onTitleChanged(s); }
    @Override public void onSessionFinished(TerminalSession s) {
        if (s != session) return;
        finished = true;
        TerminalSessionClient c = display.get(); if (c != null) c.onSessionFinished(s);
    }
    @Override public void onCopyTextToClipboard(TerminalSession s, String t) { if (s != session) return; TerminalSessionClient c = display.get(); if (c != null) c.onCopyTextToClipboard(s, t); }
    @Override public void onPasteTextFromClipboard(TerminalSession s) { if (s != session) return; TerminalSessionClient c = display.get(); if (c != null) c.onPasteTextFromClipboard(s); }
    @Override public void onBell(TerminalSession s) { if (s != session) return; TerminalSessionClient c = display.get(); if (c != null) c.onBell(s); }
    @Override public void onColorsChanged(TerminalSession s) { if (s != session) return; TerminalSessionClient c = display.get(); if (c != null) c.onColorsChanged(s); }
    @Override public void onTerminalCursorStateChange(boolean state) { TerminalSessionClient c = display.get(); if (c != null) c.onTerminalCursorStateChange(state); }
    @Override public Integer getTerminalCursorStyle() { TerminalSessionClient c = display.get(); return c == null ? null : c.getTerminalCursorStyle(); }
    @Override public void logError(String tag, String message) { android.util.Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
    @Override public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
    @Override public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { android.util.Log.e(tag, message, e); }
    @Override public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "Terminal exception", e); }
}
