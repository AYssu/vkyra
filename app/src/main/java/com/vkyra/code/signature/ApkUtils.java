package com.vkyra.code.signature;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ApkUtils {
    private static final String TAG = "ApkUtils";

    /**
     * 获取当前应用的APK路径
     */
    public static String getApkPath(Context context) {
        try {
            ApplicationInfo appInfo = context.getPackageManager()
                    .getApplicationInfo(context.getPackageName(), 0);
            return appInfo.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "获取APK路径失败", e);
            return null;
        }
    }

    /**
     * 复制APK到指定目录
     */
    public static boolean copyApkToDirectory(Context context, String destPath) {
        String apkPath = getApkPath(context);
        if (apkPath == null) {
            return false;
        }

        File sourceFile = new File(apkPath);
        File destFile = new File(destPath);

        // 确保目标目录存在
        File destDir = destFile.getParentFile();
        if (destDir != null && !destDir.exists()) {
            destDir.mkdirs();
        }

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = new FileOutputStream(destFile)) {

            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            out.flush();
            Log.d(TAG, "APK复制成功: " + destPath);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "APK复制失败", e);
            return false;
        }
    }

    /**
     * 复制APK到应用内部存储的assets目录
     */
    public static boolean copyApkToInternalStorage(Context context, String fileName) {
        File destDir = new File(context.getFilesDir(), "assets");
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        
        String destPath = new File(destDir, fileName).getAbsolutePath();
        return copyApkToDirectory(context, destPath);
    }

    /**
     * 获取APK文件大小
     */
    public static long getApkSize(Context context) {
        String apkPath = getApkPath(context);
        if (apkPath != null) {
            File apkFile = new File(apkPath);
            return apkFile.length();
        }
        return 0;
    }

    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        }
    }
}
