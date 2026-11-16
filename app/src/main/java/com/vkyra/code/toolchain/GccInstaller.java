package com.vkyra.code.toolchain;

import com.vkyra.code.installer.InstallCancellation;
import com.vkyra.code.installer.InstallJob;
import com.vkyra.code.installer.InstallJobs;
import com.vkyra.code.installer.InstallerDialogs;
import com.vkyra.code.installer.ToolchainArchives;
import com.vkyra.code.installer.ToolchainTransaction;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;

/**
 * GCC 工具链安装器
 * 负责从 assets 解压 gcc.zip 到 files 目录
 */
public class GccInstaller {
    
    private static final String TAG = "GccInstaller";
    private static final String GCC_ZIP = "gcc.zip";
    private static final String INSTALL_MARKER = ".gcc_installed";
    
    public interface InstallCallback {
        void onProgress(int current, int total, String fileName);
        void onSuccess(int totalFiles);
        void onError(String error);
    }
    
    /**
     * 检查 GCC 是否已安装
     */
    public static boolean isInstalled(Context context) {
        File marker = new File(context.getFilesDir(), INSTALL_MARKER);
        // 检查实际的编译器文件是否存在
        File gccCompiler = new File(context.getFilesDir(), "gcc/bin/aarch64-linux-android-g++");
        File gccBin = new File(context.getFilesDir(), "gcc/bin");
        
        // 必须标记文件存在、bin目录存在、且编译器文件存在
        boolean installed = marker.exists() && gccBin.exists() && gccCompiler.exists();
        
        
        return installed;
    }
    
    /**
     * 显示安装确认弹窗
     */
    public static void showInstallDialog(Context context, Runnable onInstalled) {
        new MaterialAlertDialogBuilder(context)
            .setTitle("安装 GCC 编译器")
            .setMessage("检测到 GCC 编译器未安装，是否现在安装？\n\n安装后才能编译运行 C/C++ 程序。")
            .setPositiveButton("安装", (dialog, which) -> {
                installWithProgress(context, onInstalled);
            })
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show();
    }
    
    /**
     * 带进度条的安装
     */
    public static void installWithProgress(Context context, Runnable onSuccess) {
        Context app = context.getApplicationContext();
        InstallerDialogs.show(context, "安装GCC 编译器",
                (cancellation, progress) -> installInternal(app, false, cancellation, progress), onSuccess);
    }

    
    /**
     * 异步安装 GCC
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
     * 同步安装 GCC，返回解压的文件数量
     */
    public static int install(Context context, InstallCallback callback) throws IOException {
        return installInternal(context.getApplicationContext(), false, new InstallCancellation(),
                callback == null ? null : callback::onProgress);
    }

    private static int installInternal(Context app, boolean force, InstallCancellation cancellation,
                                       ToolchainArchives.Progress progress) throws IOException {
        String directory = "gcc";
        String archive = GCC_ZIP;
        if (directory == null || archive == null) throw new IOException("找不到对应架构的工具链安装包");
        return new ToolchainTransaction().install(app.getFilesDir(), directory, INSTALL_MARKER, force, cancellation,
                (staging, token) -> {
                    int count = ToolchainArchives.zip(app.getAssets().open(archive), staging, directory, token, progress);
                    return count;
                }, root -> ToolchainArchives.requireFiles(root, directory, "bin/aarch64-linux-android-g++"));
    }
    
    /**
     * 统计 zip 文件中的条目数量
     */
    
    
    /**
     * 递归设置目录下所有文件的可执行权限
     */
    
    
    /**
     * 删除安装标记，用于重新安装
     */
    public static void resetInstallation(Context context) {
        Context app = context.getApplicationContext();
        String directory = "gcc";
        if (directory == null) return;
        try {
            ToolchainTransaction.withOwner(app.getFilesDir(), directory, () -> {
                java.nio.file.Files.deleteIfExists(new File(app.getFilesDir(), INSTALL_MARKER).toPath());
                return null;
            });
        } catch (IOException failure) { throw new IllegalStateException("无法重置工具链安装状态", failure); }
    }
}
