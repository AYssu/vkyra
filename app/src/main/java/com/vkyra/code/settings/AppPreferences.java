package com.vkyra.code.settings;

import com.vkyra.code.toolchain.ClangInstaller;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Stable settings schema and access; independent of the settings page. */
public final class AppPreferences {
    private AppPreferences() {}


    private static final String LOG_TAG = "AppPreferences";
    public static final String PREFS_NAME = "CppIDESettings";
    
    // 设置键名
    public static final String KEY_FONT_SIZE = "font_size";
    public static final String KEY_TERMINAL_FONT = "terminal_font";
    public static final String KEY_EDITOR_FONT_SIZE = "editor_font_size";
    public static final String KEY_EDITOR_THEME = "editor_theme";
    public static final String KEY_ENABLE_LOGS = "enable_logs";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_SHOW_BOTTOM_TOOLBAR = "show_bottom_toolbar";
    public static final String KEY_CLEAR_CACHE = "clear_cache";
    public static final String KEY_EXPORT_LOGS = "export_logs";
    public static final String KEY_PROJECT_ROOT = "project_root";
    public static final String KEY_ABOUT = "about";
    public static final String KEY_REMEMBER_LAST_FILE = "remember_last_file";
    public static final String KEY_LAST_FILE_PATH = "last_file_path";
    public static final String KEY_LAST_DIR_PATH = "last_dir_path";
    public static final String KEY_ENABLE_ROOT_RUN = "enable_root_run";
    public static final String KEY_USE_SOCKET_MODE = "use_socket_mode";  // 使用 socket 模式避免 /dev/pts/ 检测
    public static final String KEY_ACKNOWLEDGEMENTS = "acknowledgements";
    public static final String KEY_CLANG_ARGS = "clang_args";
    public static final String KEY_CUSTOM_SU = "custom_su";
    public static final String KEY_COMPILE_COMMANDS = "compile_commands";
    public static final String KEY_AUTO_SAVE = "auto_save";
    public static final String KEY_AUTO_SAVE_INTERVAL = "auto_save_interval";
    public static final String KEY_ANDROID_API_LEVEL = "android_api_level";
    
    // OLLVM/iROBF 混淆参数 keys
    public static final String KEY_OBF_IROBF_FLA = "obf_irobf_fla";
    public static final String KEY_OBF_IROBF_INDBR = "obf_irobf_indbr";
    public static final String KEY_OBF_IROBF_ICALL = "obf_irobf_icall";
    public static final String KEY_OBF_IROBF_INDGV = "obf_irobf_indgv";
    public static final String KEY_OBF_IROBF_CSE = "obf_irobf_cse";
    public static final String KEY_OBF_IROBF_CIE = "obf_irobf_cie";
    public static final String KEY_OBF_IROBF_CFE = "obf_irobf_cfe";
    public static final String KEY_OBF_IROBF_RTTI = "obf_irobf_rtti";
    
    // 默认编译参数（Clang 统一参数，clang.cfg 已配置基础参数）
    public static final String DEFAULT_CLANG_ARGS = "-std=c++17 -Wfatal-errors -O2 -s";
    public static final String DEFAULT_CUSTOM_SU = "su -c";

