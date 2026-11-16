package com.vkyra.code.signature;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;

import java.io.File;

/**
 * APK解析器 - 解析APK文件获取元数据
 */
public class ApkParser {
    private static final String TAG = "ApkParser";
    
    private Context context;
    
    public ApkParser(Context context) {
        this.context = context;
    }
    
    /**
     * 解析APK信息
     */
    public ApkInfo parseApk(File apkFile) {
        ApkInfo info = new ApkInfo();
        
        try (ApkFile apkFileParser = new ApkFile(apkFile)) {
            // 获取APK元数据
            ApkMeta apkMeta = apkFileParser.getApkMeta();
            
            // 包名
            info.packageName = apkMeta.getPackageName();
            Log.d(TAG, "包名: " + info.packageName);
            
            // 版本号
            info.versionCode = Math.toIntExact(apkMeta.getVersionCode());
            Log.d(TAG, "版本号: " + info.versionCode);
            
            // 版本名称
            info.versionName = apkMeta.getVersionName();
            Log.d(TAG, "版本名称: " + info.versionName);
            
            // 应用名称
            info.appName = apkMeta.getLabel();
            if (info.appName == null || info.appName.isEmpty()) {
                info.appName = apkMeta.getName();
            }
            Log.d(TAG, "应用名称: " + info.appName);
            
            // 获取图标
            try {
                byte[] iconData = apkFileParser.getIconFile().getData();
                if (iconData != null && iconData.length > 0) {
                    info.icon = BitmapFactory.decodeByteArray(iconData, 0, iconData.length);
                    Log.d(TAG, "图标解析成功");
                }
            } catch (Exception e) {
                Log.e(TAG, "图标解析失败", e);
            }
            
            // 最小SDK版本
            info.minSdkVersion = String.valueOf(apkMeta.getMinSdkVersion());
            
            // 目标SDK版本
            info.targetSdkVersion = String.valueOf(apkMeta.getTargetSdkVersion());
            
            Log.d(TAG, "APK解析完成");
            
        } catch (Exception e) {
            Log.e(TAG, "APK解析失败", e);
        }
        
        return info;
    }
    
    /**
     * APK信息类
     */
    public static class ApkInfo {
        public String packageName;
        public Integer versionCode;
        public String versionName;
        public String appName;
        public Bitmap icon;
        public String minSdkVersion;
        public String targetSdkVersion;
        
        public ApkInfo() {
            // 设置默认值
            packageName = "";
            versionCode = 1;
            versionName = "1.0";
            appName = "";
            minSdkVersion = "26";
            targetSdkVersion = "36";
        }
        
        /**
         * 转换为AppInfoDialog.AppInfo
         */
        public AppInfoDialog.AppInfo toDialogAppInfo() {
            return new AppInfoDialog.AppInfo(
                icon,
                appName,
                packageName,
                versionCode,
                versionName
            );
        }
    }
}
