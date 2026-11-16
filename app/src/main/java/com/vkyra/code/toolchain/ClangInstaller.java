package com.vkyra.code.toolchain;

import com.vkyra.code.settings.AppPreferences;
import com.vkyra.code.terminal.ManagedProcessRunner;

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
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clang 工具链安装器
 * 支持 arm64-v8a (aarch64) 和 x86_64 架构
 */
public class ClangInstaller {

    private static final String TAG = "ClangInstaller";
    private static final String INSTALL_MARKER = ".clang_installed";
    private static final String CFG_VERSION_FILE = ".clang_cfg_version";
    private static final String CURRENT_CFG_VERSION = "1.1"; // 配置文件版本，修改配置时递增

    // 架构相关常量
    private static final String ARCH_ARM64 = "arm64-v8a";
    private static final String ARCH_X86_64 = "x86_64";

    // 版本默认值（当 assets/clang-version 不可用时兜底）
    private static final String DEFAULT_CLANG_MAJOR = "18";
    private static final Pattern CLANG_VERSION_PATTERN =
            Pattern.compile("clang\\s+version\\s+(\\d+)(?:\\.\\d+)*", Pattern.CASE_INSENSITIVE);

    public interface InstallCallback {
        void onProgress(int current, int total, String fileName);

        void onSuccess(int totalFiles);

        void onError(String error);
    }

    // 缓存架构结果
    private static String cachedArch = null;