    // 获取终端字体大小
    public static int getFontSize(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            return Integer.parseInt(prefs.getString(KEY_FONT_SIZE, "26"));
        } catch (ClassCastException e) {
            // 旧版本存的是 int，迁移为 string
            int oldValue = prefs.getInt(KEY_FONT_SIZE, 26);
            prefs.edit().remove(KEY_FONT_SIZE).putString(KEY_FONT_SIZE, String.valueOf(oldValue)).apply();
            return oldValue;
        } catch (Exception e) {
            return 26;
        }
    }

    public static void setFontSize(Context context, int size) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_FONT_SIZE, String.valueOf(size)).apply();
    }

    public static String getTerminalFont(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_TERMINAL_FONT, "JetBrainsMono-Bold.ttf");
    }

    public static void setTerminalFont(Context context, String fontName) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_TERMINAL_FONT, fontName).apply();
    }

    // 编辑器设置
    public static int getEditorFontSize(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            return Integer.parseInt(prefs.getString(KEY_EDITOR_FONT_SIZE, "14"));
        } catch (ClassCastException e) {
            // 旧版本存的是 int，迁移为 string
            int oldValue = prefs.getInt(KEY_EDITOR_FONT_SIZE, 14);
            prefs.edit().remove(KEY_EDITOR_FONT_SIZE).putString(KEY_EDITOR_FONT_SIZE, String.valueOf(oldValue)).apply();
            return oldValue;
        } catch (Exception e) {
            return 14;
        }
    }

    public static void setEditorFontSize(Context context, int size) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_EDITOR_FONT_SIZE, String.valueOf(size)).apply();
    }

    public static String getEditorTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_EDITOR_THEME, "dark");  // 默认深色主题
    }

    public static void setEditorTheme(Context context, String theme) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_EDITOR_THEME, theme).apply();
    }

    public static boolean getEnableLogs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ENABLE_LOGS, false);
    }

    public static void setEnableLogs(Context context, boolean enable) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ENABLE_LOGS, enable).apply();
    }
    
    public static void setKeepScreenOn(Context context, boolean keepScreenOn) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn).apply();
    }
    
    public static boolean getKeepScreenOn(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, false);
    }
    
    public static void setShowBottomToolbar(Context context, boolean show) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_SHOW_BOTTOM_TOOLBAR, show).apply();
    }
    
    public static boolean getShowBottomToolbar(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_SHOW_BOTTOM_TOOLBAR, true);
    }
    
    // 记住上次打开的文件
    public static void setRememberLastFile(Context context, boolean remember) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_REMEMBER_LAST_FILE, remember).apply();
    }
    
    public static boolean getRememberLastFile(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_REMEMBER_LAST_FILE, true);
    }
    
    public static void setLastFilePath(Context context, String path) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_FILE_PATH, path).apply();
    }
    
    public static String getLastFilePath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_FILE_PATH, null);
    }
    
    public static void setLastDirPath(Context context, String path) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_DIR_PATH, path).apply();
    }
    
    public static String getLastDirPath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LAST_DIR_PATH, null);
    }
    
    // 启用Root运行
    public static void setEnableRootRun(Context context, boolean enable) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ENABLE_ROOT_RUN, enable).apply();
    }
    
    public static boolean getEnableRootRun(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ENABLE_ROOT_RUN, false);
    }
    
    // Clang 编译参数
    public static void setClangArgs(Context context, String args) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CLANG_ARGS, args).apply();
    }
    
    public static String getClangArgs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CLANG_ARGS, DEFAULT_CLANG_ARGS);
    }
    
    // Android API Level (21-35, 默认21)
    public static void setAndroidApiLevel(Context context, int level) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_ANDROID_API_LEVEL, level).apply();
    }
    
    public static int getAndroidApiLevel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_ANDROID_API_LEVEL, 21);
    }
    
    // 获取完整的 target 参数
    public static String getTargetTriple(Context context) {
        int apiLevel = getAndroidApiLevel(context);
        String arch = ClangInstaller.getDeviceArch(context);
        if ("x86_64".equals(arch)) {
            return "--target=x86_64-linux-android" + apiLevel;
        }
        // 默认 arm64-v8a
        return "--target=aarch64-linux-android" + apiLevel;
    }
    
    // 自定义 SU 命令
    public static void setCustomSu(Context context, String su) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CUSTOM_SU, su).apply();
    }
    
    public static String getCustomSu(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CUSTOM_SU, DEFAULT_CUSTOM_SU);
    }
    
    // 项目根目录
    public static final String DEFAULT_PROJECT_ROOT = "/storage/emulated/0/Akode";
    
    public static void setProjectRoot(Context context, String path) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PROJECT_ROOT, path).apply();
    }
    
    public static String getProjectRoot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_PROJECT_ROOT, DEFAULT_PROJECT_ROOT);
    }
    
    public static boolean getAutoSave(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_AUTO_SAVE, true);
    }
    
    public static void setAutoSave(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_AUTO_SAVE, enabled).apply();
    }
    
    public static int getAutoSaveInterval(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_AUTO_SAVE_INTERVAL, 10); // 默认10秒
    }
    
    public static void setAutoSaveInterval(Context context, int seconds) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_AUTO_SAVE_INTERVAL, seconds).apply();
    }

    public static void ensureDefaultSettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        
        if (!prefs.contains(KEY_FONT_SIZE)) {
            setFontSize(context, 26);
        }
        if (!prefs.contains(KEY_EDITOR_THEME)) {
            setEditorTheme(context, "dark");
        }
        if (!prefs.contains(KEY_ENABLE_LOGS)) {
            setEnableLogs(context, false);
        }
        if (!prefs.contains(KEY_SHOW_BOTTOM_TOOLBAR)) {
            setShowBottomToolbar(context, true);
        }
        if (!prefs.contains(KEY_REMEMBER_LAST_FILE)) {
            setRememberLastFile(context, true);
        }
        if (!prefs.contains(KEY_SYMBOL_ROWS)) {
            setSymbolRows(context, DEFAULT_SYMBOL_ROWS);
        }
    }
    
    // ==================== 运行模式配置 ====================
    
    private static final String KEY_RUN_MODE = "run_mode";
    
    /**
     * 获取运行模式
     * @return 0=运行, 1=带参数运行, 2=终端编译运行, 3=重新编译, 4=编译输出
     */
    public static int getRunMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_RUN_MODE, 0);
    }
    
    /**
     * 设置运行模式
     */
    public static void setRunMode(Context context, int mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_RUN_MODE, mode).apply();
    }

    public static void loadFromConfigFile(Context context) {
        // 简化版本，不再需要从外部文件加载
    }
    
    public static void sendFontSettingsBroadcast(Context context) {
        try {
            Intent intent = new Intent("com.vkyra.code.FONT_SETTINGS_CHANGED");
            intent.putExtra("font_size", getFontSize(context));
            intent.putExtra("terminal_font", getTerminalFont(context));
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending font settings broadcast", e);
        }
    }
    
    public static void sendBottomToolbarSettingsBroadcast(Context context) {
        try {
            Intent intent = new Intent("com.vkyra.code.BOTTOM_TOOLBAR_SETTINGS_CHANGED");
            intent.putExtra("show_bottom_toolbar", getShowBottomToolbar(context));
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error sending bottom toolbar settings broadcast", e);
        }
    }
    
    // ==================== 快捷键配置 ====================
    
    public static final String KEY_SYMBOL_KEYS = "symbol_keys";
    public static final String KEY_SYMBOL_ROWS = "symbol_rows";
    public static final String DEFAULT_SYMBOL_KEYS = "TAB,↩,↪,//,{,},(,),[,],<,>,;,:,\",',=,+,-,*,/,&,|,!,?,#,_,\\,~,^";
    public static final int DEFAULT_SYMBOL_ROWS = 2;
    
    public static void setSymbolKeys(Context context, String keys) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SYMBOL_KEYS, keys).apply();
    }
    
    public static String getSymbolKeys(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SYMBOL_KEYS, DEFAULT_SYMBOL_KEYS);
    }
    
    public static String[] getSymbolKeysArray(Context context) {
        String keys = getSymbolKeys(context);
        return keys.split(",");
    }
    
    public static void setSymbolRows(Context context, int rows) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 限制在 1-3 行
        rows = Math.max(1, Math.min(3, rows));
        prefs.edit().putInt(KEY_SYMBOL_ROWS, rows).apply();
    }
    
    public static int getSymbolRows(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_SYMBOL_ROWS, DEFAULT_SYMBOL_ROWS);
    }
    
    // ==================== 编译命令管理 ====================
    
    /**
     * 编译命令数据类
     */
    public static class CompileCommand {
        public String name;
        public String compileCmd;
        public String runCmd;
        public boolean enabled;
        public boolean isDefault;
        
        public CompileCommand() {}
        
        public CompileCommand(String name, String compileCmd, String runCmd, boolean enabled) {
            this.name = name;
            this.compileCmd = compileCmd;
            this.runCmd = runCmd;
            this.enabled = enabled;
            this.isDefault = false;
        }
        
        public CompileCommand(String name, String compileCmd, String runCmd, boolean enabled, boolean isDefault) {
            this.name = name;
            this.compileCmd = compileCmd;
            this.runCmd = runCmd;
            this.enabled = enabled;
            this.isDefault = isDefault;
        }
    }
    
    /**
     * 获取编译命令列表
     */
    public static List<CompileCommand> getCompileCommands(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_COMPILE_COMMANDS, "[]");
        List<CompileCommand> commands = new ArrayList<>();
        
        try {
            org.json.JSONArray array = new org.json.JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.getJSONObject(i);
                CompileCommand cmd = new CompileCommand();
                cmd.name = obj.optString("name", "");
                cmd.compileCmd = obj.optString("compileCmd", "");
                cmd.runCmd = obj.optString("runCmd", "");
                cmd.enabled = obj.optBoolean("enabled", true);
                cmd.isDefault = obj.optBoolean("isDefault", false);
                commands.add(cmd);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error parsing compile commands", e);
        }
        
        return commands;
    }
    
    /**
     * 保存编译命令列表
     */
    public static void saveCompileCommands(Context context, List<CompileCommand> commands) {
        try {
            org.json.JSONArray array = new org.json.JSONArray();
            for (CompileCommand cmd : commands) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("name", cmd.name);
                obj.put("compileCmd", cmd.compileCmd);
                obj.put("runCmd", cmd.runCmd);
                obj.put("enabled", cmd.enabled);
                obj.put("isDefault", cmd.isDefault);
                array.put(obj);
            }
            
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_COMPILE_COMMANDS, array.toString()).apply();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error saving compile commands", e);
        }
    }
    
    /**
     * 获取已启用的编译命令列表
     */
    public static List<CompileCommand> getEnabledCompileCommands(Context context) {
        List<CompileCommand> all = getCompileCommands(context);
        List<CompileCommand> enabled = new ArrayList<>();
        for (CompileCommand cmd : all) {
            if (cmd.enabled) {
                enabled.add(cmd);
            }
        }
        return enabled;
    }
    
    /**
     * 获取默认编译命令（已启用且标记为默认的）
     */
    public static CompileCommand getDefaultCompileCommand(Context context) {
        List<CompileCommand> all = getCompileCommands(context);
        for (CompileCommand cmd : all) {
            if (cmd.enabled && cmd.isDefault) {
                return cmd;
            }
        }
        return null;
    }
    
    // ==================== OLLVM 混淆参数 ====================
    
    /**
     * 获取 OLLVM 混淆参数字符串
     * 根据设置中启用的混淆选项，生成对应的命令行参数
     */
    public static String getOllvmArgs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        StringBuilder args = new StringBuilder();

        if (prefs.getBoolean(KEY_OBF_IROBF_INDBR, false)) {
            args.append(" -mllvm -irobf-indbr");
        }
        if (prefs.getBoolean(KEY_OBF_IROBF_ICALL, false)) {
            args.append(" -mllvm -irobf-icall");
        }
        if (prefs.getBoolean(KEY_OBF_IROBF_INDGV, false)) {
            args.append(" -mllvm -irobf-indgv");
        }
        if (prefs.getBoolean(KEY_OBF_IROBF_CSE, false)) {
            args.append(" -mllvm -irobf-cse");
        }
        if (prefs.getBoolean(KEY_OBF_IROBF_FLA, false)) {
            args.append(" -mllvm -irobf-fla");
        }
        if (prefs.getBoolean(KEY_OBF_IROBF_CIE, false)) {
            args.append(" -mllvm -irobf-cie");
        }
        if (prefs.getBoolean(KEY_OBF_IROBF_CFE, false)) {
            args.append(" -mllvm -irobf-cfe");
        }
        if (prefs.getBoolean(KEY_OBF_IROBF_RTTI, false)) {
            args.append(" -mllvm -irobf-rtti");
        }

        return args.toString().trim();
    }
    
    /**
     * 检查是否启用了任何混淆选项
     */
    public static boolean isOllvmEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_OBF_IROBF_INDBR, false) ||
               prefs.getBoolean(KEY_OBF_IROBF_ICALL, false) ||
               prefs.getBoolean(KEY_OBF_IROBF_INDGV, false) ||
               prefs.getBoolean(KEY_OBF_IROBF_CSE, false) ||
               prefs.getBoolean(KEY_OBF_IROBF_FLA, false) ||
               prefs.getBoolean(KEY_OBF_IROBF_CIE, false) ||
               prefs.getBoolean(KEY_OBF_IROBF_CFE, false) ||
               prefs.getBoolean(KEY_OBF_IROBF_RTTI, false);
    }

    private static int parsePositiveIntOrDefault(String raw, int defaultValue) {
        if (raw == null || raw.trim().isEmpty()) {
            return Math.max(1, defaultValue);
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : Math.max(1, defaultValue);
        } catch (NumberFormatException ignored) {
            return Math.max(1, defaultValue);
        }
    }
    
    // ==================== Make/CMake 编译参数 ====================
    
    private static final String KEY_MAKE_ARGS = "make_args";
    private static final String KEY_MAKE_PARALLEL = "make_parallel";
    private static final String KEY_CMAKE_ARGS = "cmake_args";
    private static final String KEY_CMAKE_USE_NINJA = "cmake_use_ninja";
    private static final String KEY_CMAKE_PARALLEL = "cmake_parallel";
    private static final String KEY_CMAKE_PARALLEL_THREADS = "cmake_parallel_threads";
    
    // Make 参数
    public static String getMakeArgs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MAKE_ARGS, "");
    }
    
    public static void setMakeArgs(Context context, String args) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MAKE_ARGS, args).apply();
    }
    
    public static boolean getMakeParallel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_MAKE_PARALLEL, true);
    }
    
    public static void setMakeParallel(Context context, boolean parallel) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_MAKE_PARALLEL, parallel).apply();
    }
    
    // CMake 参数
    public static String getCMakeArgs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CMAKE_ARGS, "");
    }
    
    public static void setCMakeArgs(Context context, String args) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CMAKE_ARGS, args).apply();
    }
    
    public static boolean getCMakeUseNinja(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_CMAKE_USE_NINJA, false);
    }
    
    public static void setCMakeUseNinja(Context context, boolean useNinja) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_CMAKE_USE_NINJA, useNinja).apply();
    }
    
    public static boolean getCMakeParallel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_CMAKE_PARALLEL, true);
    }
    
    public static void setCMakeParallel(Context context, boolean parallel) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_CMAKE_PARALLEL, parallel).apply();
    }
    
    public static int getCMakeParallelThreads(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_CMAKE_PARALLEL_THREADS, 4);
    }
    
    public static void setCMakeParallelThreads(Context context, int threads) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_CMAKE_PARALLEL_THREADS, threads).apply();
    }
    
    // CMake 产物运行参数
    private static final String KEY_CMAKE_OUTPUT_NAME = "cmake_output_name";
    private static final String KEY_CMAKE_RUN_ARGS = "cmake_run_args";
    private static final String KEY_CMAKE_COPY_TO_TMP = "cmake_copy_to_tmp";
    
    public static String getCMakeOutputName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CMAKE_OUTPUT_NAME, "");
    }
    
    public static void setCMakeOutputName(Context context, String name) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CMAKE_OUTPUT_NAME, name).apply();
    }
    
    public static String getCMakeRunArgs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_CMAKE_RUN_ARGS, "");
    }
    
    public static void setCMakeRunArgs(Context context, String args) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CMAKE_RUN_ARGS, args).apply();
    }
    
    public static boolean getCMakeCopyToTmp(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_CMAKE_COPY_TO_TMP, false);
    }
    
    public static void setCMakeCopyToTmp(Context context, boolean copyToTmp) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_CMAKE_COPY_TO_TMP, copyToTmp).apply();
    }
    
}
