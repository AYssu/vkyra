package com.vkyra.code;

import com.vkyra.code.crash.CrashActivity;
import com.vkyra.code.home.MainActivity;
import com.vkyra.code.plugin.PluginHookManager;
import com.vkyra.code.plugin.PluginManager;
import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.update.VersionChecker;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.topjohnwu.superuser.Shell;
import cat.ereza.customactivityoncrash.config.CaocConfig;

public class App extends Application {
    
    private static App instance;
    
    static {
        // 配置 Shell
        Shell.setDefaultBuilder(Shell.Builder.create()
            .setFlags(Shell.FLAG_REDIRECT_STDERR)
            .setTimeout(10)
        );
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        
        // 初始化崩溃捕获
        initCrashHandler();
        
        // 迁移旧版本的设置数据类型
        migratePreferences();
        
        // 异步初始化 Shell（不阻塞主线程）
        Shell.getShell(shell -> {
            // Shell 初始化完成后执行 app init hook
            new Handler(Looper.getMainLooper()).post(() -> {
                executeAppInitHook();
                // 异步检查版本更新
                checkVersionUpdate();
            });
        });
    }
    
    /**
     * 初始化崩溃捕获
     */
    private void initCrashHandler() {
        CaocConfig.Builder.create()
            .backgroundMode(CaocConfig.BACKGROUND_MODE_SHOW_CUSTOM)  // 后台崩溃也显示
            .enabled(true)  // 启用崩溃捕获
            .showErrorDetails(true)  // 显示错误详情
            .showRestartButton(true)  // 显示重启按钮
            .logErrorOnRestart(true)  // 重启时记录错误
            .trackActivities(true)  // 跟踪 Activity
            .minTimeBetweenCrashesMs(2000)  // 崩溃间隔最小时间
            .restartActivity(MainActivity.class)  // 重启后打开的 Activity
            .errorActivity(CrashActivity.class)  // 崩溃页面 Activity
            .apply();
    }
    
    /**
     * 执行应用初始化 hook
     */
    private void executeAppInitHook() {
        try {
            PluginHookManager hookManager = PluginManager.getHookManager(this);
            // 由于 App 不是 Activity，我们需要特殊处理
            // 这里只执行不需要 UI 的 hook（如 command）
            // 如果需要 UI 交互的 hook，会在第一个 Activity 启动时执行
        } catch (Exception e) {
            // 忽略 hook 执行错误
        }
    }
    
    /**
     * 异步检查版本更新
     */
    private void checkVersionUpdate() {
        SharedPreferences cached = getSharedPreferences("app_state", MODE_PRIVATE);
        if (!VersionChecker.isConfigured() || !BuildConfig.UPDATE_REPOSITORY.equals(cached.getString("update_repository", ""))) {
            cached.edit().clear().apply();
        }
        if (!VersionChecker.isConfigured()) return;
        VersionChecker versionChecker = new VersionChecker(this);
        versionChecker.checkVersion(new VersionChecker.VersionCheckCallback() {
            @Override
            public void onVersionCheckComplete(boolean isNewVersion, VersionChecker.ReleaseInfo releaseInfo, String currentVersion) {
                versionChecker.close();
                if (isNewVersion && releaseInfo != null) {
                    // 有新版本可用，保存信息供MainActivity显示
                    SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
                    prefs.edit()
                            .putString("update_repository", BuildConfig.UPDATE_REPOSITORY)
                            .putBoolean("has_new_version", true)
                            .putString("new_version", releaseInfo.version)
                            .putString("release_body", releaseInfo.body)
                            .putString("download_url", releaseInfo.downloadUrl)
                            .apply();
                } else {
                    cached.edit().clear().putString("update_repository", BuildConfig.UPDATE_REPOSITORY).apply();
                }
            }
            
            @Override
            public void onVersionCheckError(String error) {
                versionChecker.close();
                // 版本检查失败，记录日志但不影响应用运行
                android.util.Log.w("VersionChecker", "Version check error: " + error);
            }
        });
    }
    
    public static App getInstance() {
        return instance;
    }
    
    private void migratePreferences() {
        SharedPreferences prefs = getSharedPreferences(AppPreferences.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        // 迁移 font_size (int -> string)
        try {
            prefs.getString(AppPreferences.KEY_FONT_SIZE, null);
        } catch (ClassCastException e) {
            int oldValue = prefs.getInt(AppPreferences.KEY_FONT_SIZE, 26);
            editor.remove(AppPreferences.KEY_FONT_SIZE);
            editor.putString(AppPreferences.KEY_FONT_SIZE, String.valueOf(oldValue));
        }
        
        // 迁移 editor_font_size (int -> string)
        try {
            prefs.getString(AppPreferences.KEY_EDITOR_FONT_SIZE, null);
        } catch (ClassCastException e) {
            int oldValue = prefs.getInt(AppPreferences.KEY_EDITOR_FONT_SIZE, 14);
            editor.remove(AppPreferences.KEY_EDITOR_FONT_SIZE);
            editor.putString(AppPreferences.KEY_EDITOR_FONT_SIZE, String.valueOf(oldValue));
        }
        
        editor.apply();
    }
}
