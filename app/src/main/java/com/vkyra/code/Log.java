package com.vkyra.code;

import android.content.Context;
import android.os.Build;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight logging wrapper that mirrors android.util.Log API and also writes
 * logs to an app-private rolling file for persistence.
 */
public final class Log {

    private static final String LOG_DIR_NAME = "logs";
    private static final String LOG_FILE_NAME = "akode.log";
    private static final long MAX_BYTES_BEFORE_ROTATE = 1_500_000; // ~1.5MB
    private static final int MAX_ROTATED_FILES = 3;

    private static volatile Context appContext;
    private static final Object fileLock = new Object();
    private static volatile File logFile;
    private static final SimpleDateFormat TS_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat ROTATE_FORMAT = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private Log() {}

    public static void init(Context context) {
        if (context == null) return;
        if (appContext == null) appContext = context.getApplicationContext();
        ensureLogFile();
        writeDeviceHeaderOnce();
    }

    public static int d(String tag, String msg) {
        writeToFile("D", tag, msg, null);
        return android.util.Log.d(tag, msg);
    }

    public static int d(String tag, String msg, Throwable tr) {
        writeToFile("D", tag, msg, tr);
        return android.util.Log.d(tag, msg, tr);
    }

    public static int i(String tag, String msg) {
        writeToFile("I", tag, msg, null);
        return android.util.Log.i(tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        writeToFile("I", tag, msg, tr);
        return android.util.Log.i(tag, msg, tr);
    }

    public static int w(String tag, String msg) {
        writeToFile("W", tag, msg, null);
        return android.util.Log.w(tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        writeToFile("W", tag, msg, tr);
        return android.util.Log.w(tag, msg, tr);
    }

    public static int e(String tag, String msg) {
        writeToFile("E", tag, msg, null);
        return android.util.Log.e(tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        writeToFile("E", tag, msg, tr);
        return android.util.Log.e(tag, msg, tr);
    }

    public static int v(String tag, String msg) {
        writeToFile("V", tag, msg, null);
        return android.util.Log.v(tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        writeToFile("V", tag, msg, tr);
        return android.util.Log.v(tag, msg, tr);
    }

    private static void writeToFile(String level, String tag, String message, Throwable tr) {
        if (appContext == null) return; // not initialized yet
        ensureLogFile();

        StringBuilder sb = new StringBuilder();
        sb.append(TS_FORMAT.format(new Date()))
          .append(' ')
          .append(level)
          .append('/')
          .append(tag == null ? "-" : tag)
          .append(':')
          .append(' ')
          .append(message == null ? "" : message);
        if (tr != null) {
            sb.append("\n").append(android.util.Log.getStackTraceString(tr));
        }
        String line = sb.append('\n').toString();

        synchronized (fileLock) {
            try {
                rotateIfNeeded();
                try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                    bw.write(line);
                }
            } catch (IOException ignored) {
                // Swallow file errors to never break normal logging
            }
        }
    }

    private static void ensureLogFile() {
        if (logFile != null && logFile.exists()) return;
        if (appContext == null) return;
        File dir = new File(appContext.getFilesDir(), LOG_DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        logFile = new File(dir, LOG_FILE_NAME);
        if (!logFile.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                logFile.createNewFile();
            } catch (IOException ignored) {}
        }
    }

    private static void rotateIfNeeded() {
        if (logFile == null) return;
        if (logFile.length() < MAX_BYTES_BEFORE_ROTATE) return;
        File rotated = new File(logFile.getParentFile(),
                LOG_FILE_NAME + "." + ROTATE_FORMAT.format(new Date()));
        //noinspection ResultOfMethodCallIgnored
        logFile.renameTo(rotated);
        // Recreate new active file
        //noinspection ResultOfMethodCallIgnored
        try { logFile.createNewFile(); } catch (IOException ignored) {}
        cleanupOldRotations();
    }

    private static void cleanupOldRotations() {
        File dir = logFile == null ? null : logFile.getParentFile();
        if (dir == null) return;
        File[] arr = dir.listFiles((d, name) -> name.startsWith(LOG_FILE_NAME + "."));
        if (arr == null || arr.length <= MAX_ROTATED_FILES) return;
        // Sort by lastModified ascending and delete oldest extras
        java.util.Arrays.sort(arr, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < arr.length - MAX_ROTATED_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            arr[i].delete();
        }
    }

    private static void writeDeviceHeaderOnce() {
        synchronized (fileLock) {
            if (logFile == null || !logFile.exists()) return;
            if (logFile.length() > 0) return; // header already exists in active file
            String header = "==== FastScan Log Started " + TS_FORMAT.format(new Date()) + " ====\n" +
                    "Device=" + Build.MANUFACTURER + " " + Build.MODEL + ", SDK=" + Build.VERSION.SDK_INT + "\n" +
                    "AppFilesDir=" + appContext.getFilesDir().getAbsolutePath() + "\n" +
                    "============================================\n";
            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
                bw.write(header);
            } catch (IOException ignored) {}
        }
    }
}


