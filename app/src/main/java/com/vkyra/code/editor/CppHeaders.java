package com.vkyra.code.editor;

import com.vkyra.code.toolchain.ClangInstaller;
import com.vkyra.code.toolchain.HeaderDirManager;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * C/C++ 头文件补全管理器
 * 从实际的 include 目录扫描头文件
 */
public class CppHeaders {

    private static List<String> cachedHeaders = null;
    private static long lastScanTime = 0;
    private static final long CACHE_DURATION = 60000; // 缓存 60 秒

    /**
     * 获取所有头文件列表（从实际目录扫描）
     */
    public static List<String> getAllHeaders(Context context) {
        long now = System.currentTimeMillis();
        if (cachedHeaders != null && (now - lastScanTime) < CACHE_DURATION) {
            return cachedHeaders;
        }
        
        Set<String> headers = new HashSet<>();
        String filesDir = context.getFilesDir().getAbsolutePath();
        String clangRoot = ClangInstaller.getClangDir(context);
        if (clangRoot == null) return new ArrayList<>();
        
        // 1. 扫描 Clang 内置头文件（clang 会自动处理 C++ 标准库）
        String clangResourceMajor = ClangInstaller.getClangResourceMajor(context);
        File clangIncludeBase = new File(clangRoot + "/lib/clang/" + clangResourceMajor + "/include");
        if (clangIncludeBase.exists()) {
            scanIncludeDir(clangIncludeBase, headers, false);
        }
        
        // 2. 扫描 sysroot 中的系统头文件
        File sysrootInclude = new File(clangRoot + "/sysroot/usr/include");
        if (sysrootInclude.exists()) {
            scanIncludeDir(sysrootInclude, headers, false);
        }
        
        // 3. 扫描插件的 include 目录
        File pluginDir = new File(filesDir, "plugin");
        if (pluginDir.exists() && pluginDir.isDirectory()) {
            File[] plugins = pluginDir.listFiles();
            if (plugins != null) {
                for (File plugin : plugins) {
                    if (plugin.isDirectory()) {
                        File includeDir = new File(plugin, "include");
                        if (includeDir.exists()) {
                            scanIncludeDir(includeDir, headers, true);
                        }
                    }
                }
            }
        }
        
        // 4. 扫描用户自定义的头文件目录
        Set<String> userDirs = HeaderDirManager.getHeaderDirs(context);
        for (String dirPath : userDirs) {
            File dir = new File(dirPath);
            if (dir.exists() && dir.isDirectory()) {
                scanIncludeDir(dir, headers, true);
            }
        }
        
        cachedHeaders = new ArrayList<>(headers);
        lastScanTime = now;
        return cachedHeaders;
    }
    
    /**
     * 扫描 include 目录中的头文件
     * @param dir 目录
     * @param headers 头文件集合
     * @param includeSubPath 是否包含子目录路径（用于自定义目录）
     */
    private static void scanIncludeDir(File dir, Set<String> headers, boolean includeSubPath) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        
        if (includeSubPath) {
            // 递归扫描，保留相对路径
            scanRecursive(dir, dir, headers);
        } else {
            // 只扫描顶层，只取文件名
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        String name = file.getName();
                        // C++ 标准库头文件没有扩展名（如 iostream, vector）
                        // C 头文件有 .h 扩展名
                        if (!name.contains(".") || name.endsWith(".h") || 
                            name.endsWith(".hpp") || name.endsWith(".hxx")) {
                            headers.add(name);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 递归扫描目录
     */
    private static void scanRecursive(File baseDir, File currentDir, Set<String> headers) {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                scanRecursive(baseDir, file, headers);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".h") || name.endsWith(".hpp") || 
                    name.endsWith(".hxx") || name.endsWith(".hh")) {
                    // 计算相对路径
                    String relativePath = getRelativePath(baseDir, file);
                    headers.add(relativePath);
                }
            }
        }
    }
    
    /**
     * 获取文件相对于基础目录的路径
     */
    private static String getRelativePath(File baseDir, File file) {
        String basePath = baseDir.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        
        if (filePath.startsWith(basePath)) {
            String relative = filePath.substring(basePath.length());
            if (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return relative;
        }
        return file.getName();
    }

    /**
     * 清除缓存，下次会重新扫描
     */
    public static void clearCache() {
        cachedHeaders = null;
        lastScanTime = 0;
    }
    
    // 保留旧接口的兼容性（返回空列表，因为现在统一用 getAllHeaders）
    public static List<String> getCHeaders(Context context) {
        return new ArrayList<>();
    }
    
    public static List<String> getCppHeaders(Context context) {
        return new ArrayList<>();
    }
    
    public static List<String> getCustomHeaders(Context context) {
        return new ArrayList<>();
    }
}
