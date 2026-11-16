package com.vkyra.code.toolchain;

import com.vkyra.code.installer.InstallCancellation;
import com.vkyra.code.installer.InstallJob;
import com.vkyra.code.installer.InstallJobs;
import com.vkyra.code.installer.InstallerDialogs;
import com.vkyra.code.installer.ToolchainArchives;
import com.vkyra.code.installer.ToolchainTransaction;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;

/**
 * 编译工具安装器
 * 支持 arm64-v8a (aarch64) 和 x86_64 架构
 * 包含: cmake, make, ninja
 */
public class BuildToolsInstaller {
    
    private static final String TAG = "BuildToolsInstaller";
    private static final String INSTALL_MARKER = ".build_tools_installed";
    
    // 架构相关常量
    private static final String ARCH_ARM64 = "arm64-v8a";
    private static final String ARCH_X86_64 = "x86_64";
    
    // 压缩包和目录名
    private static final String ARCHIVE_ARM64 = "build-tools-aarch64.tar.xz";
    private static final String ARCHIVE_X86_64 = "build-tools-x86_64.tar.xz";
    private static final String DIR_ARM64 = "build-tools-aarch64";
    private static final String DIR_X86_64 = "build-tools-x86_64";
    
    public interface InstallCallback {
        void onProgress(int current, int total, String fileName);
        void onSuccess(int totalFiles);
        void onError(String error);
    }
    
    // 缓存架构结果
    private static String cachedArch = null;
    
