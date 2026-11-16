package com.termux.terminal;

import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Map;

/** An isolated native process session with pipes; the leader remains unreaped until waitFor(). */
public final class PipeProcess extends Process {
    private final int pid;
    private final InputStream stdout, stderr;
    private final OutputStream stdin;
    private Integer exitStatus;

    private PipeProcess(int[] process) {
        pid = process[0];
        stdin = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(process[1]));
        stdout = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(process[2]));
        stderr = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(process[3]));
    }

    public static Process start(ProcessBuilder builder) throws IOException {
        if (builder.command().isEmpty()) throw new IOException("Empty command");
        for (String word : builder.command()) if (word == null || word.indexOf('\0') >= 0) throw new IOException("Invalid command argument");
        String[] environment = new String[builder.environment().size()];
        int index = 0;
        for (Map.Entry<String, String> entry : builder.environment().entrySet()) environment[index++] = entry.getKey() + "=" + entry.getValue();
        try {
            return new PipeProcess(JNI.createPipeProcess(builder.command().toArray(new String[0]),
                    builder.directory() == null ? null : builder.directory().getAbsolutePath(), environment, builder.redirectErrorStream()));
        } catch (RuntimeException failure) { throw new IOException("Cannot start owned process", failure); }
    }
    public int getPid() { return pid; }
    @Override public InputStream getInputStream() { return stdout; }
    @Override public InputStream getErrorStream() { return stderr; }
    @Override public OutputStream getOutputStream() { return stdin; }
    @Override public synchronized int exitValue() {
        if (exitStatus != null) return exitStatus;
        int status = JNI.peekPipeProcess(pid);
        if (status == Integer.MIN_VALUE) throw new IllegalThreadStateException("Process is running");
        return status;
    }
    @Override public synchronized int waitFor() {
        if (exitStatus == null) exitStatus = JNI.waitFor(pid);
        return exitStatus;
    }
    /** Only call after stream readers finish or after cancellation has killed the session. */
    @Override public void destroy() { destroyForcibly(); }
    @Override public synchronized Process destroyForcibly() {
        if (exitStatus == null) JNI.terminatePipeProcess(pid);
        return this;
    }
    @Override public boolean isAlive() {
        try { exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }
}
