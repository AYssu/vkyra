package com.vkyra.code.plugin;

import com.vkyra.code.Log;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件管理器 - 提供插件相关的工具方法
 */
public class PluginManager {

    /**
     * 获取所有已启用插件的库目录和库名称
     */
    public static PluginLibInfo getEnabledPluginLibs(Context context) {
        PluginLibInfo info = new PluginLibInfo();
        
        File pluginDir = new File(context.getFilesDir(), "plugin");
        if (!pluginDir.exists() || !pluginDir.isDirectory()) {
            return info;
        }

        File[] plugins = pluginDir.listFiles();
        if (plugins == null) {
            return info;
        }

        for (File plugin : plugins) {
            if (!plugin.isDirectory() || plugin.getName().startsWith(".")
                    || !PluginInstallTransaction.isValidPluginId(plugin.getName())
                    || java.nio.file.Files.isSymbolicLink(plugin.toPath())) continue;

            // 检查是否启用
            File enabledFile = new File(plugin, ".enabled");
            if (!enabledFile.exists()) continue;

            // 读取选择的ABI
            String abi = "arm64-v8a";  // 默认
            File abiFile = new File(plugin, ".abi");
            if (abiFile.exists()) {
                String content = readFileContent(abiFile);
                if (content != null && !content.trim().isEmpty()) {
                    abi = content.trim();
                }
            }

            // 查找lib目录
            File libDir = new File(plugin, "lib/" + abi);
            if (!libDir.exists() || !libDir.isDirectory()) {
                // 尝试不带abi的lib目录
                libDir = new File(plugin, "lib");
                if (!libDir.exists() || !libDir.isDirectory()) {
                    continue;
                }
            }

            // 添加库目录
            info.libDirs.add(libDir.getAbsolutePath());

            // 查找include目录
            File includeDir = new File(plugin, "include");
            if (includeDir.exists() && includeDir.isDirectory()) {
                info.includeDirs.add(includeDir.getAbsolutePath());
            }

            // 扫描静态库文件 (.a)
            File[] libFiles = libDir.listFiles();
            if (libFiles != null) {
                for (File libFile : libFiles) {
                    String name = libFile.getName();
                    if (name.startsWith("lib") && name.endsWith(".a")) {
                        // libXXX.a -> XXX
                        String libName = name.substring(3, name.length() - 2);
                        info.libNames.add(libName);
                    }
                }
            }
        }

        return info;
    }

    private static String readFileContent(File file) {
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            return content.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 插件库信息
     */
    public static class PluginLibInfo {
        public List<String> libDirs = new ArrayList<>();      // -L 目录
        public List<String> includeDirs = new ArrayList<>();  // -I 目录
        public List<String> libNames = new ArrayList<>();     // -l 库名
        
        public boolean hasLibs() {
            return !libNames.isEmpty();
        }
    }
    
    /**
     * 获取所有已启用插件的 PATH（从 .path 文件读取）
     */
    public static String getEnabledPluginPaths(Context context) {
        StringBuilder paths = new StringBuilder();
        
        File pluginDir = new File(context.getFilesDir(), "plugin");
        if (!pluginDir.exists() || !pluginDir.isDirectory()) {
            return "";
        }

        File[] plugins = pluginDir.listFiles();
        if (plugins == null) {
            return "";
        }

        for (File plugin : plugins) {
            if (!plugin.isDirectory() || plugin.getName().startsWith(".")
                    || !PluginInstallTransaction.isValidPluginId(plugin.getName())
                    || java.nio.file.Files.isSymbolicLink(plugin.toPath())) continue;

            // 检查是否启用
            File enabledFile = new File(plugin, ".enabled");
            if (!enabledFile.exists()) continue;

            // 读取 .path 文件
            File pathFile = new File(plugin, ".path");
            if (!pathFile.exists()) continue;
            
            String pluginPath = readFileContent(pathFile);
            if (pluginPath != null && !pluginPath.trim().isEmpty()) {
                if (paths.length() > 0) {
                    paths.append(":");
                }
                paths.append(pluginPath.trim());
            }
        }

        return paths.toString();
    }
    
    // 单例 HookManager
    private static PluginHookManager hookManager;
    
    /**
     * 获取 HookManager 单例
     */
    public static PluginHookManager getHookManager(Context context) {
        if (hookManager == null) {
            android.util.Log.d("PluginManager", "getHookManager: creating new hook manager");
            hookManager = new PluginHookManager(context.getApplicationContext());
        }
        return hookManager;
    }
    
    /**
     * 重新加载 hooks（插件启用/禁用后调用）
     */
    public static void reloadHooks(Context context) {
        if (hookManager != null) {
            android.util.Log.d("PluginManager", "reloadHooks: reloading hook cache");
            hookManager.loadAllHooks();
        } else {
            android.util.Log.w("PluginManager", "reloadHooks: hookManager is null, creating now");
            hookManager = new PluginHookManager(context.getApplicationContext());
        }
    }
}
