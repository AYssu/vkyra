package com.vkyra.code.toolchain;

import com.vkyra.code.Log;

import com.vkyra.code.installer.InstallCancellation;
import com.vkyra.code.installer.InstallJob;
import com.vkyra.code.installer.InstallJobs;
import com.vkyra.code.installer.InstallerDialogs;
import com.vkyra.code.installer.ToolchainArchives;
import com.vkyra.code.installer.ToolchainTransaction;

import android.content.Context;
import android.os.Build;


import java.io.File;
import java.io.IOException;

/**
 * Zsh Shell 安装器
 * 支持 arm64-v8a (aarch64) 和 x86_64 架构
 * 从 assets 中解压 zsh 包到应用目录
 */
public class ZshInstaller {
    
    private static final String TAG = "ZshInstaller";
    private static final String INSTALL_MARKER = ".zsh_installed";
    private static final String ZSHRC_VERSION_FILE = ".zshrc_version";
    private static final String CURRENT_ZSHRC_VERSION = "1.6"; // .zshrc 配置版本，修改配置时递增
    
    // 架构相关常量
    private static final String ARCH_ARM64 = "arm64-v8a";
    private static final String ARCH_X86_64 = "x86_64";
    
    // 压缩包名 (tar.xz 格式)
    private static final String ARCHIVE_ARM64 = "zsh-5.9-android-aarch64.tar.xz";
    private static final String ARCHIVE_X86_64 = "zsh-5.9-android-x86_64.tar.xz";
    
    // 解压后的目录名
    private static final String ZSH_DIR = "zsh";
    
    // 缓存架构结果
    private static String cachedArch = null;
    
    /**
     * 安装进度回调接口
     */
    public interface InstallCallback {
        void onProgress(int current, int total, String fileName);
        void onSuccess(int totalFiles);
        void onError(String error);
    }
    
