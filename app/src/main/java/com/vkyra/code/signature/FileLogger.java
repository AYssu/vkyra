package com.vkyra.code.signature;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志工具 - 将日志同时输出到Logcat和文件
 */
public class FileLogger {
    private static File logFile;
    private static boolean initialized = false;
    private static final SimpleDateFormat dateFormat = 
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    
    /**
     * 初始化日志文件
     */
    public static synchronized void init(Context context) {
        if (initialized) return;
        
        File logDir = new File(context.getFilesDir(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(new Date());
        logFile = new File(logDir, "apk_repack_" + timestamp + ".log");
        
        initialized = true;
        
        i("FileLogger", "日志文件: " + logFile.getAbsolutePath());
    }
    
    /**
     * Debug日志
     */
    public static void d(String tag, String message) {
        Log.d(tag, message);
        writeToFile("D", tag, message);
    }
    
    /**
     * Info日志
     */
    public static void i(String tag, String message) {
        Log.i(tag, message);
        writeToFile("I", tag, message);
    }
    
    /**
     * Warning日志
     */
    public static void w(String tag, String message) {
        Log.w(tag, message);
        writeToFile("W", tag, message);
    }
    
    /**
     * Warning日志（带异常）
     */
    public static void w(String tag, String message, Throwable throwable) {
        Log.w(tag, message, throwable);
        writeToFile("W", tag, message + "\n" + getStackTrace(throwable));
    }
    
    /**
     * Error日志
     */
    public static void e(String tag, String message) {
        Log.e(tag, message);
        writeToFile("E", tag, message);
    }
    
    /**
     * Error日志（带异常）
     */
    public static void e(String tag, String message, Throwable throwable) {
        Log.e(tag, message, throwable);
        writeToFile("E", tag, message + "\n" + getStackTrace(throwable));
    }
    
    /**
     * 写入文件
     */
    private static synchronized void writeToFile(String level, String tag, String message) {
        if (!initialized || logFile == null) return;
        
        try {
            FileWriter fw = new FileWriter(logFile, true);
            PrintWriter pw = new PrintWriter(fw);
            
            String timestamp = dateFormat.format(new Date());
            pw.println(timestamp + " " + level + "/" + tag + ": " + message);
            
            pw.close();
            fw.close();
        } catch (IOException e) {
            Log.e("FileLogger", "写入日志文件失败", e);
        }
    }
    
    /**
     * 获取异常堆栈信息
     */
    private static String getStackTrace(Throwable throwable) {
        if (throwable == null) return "";
        
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.toString()).append("\n");
        
        for (StackTraceElement element : throwable.getStackTrace()) {
            sb.append("    at ").append(element.toString()).append("\n");
        }
        
        if (throwable.getCause() != null) {
            sb.append("Caused by: ").append(getStackTrace(throwable.getCause()));
        }
        
        return sb.toString();
    }
    
    /**
     * 获取日志文件
     */
    public static File getLogFile() {
        return logFile;
    }
    
    /**
     * 获取日志文件路径
     */
    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "未初始化";
    }
    
    /**
     * 清理旧日志文件（保留最近5个）
     */
    public static void cleanOldLogs(Context context) {
        File logDir = new File(context.getFilesDir(), "logs");
        if (!logDir.exists()) return;
        
        File[] logFiles = logDir.listFiles((dir, name) -> name.startsWith("apk_repack_") && name.endsWith(".log"));
        if (logFiles == null || logFiles.length <= 5) return;
        
        // 按修改时间排序
        java.util.Arrays.sort(logFiles, (f1, f2) -> 
            Long.compare(f2.lastModified(), f1.lastModified()));
        
        // 删除旧文件
        for (int i = 5; i < logFiles.length; i++) {
            logFiles[i].delete();
        }
    }
}