    /**
     * 读取当前运行时的机器架构，无需创建子进程
     */
    private static String getKernelArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64") || arch.equals(ARCH_ARM64)) return ARCH_ARM64;
        if (arch.equals("amd64") || arch.equals(ARCH_X86_64)) return ARCH_X86_64;
        return null;
    }

    /**
     * 获取 Build.SUPPORTED_ABIS 返回的系统架构
     */
    private static String getSystemArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            String primaryAbi = abis[0];
            if (primaryAbi.equals(ARCH_ARM64) || primaryAbi.equals("aarch64")) {
                return ARCH_ARM64;
            } else if (primaryAbi.equals(ARCH_X86_64)) {
                return ARCH_X86_64;
            }
        }
        return null;
    }

    /**
     * 检查 assets 中是否存在指定架构的 clang 包
     */
    private static boolean hasArchiveInAssets(Context context, String arch) {
        String archiveName = getArchiveNameForArch(context, arch);
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
     * 获取当前设备的 CPU 架构（需要 Context 来检查 assets）
     * 逻辑：
     * 1. 如果 uname 和 Build.SUPPORTED_ABIS 返回一致，用这个架构
     * 2. 如果不一致，看 assets 里有哪个架构的 clang 包就用哪个
     */
    public static String getDeviceArch(Context context) {
        if (cachedArch != null) {
            return cachedArch;
        }

        String kernelArch = getKernelArch();
        String systemArch = getSystemArch();

        Log.d(TAG, "Kernel arch: " + kernelArch + ", System arch: " + systemArch);

        // 如果两者一致，直接使用
        if (kernelArch != null && kernelArch.equals(systemArch)) {
            cachedArch = kernelArch;
            return cachedArch;
        }

        // 不一致时，检查 assets 里有哪个架构的包
        if (context != null) {
            // 优先检查内核架构对应的包
            if (kernelArch != null && hasArchiveInAssets(context, kernelArch)) {
                Log.d(TAG, "Using kernel arch: " + kernelArch + " (found in assets)");
                cachedArch = kernelArch;
                return cachedArch;
            }
            // 再检查系统架构对应的包
            if (systemArch != null && hasArchiveInAssets(context, systemArch)) {
                Log.d(TAG, "Using system arch: " + systemArch + " (found in assets)");
                cachedArch = systemArch;
                return cachedArch;
            }
        }

        // 都没有，返回内核架构或系统架构
        cachedArch = kernelArch != null ? kernelArch : systemArch;
        return cachedArch;
    }

    /**
     * 获取当前设备的 CPU 架构（无 Context 版本，仅用于简单判断）
     */
    public static String getDeviceArch() {
        if (cachedArch != null) {
            return cachedArch;
        }

        String kernelArch = getKernelArch();
        String systemArch = getSystemArch();

        // 如果两者一致，直接使用
        if (kernelArch != null && kernelArch.equals(systemArch)) {
            return kernelArch;
        }

        // 不一致时，优先返回内核架构
        return kernelArch != null ? kernelArch : systemArch;
    }

    /**
     * 检查当前架构是否支持
     */
    public static boolean isArchSupported() {
        return getDeviceArch() != null;
    }

    /**
     * 检查当前架构是否支持（带 Context）
     */
    public static boolean isArchSupported(Context context) {
        return getDeviceArch(context) != null;
    }

    /**
     * 获取当前架构对应的压缩包名
     */
    private static String getArchiveNameForArch(Context context, String arch) {
        String major = getExpectedClangMajor(context);
        if (ARCH_ARM64.equals(arch)) {
            return "clang-" + major + "-android-aarch64.tar.xz";
        } else if (ARCH_X86_64.equals(arch)) {
            return "clang-" + major + "-android-x86_64.tar.xz";
        }
        return null;
    }

    /**
     * 获取当前架构对应的目录名
     */
    public static String getClangDirName(Context context) {
        String arch = getDeviceArch(context);
        String major = getExpectedClangMajor(context);
        if (ARCH_ARM64.equals(arch)) {
            return "clang-" + major + "-android-aarch64";
        } else if (ARCH_X86_64.equals(arch)) {
            return "clang-" + major + "-android-x86_64";
        }
        return null;
    }


    /**
     * 获取 Clang 安装目录的完整路径
     */
    public static String getClangDir(Context context) {
        String dirName = getClangDirName(context);
        if (dirName == null) return null;
        return new File(context.getFilesDir(), dirName).getAbsolutePath();
    }

    /**
     * 检查 Clang 是否已安装
     */
    public static boolean isInstalled(Context context) {
        String dirName = getClangDirName(context);
        if (dirName == null) return false;

        File marker = new File(context.getFilesDir(), INSTALL_MARKER);
        File clangCompiler = new File(context.getFilesDir(), dirName + "/bin/clang");
        File clangBin = new File(context.getFilesDir(), dirName + "/bin");

        boolean installed = marker.exists() && clangBin.exists() && clangCompiler.exists();


        return installed;
    }

    /**
     * 显示安装确认弹窗
     */
    public static void showInstallDialog(Context context, Runnable onInstalled) {
        String arch = getDeviceArch(context);

        if (arch == null) {
            // 不支持的架构
            new MaterialAlertDialogBuilder(context)
                    .setTitle("不支持的设备")
                    .setMessage("当前设备架构不支持，仅支持 arm64-v8a 和 x86_64 架构。\n\n当前架构: " + Build.SUPPORTED_ABIS[0])
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        String archDisplay = ARCH_ARM64.equals(arch) ? "ARM64" : "x86_64";

        new MaterialAlertDialogBuilder(context)
                .setTitle("安装 Clang 编译器")
                .setMessage("检测到 Clang 编译器未安装，是否现在安装？\n\n" +
                        "当前架构: " + archDisplay + "\n" +
                        "安装后才能编译运行 C/C++ 程序。")
                .setPositiveButton("安装", (dialog, which) -> {
                    installWithProgress(context, onInstalled);
                })
                .setCancelable(false)
                .show();
    }


    /**
     * 带进度条的安装
     */
    public static void installWithProgress(Context context, Runnable onSuccess) {
        Context app = context.getApplicationContext();
        InstallerDialogs.show(context, "安装Clang 编译器",
                (cancellation, progress) -> installInternal(app, false, cancellation, progress), onSuccess);
    }

    /**
     * 异步安装 Clang
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
     * 同步安装 Clang，返回解压的文件数量
     */
    public static int install(Context context, InstallCallback callback) throws IOException {
        return installInternal(context.getApplicationContext(), false, new InstallCancellation(),
                callback == null ? null : callback::onProgress);
    }

    private static int installInternal(Context app, boolean force, InstallCancellation cancellation,
                                       ToolchainArchives.Progress progress) throws IOException {
        String directory = getClangDirName(app);
        String archive = getArchiveNameForArch(app, getDeviceArch(app));
        if (directory == null || archive == null) throw new IOException("找不到对应架构的工具链安装包");
        return new ToolchainTransaction().install(app.getFilesDir(), directory, INSTALL_MARKER, force, cancellation,
                (staging, token) -> {
                    int count = ToolchainArchives.tarXz(app.getAssets().open(archive), staging, directory, token, progress);
                    writeClangConfiguration(app, new File(staging, directory), new File(app.getFilesDir(), directory).getAbsolutePath());
                    return count;
                }, root -> ToolchainArchives.requireFiles(root, directory, "bin/clang", "bin/clang++", "bin/clangd"));
    }

    /**
     * 检查配置文件版本是否需要更新
     */
    

    /**
     * 保存配置文件版本
     */
    

    /**
     * 创建 clang 配置文件，让 clang/clang++ 直接可用
     */
    private static void createWrapperScripts(Context context) {
        String directory = getClangDirName(context);
        if (directory == null) return;
        try {
            ToolchainTransaction.withOwner(context.getFilesDir(), directory, () -> {
                File clang = new File(context.getFilesDir(), directory);
                writeClangConfiguration(context.getApplicationContext(), clang, clang.getAbsolutePath());
                return null;
            });
        } catch (IOException failure) { throw new IllegalStateException("无法更新 Clang 配置", failure); }
    }

    private static void writeClangConfiguration(Context context, File output, String finalDirectory) throws IOException {
        File bin = new File(output, "bin");
        if (!bin.isDirectory()) throw new IOException("安装包缺少 Clang bin 目录");
        String resourceMajor = getExpectedClangMajor(context);
        File resources = new File(output, "lib/clang");
        File[] versions = resources.listFiles();
        if (versions != null) {
            for (File version : versions) {
                if (version.isDirectory() && new File(version, "include").isDirectory()
                        && version.getName().matches("[0-9]+")) {
                    resourceMajor = version.getName();
                    if (resourceMajor.equals(getExpectedClangMajor(context))) break;
                }
            }
        }
        int api = AppPreferences.getAndroidApiLevel(context);
        String triple = ARCH_ARM64.equals(getDeviceArch(context)) ? "aarch64" : "x86_64";
        String common = "--target=" + triple + "-linux-android" + api + "\n"
                + "--sysroot=" + finalDirectory + "/sysroot\n"
                + "-resource-dir=" + finalDirectory + "/lib/clang/" + resourceMajor + "\n";
        String cpp = common + "-stdlib=libc++\n-L" + finalDirectory + "/lib\n-L"
                + finalDirectory + "/" + triple + "-linux-android/lib\n-lc++abi\n";
        writeConfig(new File(bin, "clang.cfg"), common);
        writeConfig(new File(bin, "clang++.cfg"), cpp);
        writeConfig(new File(bin, "cc.cfg"), common);
        writeConfig(new File(bin, "c++.cfg"), cpp);
        writeConfig(new File(output, CFG_VERSION_FILE), CURRENT_CFG_VERSION);
    }

    private static void writeConfig(File destination, String text) throws IOException {
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(destination.getParentFile().toPath(), ".config-", ".tmp");
        try {
            java.nio.file.Files.write(temporary, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try {
                java.nio.file.Files.move(temporary, destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                java.nio.file.Files.move(temporary, destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
    }

    /**
     * 更新 clang 配置文件（当 API Level 改变时调用）
     */
    public static void updateClangConfig(Context context) {
        if (isInstalled(context)) createWrapperScripts(context);
    }


    /**
     * 符号链接信息
     */
    

    /**
     * 创建符号链接
     */

    /**
     * 递归设置目录下所有文件的可执行权限
     */
    

    /**
     * 删除安装标记，用于重新安装
     */
    public static void resetInstallation(Context context) {
        Context app = context.getApplicationContext();
        String directory = getClangDirName(app);
        if (directory == null) return;
        try {
            ToolchainTransaction.withOwner(app.getFilesDir(), directory, () -> {
                java.nio.file.Files.deleteIfExists(new File(app.getFilesDir(), INSTALL_MARKER).toPath());
                return null;
            });
        } catch (IOException failure) { throw new IllegalStateException("无法重置工具链安装状态", failure); }
    }

    /**
     * 从 assets 读取期望的 clang 版本
     */
    public static String getExpectedVersion(Context context) {
        try (InputStream input = context.getAssets().open("clang-version");
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(input,
                     java.nio.charset.StandardCharsets.UTF_8))) {
            String version = reader.readLine();
            return version == null ? null : version.trim();
        } catch (IOException unavailable) { return null; }
    }

    /**
     * 从 assets/clang-version 读取 clang 主版本号（如 18 / 21）。
     */
    private static String getExpectedClangMajor(Context context) {
        String expected = getExpectedVersion(context);
        if (expected != null) {
            Matcher m = CLANG_VERSION_PATTERN.matcher(expected);
            if (m.find()) {
                return m.group(1);
            }
        }
        return DEFAULT_CLANG_MAJOR;
    }

    /**
     * 检测已安装目录中的 resource-dir 主版本（lib/clang/<major>）。
     */
    private static String detectInstalledClangResourceMajor(Context context) {
        String clangDir = getClangDir(context);
        if (clangDir != null) {
            File libClangDir = new File(clangDir, "lib/clang");
            if (libClangDir.exists() && libClangDir.isDirectory()) {
                File[] children = libClangDir.listFiles();
                if (children != null) {
                    int best = -1;
                    for (File c : children) {
                        if (c != null && c.isDirectory()) {
                            String name = c.getName().trim();
                            try {
                                int v = Integer.parseInt(name);
                                if (v > best) best = v;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    if (best > 0) return String.valueOf(best);
                }
            }
        }
        return getExpectedClangMajor(context).toLowerCase(Locale.ROOT);
    }

    /**
     * 获取 resource-dir 的主版本号（lib/clang/<major>）。
     */
    public static String getClangResourceMajor(Context context) {
        return detectInstalledClangResourceMajor(context);
    }

    /**
     * 获取已安装的 clang 版本（通过执行 clang -v）
     */
    public static String getInstalledVersion(Context context) {
        String directory = getClangDir(context);
        if (directory == null || !new File(directory, "bin/clang").isFile()) return null;
        try {
            ManagedProcessRunner.Result result = new ManagedProcessRunner(null).run(
                    new ProcessBuilder(new File(directory, "bin/clang").getAbsolutePath(), "-v").redirectErrorStream(true), 3000);
            if (result.cancelled) return null;
            for (String line : result.output.split("\\r?\\n")) {
                if (line.startsWith("clang version")) return line.trim();
            }
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            Log.w(TAG, "Unable to query installed clang version");
            return null;
        }
    }

    /**
     * 检查已安装的版本是否与期望版本匹配
     *
     * @return true 如果版本匹配，false 如果不匹配或无法检查
     */
    public static boolean isVersionMatched(Context context) {
        String expected = getExpectedVersion(context);
        String installed = getInstalledVersion(context);

        Log.d(TAG, "Expected version: " + expected);
        Log.d(TAG, "Installed version: " + installed);

        if (expected == null || installed == null) {
            return false;
        }

        return expected.equals(installed);
    }

    /**
     * 强制重新安装（暂存、验证、切换，失败保留旧版本）
     */
    public static void reinstallWithProgress(Context context, Runnable onSuccess) {
        Context app = context.getApplicationContext();
        InstallerDialogs.show(context, "重新安装Clang 编译器",
                (cancellation, progress) -> installInternal(app, true, cancellation, progress), onSuccess);
    }

    /**
     * 递归删除目录
     */
    
}
