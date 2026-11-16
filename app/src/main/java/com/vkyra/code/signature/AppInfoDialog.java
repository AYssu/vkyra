package com.vkyra.code.signature;

import android.graphics.Bitmap;

/**
 * APK信息对话框相关类
 */
public class AppInfoDialog {
    
    /**
     * 应用信息类
     */
    public static class AppInfo {
        public Bitmap icon;
        public String appName;
        public String packageName;
        public int versionCode;
        public String versionInfo;
        
        public AppInfo() {
            this.icon = null;
            this.appName = "";
            this.packageName = "";
            this.versionCode = 1;
            this.versionInfo = "1.0.0";
        }
        
        public AppInfo(Bitmap icon, String appName, String packageName, int versionCode, String versionInfo) {
            this.icon = icon;
            this.appName = appName;
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.versionInfo = versionInfo;
        }
    }
    
    /**
     * 确认监听器接口
     */
    public interface OnConfirmListener {
        void onConfirm(AppInfo appInfo);
    }
}