    /**
     * 获取设备架构（复用 ClangInstaller 的逻辑）
     */
    public static String getDeviceArch(Context context) {
        if (cachedArch != null) {
            return cachedArch;
        }
        
        // 优先使用 ClangInstaller 的架构检测
        String clangArch = ClangInstaller.getDeviceArch(context);
        if (clangArch != null) {
            cachedArch = clangArch;
            return cachedArch;
        }
        
        // 备用检测
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            String primaryAbi = abis[0];
            if (primaryAbi.equals(ARCH_ARM64) || primaryAbi.equals("aarch64")) {
                cachedArch = ARCH_ARM64;
            } else if (primaryAbi.equals(ARCH_X86_64)) {
                cachedArch = ARCH_X86_64;
            }
        }
        return cachedArch;
    }

    
    /**
     * 获取当前架构对应的压缩包名
     */
    private static String getArchiveNameForArch(String arch) {
        if (ARCH_ARM64.equals(arch)) {
            return ARCHIVE_ARM64;
        } else if (ARCH_X86_64.equals(arch)) {
            return ARCHIVE_X86_64;
        }
        return null;
    }
    
    /**
     * 获取当前架构对应的目录名
     */
    public static String getBuildToolsDirName(Context context) {
        String arch = getDeviceArch(context);
        if (ARCH_ARM64.equals(arch)) {
            return DIR_ARM64;
        } else if (ARCH_X86_64.equals(arch)) {
            return DIR_X86_64;
        }
        return null;
    }
    
    /**
     * 获取编译工具安装目录
     */
    public static String getBuildToolsDir(Context context) {
        String dirName = getBuildToolsDirName(context);
        if (dirName == null) return null;
        return new File(context.getFilesDir(), dirName).getAbsolutePath();
    }
    
    /**
     * 获取 cmake 路径
     */
    public static String getCmakePath(Context context) {
        String dir = getBuildToolsDir(context);
        if (dir == null) return null;
        return new File(dir, "bin/cmake").getAbsolutePath();
    }
    
    /**
     * 获取 make 路径
     */
    public static String getMakePath(Context context) {
        String dir = getBuildToolsDir(context);
        if (dir == null) return null;
        return new File(dir, "bin/make").getAbsolutePath();
    }
    
    /**
     * 获取 ninja 路径
     */
    public static String getNinjaPath(Context context) {
        String dir = getBuildToolsDir(context);
        if (dir == null) return null;
        return new File(dir, "bin/ninja").getAbsolutePath();
    }
    
    /**
     * 检查编译工具是否已安装
     */
    public static boolean isInstalled(Context context) {
        String dirName = getBuildToolsDirName(context);
        if (dirName == null) return false;
        
        File marker = new File(context.getFilesDir(), INSTALL_MARKER);
        File binDir = new File(context.getFilesDir(), dirName + "/bin");
        File cmakeBin = new File(binDir, "cmake");
        File makeBin = new File(binDir, "make");
        File ninjaBin = new File(binDir, "ninja");
        
        boolean installed = marker.exists() && cmakeBin.exists() && makeBin.exists() && ninjaBin.exists();
        
        
        return installed;
    }
    
    /**
     * 检查 assets 中是否存在对应架构的编译工具包
     */
    public static boolean hasArchiveInAssets(Context context) {
        String arch = getDeviceArch(context);
        if (arch == null) return false;
        
        String archiveName = getArchiveNameForArch(arch);
        if (archiveName == null) return false;
        
        try {
            String[] assets = context.getAssets().list("");
            if (assets != null) {
                for (String asset : assets) {
                    if (asset.equals(archiveName)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to check assets", e);
        }
        return false;
    }
    
    /**
     * 显示安装确认弹窗
     */
    public static void showInstallDialog(Context context, Runnable onInstalled) {
        String arch = getDeviceArch(context);
        
        if (arch == null) {
            new MaterialAlertDialogBuilder(context)
                .setTitle("不支持的设备")
                .setMessage("当前设备架构不支持，仅支持 arm64-v8a 和 x86_64 架构。")
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        
        if (!hasArchiveInAssets(context)) {
            String archiveName = getArchiveNameForArch(arch);
            new MaterialAlertDialogBuilder(context)
                .setTitle("安装包不存在")
                .setMessage("未找到编译工具安装包 (" + archiveName + ")")
                .setPositiveButton("确定", null)
                .show();
            return;
        }
        
        String archDisplay = ARCH_ARM64.equals(arch) ? "ARM64" : "x86_64";
        
        new MaterialAlertDialogBuilder(context)
            .setTitle("安装编译工具")
            .setMessage("是否安装编译工具？\n\n" +
                "当前架构: " + archDisplay + "\n\n" +
                "包含:\n• CMake - 跨平台构建系统\n• Make - 传统构建工具\n• Ninja - 高速构建工具\n\n" +
                "安装后可在终端中使用这些工具编译项目。")
            .setPositiveButton("安装", (dialog, which) -> {
                installWithProgress(context, onInstalled);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    
    /**
     * 带进度条的安装
     */
    public static void installWithProgress(Context context, Runnable onSuccess) {
        Context app = context.getApplicationContext();
        InstallerDialogs.show(context, "安装编译工具",
                (cancellation, progress) -> installInternal(app, false, cancellation, progress), onSuccess);
    }
    
    /**
     * 异步安装
     */
    public static InstallJob installAsync(Context context, InstallCallback callback) {
        Context app = context.getApplicationContext();
        return InstallJobs.start(context, (cancellation, progress) -> installInternal(app, false, cancellation, progress),
                new InstallJob.Listener() {
                    @Override public void progress(int current, int total, String name) {
                        if (callback != null) callback.onProgress(current, total, name);
                    }
                    @Override public void success(int files) { if (callback != null) callback.onSuccess(files); }
                    @Override public void error(String error) { if (callback != null) callback.onError(error); }
                });
    }
    
    /**
     * 同步安装，返回解压的文件数量
     */
    public static int install(Context context, InstallCallback callback) throws IOException {
        return installInternal(context.getApplicationContext(), false, new InstallCancellation(),
                callback == null ? null : callback::onProgress);
    }

    private static int installInternal(Context app, boolean force, InstallCancellation cancellation,
                                       ToolchainArchives.Progress progress) throws IOException {
        String directory = getBuildToolsDirName(app);
        String archive = getArchiveNameForArch(getDeviceArch(app));
        if (directory == null || archive == null) throw new IOException("找不到对应架构的工具链安装包");
        return new ToolchainTransaction().install(app.getFilesDir(), directory, INSTALL_MARKER, force, cancellation,
                (staging, token) -> {
                    int count = ToolchainArchives.tarXz(app.getAssets().open(archive), staging, directory, token, progress);
                    return count;
                }, root -> ToolchainArchives.requireFiles(root, directory, "bin/cmake", "bin/make", "bin/ninja"));
    }

    
    /**
     * 符号链接信息
     */
    
    
    /**
     * 创建符号链接
     */
    
    
    /**
     * 统计 tar.xz 文件中的条目数量
     */
    
    
    /**
     * 递归设置目录下所有文件的可执行权限
     */
    
    
    /**
     * 删除安装标记，用于重新安装
     */
    public static void resetInstallation(Context context) {
        Context app = context.getApplicationContext();
        String directory = getBuildToolsDirName(app);
        if (directory == null) return;
        try {
            ToolchainTransaction.withOwner(app.getFilesDir(), directory, () -> {
                java.nio.file.Files.deleteIfExists(new File(app.getFilesDir(), INSTALL_MARKER).toPath());
                return null;
            });
        } catch (IOException failure) { throw new IllegalStateException("无法重置工具链安装状态", failure); }
    }
    
    /**
     * 卸载编译工具
     */
    public static void uninstall(Context context) {
        Context app = context.getApplicationContext();
        String directory = getBuildToolsDirName(app);
        if (directory == null) return;
        try {
            ToolchainTransaction.withOwner(app.getFilesDir(), directory, () -> {
                ToolchainTransaction.deleteTree(new File(app.getFilesDir(), directory).toPath());
                java.nio.file.Files.deleteIfExists(new File(app.getFilesDir(), INSTALL_MARKER).toPath());
                return null;
            });
        } catch (IOException failure) { throw new IllegalStateException("无法卸载工具链", failure); }
    }
    
    /**
     * 递归删除目录
     */
    
    
    /**
     * 重新安装
     */
    public static void reinstallWithProgress(Context context, Runnable onSuccess) {
        Context app = context.getApplicationContext();
        InstallerDialogs.show(context, "重新安装编译工具",
                (cancellation, progress) -> installInternal(app, true, cancellation, progress), onSuccess);
    }
}