    /**
     * 获取设备架构 - 复用 ClangInstaller 的逻辑
     */
    public static String getDeviceArch(Context context) {
        if (cachedArch != null) {
            return cachedArch;
        }
        
        // 复用 ClangInstaller 的架构检测逻辑
        String clangArch = ClangInstaller.getDeviceArch(context);
        if (clangArch != null) {
            cachedArch = clangArch;
            Log.d(TAG, "Using arch from ClangInstaller: " + cachedArch);
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
        Log.d(TAG, "Detected arch: " + cachedArch);
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
     * 获取 zsh 安装目录
     */
    public static String getZshDir(Context context) {
        return new File(context.getFilesDir(), ZSH_DIR).getAbsolutePath();
    }
    /**
     * 获取 zsh 二进制路径
     */
    public static String getZshPath(Context context) {
        return new File(getZshDir(context), "bin/zsh").getAbsolutePath();
    }
    
    /**
     * 获取 zshrc 配置目录 (ZDOTDIR)
     * 指向 HOME 目录，让 zsh 加载 ~/.zshrc
     */
    public static String getZdotdir(Context context) {
        return context.getFilesDir().getAbsolutePath();
    }
    
    /**
     * 获取 terminfo 目录
     */
    public static String getTerminfoDir(Context context) {
        return new File(getZshDir(context), "share/terminfo").getAbsolutePath();
    }
    
    /**
     * 检查 zsh 是否已安装
     */
    public static boolean isInstalled(Context context) {
        File marker = new File(context.getFilesDir(), INSTALL_MARKER);
        File zshBin = new File(getZshPath(context));
        
        boolean installed = marker.exists() && zshBin.exists();
        
        
        return installed;
    }
    
    /**
     * 检查 assets 中是否存在对应架构的 zsh 包
     */
    public static boolean hasArchiveInAssets(Context context) {
        String arch = getDeviceArch(context);
        Log.d(TAG, "hasArchiveInAssets: arch=" + arch);
        if (arch == null) return false;
        
        String archiveName = getArchiveNameForArch(arch);
        Log.d(TAG, "hasArchiveInAssets: looking for " + archiveName);
        if (archiveName == null) return false;
        
        try {
            String[] assets = context.getAssets().list("");
            if (assets != null) {
                Log.d(TAG, "hasArchiveInAssets: found " + assets.length + " assets");
                for (String asset : assets) {
                    // 打印所有 zsh 相关文件
                    if (asset.startsWith("zsh-")) {
                        Log.d(TAG, "hasArchiveInAssets: found zsh file: " + asset);
                    }
                    if (asset.equals(archiveName)) {
                        Log.d(TAG, "hasArchiveInAssets: MATCH found!");
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
     * 统计 tar.xz 文件中的条目数量
     */
    

    /**
     * 安装 zsh (同步方法，无进度回调)
     * @return 解压的文件数量
     */
    public static int install(Context context) throws IOException {
        return install(context, null);
    }
    
    /**
     * 安装 zsh (同步方法，带进度回调)
     * @return 解压的文件数量
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

    public static int install(Context context, InstallCallback callback) throws IOException {
        return installInternal(context.getApplicationContext(), false, new InstallCancellation(),
                callback == null ? null : callback::onProgress);
    }

    private static int installInternal(Context app, boolean force, InstallCancellation cancellation,
                                       ToolchainArchives.Progress progress) throws IOException {
        String directory = ZSH_DIR;
        String archive = getArchiveNameForArch(getDeviceArch(app));
        if (directory == null || archive == null) throw new IOException("找不到对应架构的工具链安装包");
        return new ToolchainTransaction().install(app.getFilesDir(), directory, INSTALL_MARKER, force, cancellation,
                (staging, token) -> {
                    int count = ToolchainArchives.tarXz(app.getAssets().open(archive), staging, directory, token, progress);
                    // Existing user shell configuration is never overwritten by a toolchain replacement.
                    if (!new File(app.getFilesDir(), ".zshrc").exists()) writeZshrcConfig(new File(staging, ".zshrc"));
                    return count;
                }, root -> ToolchainArchives.requireFiles(root, directory, "bin/zsh"));
    }
    
    /**
     * 带进度对话框的安装
     */
    public static void installWithProgress(Context context, Runnable onSuccess) {
        Context app = context.getApplicationContext();
        InstallerDialogs.show(context, "安装Zsh Shell",
                (cancellation, progress) -> installInternal(app, false, cancellation, progress), onSuccess);
    }
    
    /**
     * 检查 .zshrc 配置版本是否需要更新
     */
    
    
    /**
     * 保存 .zshrc 配置版本
     */
    
    
    /**
     * 创建 ~/.zshrc 配置文件
     */
    private static void createZshrcConfig(Context context) {
        // A shell upgrade must not replace user aliases, settings, history or custom startup code.
        File configuration = new File(context.getFilesDir(), ".zshrc");
        if (configuration.exists()) return;
        try {
            ToolchainTransaction.withOwner(context.getFilesDir(), ZSH_DIR, () -> {
                if (!configuration.exists()) writeZshrcConfig(configuration);
                return null;
            });
        } catch (IOException failure) { throw new IllegalStateException("无法创建 Zsh 配置", failure); }
    }

    private static void writeZshrcConfig(File destination) throws IOException {
        StringBuilder config = new StringBuilder();
        config.append("# Akode Zsh Config\n");
        config.append("# Version: ").append(CURRENT_ZSHRC_VERSION).append("\n\n");

        config.append("# 环境变量\n");
        config.append("export TERMINFO=$HOME/zsh/share/terminfo\n");
        config.append("export TERM=xterm-256color\n");
        config.append("export ZSH_PLUGINS=$HOME/zsh/plugins\n");

        config.append("# 历史记录\n");
        config.append("HISTFILE=~/.zsh_history\n");
        config.append("HISTSIZE=10000\n");
        config.append("SAVEHIST=10000\n");
        config.append("setopt HIST_IGNORE_DUPS\n");
        config.append("setopt HIST_IGNORE_SPACE\n");
        config.append("setopt SHARE_HISTORY\n");
        config.append("setopt APPEND_HISTORY\n\n");
        
        config.append("# PS1 提示符 (路径只显示最后3级，超出显示...)\n");
        config.append("if [[ $UID -eq 0 ]]; then\n");
        config.append("  PS1='%F{red}root@akode%f in %F{blue}%(4~|.../%3~|%~)%f# '\n");
        config.append("else\n");
        config.append("  PS1='%F{green}user@akode%f in %F{blue}%(4~|.../%3~|%~)%f$ '\n");
        config.append("fi\n\n");
        
        config.append("# ls 颜色\n");
        config.append("export LS_COLORS='di=1;34:ln=1;35:so=1;32:pi=1;33:ex=1;31:bd=1;34;46:cd=1;34;43'\n");
        config.append("alias ls='ls --color=auto'\n");
        config.append("alias ll='ls -la'\n");
        config.append("alias la='ls -A'\n");
        config.append("alias grep='grep --color=auto'\n\n");
        
        config.append("# 常用别名\n");
        config.append("alias ..='cd ..'\n");
        config.append("alias ...='cd ../..'\n");
        config.append("alias cls='clear'\n\n");
        
        config.append("# 键绑定 - emacs 模式\n");
        config.append("bindkey -e\n\n");
        
        config.append("# 方向键\n");
        config.append("bindkey '\\e[A' up-line-or-history\n");
        config.append("bindkey '\\e[B' down-line-or-history\n");
        config.append("bindkey '\\e[C' forward-char\n");
        config.append("bindkey '\\e[D' backward-char\n\n");
        
        config.append("# 删除键\n");
        config.append("bindkey '^?' backward-delete-char\n");
        config.append("bindkey '^H' backward-delete-char\n");
        config.append("bindkey '\\e[3~' delete-char\n\n");
        
        config.append("# Home/End\n");
        config.append("bindkey '\\e[H' beginning-of-line\n");
        config.append("bindkey '\\e[F' end-of-line\n");
        config.append("bindkey '\\e[1~' beginning-of-line\n");
        config.append("bindkey '\\e[4~' end-of-line\n\n");
        
        config.append("# PageUp/PageDown\n");
        config.append("bindkey '\\e[5~' up-history\n");
        config.append("bindkey '\\e[6~' down-history\n\n");
        
        config.append("# Ctrl+左右箭头 跳词\n");
        config.append("bindkey '\\e[1;5C' forward-word\n");
        config.append("bindkey '\\e[1;5D' backward-word\n\n");
        
        config.append("# 终端设置 (隐藏 stty 输出)\n");
        config.append("stty erase '^?' 2>/dev/null\n");
        config.append("stty -ixon 2>/dev/null\n\n");
        
        config.append("# 简单版 is-at-least\n");
        config.append("is-at-least() {\n");
        config.append("  [[ $ZSH_VERSION == ${1}* ]] || [[ $ZSH_VERSION > $1 ]]\n");
        config.append("}\n\n");
        
        config.append("# 简单版 add-zsh-hook\n");
        config.append("add-zsh-hook() {\n");
        config.append("  local hook_type=$1 func=$2\n");
        config.append("  case $hook_type in\n");
        config.append("    precmd) precmd_functions+=($func) ;;\n");
        config.append("    preexec) preexec_functions+=($func) ;;\n");
        config.append("  esac\n");
        config.append("}\n\n");
        
        config.append("# 自动建议\n");
        config.append("[[ -f $ZSH_PLUGINS/zsh-autosuggestions/zsh-autosuggestions.zsh ]] && \\\n");
        config.append("  source $ZSH_PLUGINS/zsh-autosuggestions/zsh-autosuggestions.zsh\n\n");
        
        config.append("# 语法高亮 (patch 以避免 regex 模块检查)\n");
        config.append("if [[ -f $ZSH_PLUGINS/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ]]; then\n");
        config.append("  # 创建一个 stub 函数来替代 -regex-match\n");
        config.append("  function -regex-match() { return 1; }\n");
        config.append("  # 标记 regex 模块为已加载（欺骗插件）\n");
        config.append("  zmodload() {\n");
        config.append("    if [[ $1 == 'zsh/regex' ]]; then\n");
        config.append("      return 0\n");
        config.append("    else\n");
        config.append("      builtin zmodload \"$@\"\n");
        config.append("    fi\n");
        config.append("  }\n");
        config.append("  source $ZSH_PLUGINS/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh\n");
        config.append("  # 恢复原始 zmodload\n");
        config.append("  unfunction zmodload\n");
        config.append("fi\n");
        
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(destination.getParentFile().toPath(), ".zshrc-", ".tmp");
        try {
            java.nio.file.Files.write(temporary, config.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.move(temporary, destination.toPath());
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
    }

    /**
     * 检查文件是否是软链接
     */
    
    
    /**
     * 静默安装 (如果未安装则安装)
     */
    public static void ensureInstalled(Context context) {
        Log.d(TAG, "ensureInstalled called");
        Log.d(TAG, "isInstalled: " + isInstalled(context));
        Log.d(TAG, "hasArchiveInAssets: " + hasArchiveInAssets(context));
        
        if (!isInstalled(context) && hasArchiveInAssets(context)) {
            try {
                Log.d(TAG, "Starting zsh installation...");
                int count = install(context);
                Log.d(TAG, "Zsh installation completed, " + count + " files extracted");
            } catch (Exception e) {
                Log.e(TAG, "Failed to install zsh", e);
            }
        } else if (isInstalled(context)) {
            Log.d(TAG, "Zsh already installed at: " + getZshPath(context));
            // 检查 .zshrc 配置文件是否需要更新
            ensureZshrcConfig(context);
        } else {
            Log.d(TAG, "No zsh archive found in assets for arch: " + getDeviceArch(context));
        }
    }
    
    /**
     * 确保 ~/.zshrc 配置文件存在
     */
    public static void ensureZshrcConfig(Context context) {
        createZshrcConfig(context);
    }
    
    /**
     * 确保 ~/.zshrc 软链接存在（已废弃，保留用于兼容）
     */
    @Deprecated
    public static void ensureZshrcSymlink(Context context) {
        ensureZshrcConfig(context);
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
     * 卸载 zsh
     */
    public static void uninstall(Context context) {
        Context app = context.getApplicationContext();
        String directory = ZSH_DIR;
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
    
}
