package com.vkyra.code.toolchain;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 头文件目录管理器
 * 管理用户自定义的头文件目录，用于 clangd 的 compile_commands.json
 */
public class HeaderDirManager {
    
    private static final String PREFS_NAME = "header_dirs";
    private static final String KEY_DIRS = "include_dirs";
    
    private static Set<String> cachedDirs = null;
    
    /**
     * 获取所有头文件目录
     */
    public static Set<String> getHeaderDirs(Context context) {
        if (cachedDirs == null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            cachedDirs = new HashSet<>(prefs.getStringSet(KEY_DIRS, new HashSet<>()));
        }
        return new HashSet<>(cachedDirs);
    }
    
    /**
     * 添加头文件目录
     */
    public static void addHeaderDir(Context context, String path) {
        Set<String> dirs = getHeaderDirs(context);
        dirs.add(path);
        saveDirs(context, dirs);
    }
    
    /**
     * 移除头文件目录
     */
    public static void removeHeaderDir(Context context, String path) {
        Set<String> dirs = getHeaderDirs(context);
        dirs.remove(path);
        saveDirs(context, dirs);
    }
    
    /**
     * 检查是否是头文件目录
     */
    public static boolean isHeaderDir(Context context, String path) {
        return getHeaderDirs(context).contains(path);
    }
    
    /**
     * 保存目录配置
     */
    private static void saveDirs(Context context, Set<String> dirs) {
        cachedDirs = new HashSet<>(dirs);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putStringSet(KEY_DIRS, dirs).apply();
    }
    
    /**
     * 清除缓存（配置变更后调用）
     */
    public static void clearCache() {
        cachedDirs = null;
    }
}
