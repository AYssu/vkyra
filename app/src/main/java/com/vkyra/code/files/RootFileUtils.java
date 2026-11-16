package com.vkyra.code.files;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.io.SuFile;
import com.topjohnwu.superuser.io.SuFileInputStream;
import com.topjohnwu.superuser.io.SuFileOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Root 文件操作工具类
 * 使用 libsu 库实现 root 文件访问
 */
public class RootFileUtils {
    
    private static volatile Boolean hasRoot = null;
    
    /**
     * 检查是否有 root 权限
     */
    public static boolean isRootAvailable() {
        if (hasRoot == null) {
            hasRoot = Shell.getShell().isRoot();
        }
        return hasRoot;
    }
    
    /**
     * 请求 root 权限
     */
    public static void requestRoot(RootCallback callback) {
        Shell.getShell(shell -> {
            hasRoot = shell.isRoot();
            if (callback != null) {
                callback.onResult(hasRoot);
            }
        });
    }
    
    /**
     * 获取文件对象（自动判断是否需要 root）
     */
    public static File getFile(String path) {
        File normalFile = new File(path);
        // 如果普通方式可以访问，就用普通方式
        if (normalFile.exists() && normalFile.canRead()) {
            return normalFile;
        }
        // 否则尝试用 root 方式
        if (isRootAvailable()) {
            return new SuFile(path);
        }
        return normalFile;
    }
    
    /**
     * 获取文件对象（强制使用 root）
     */
    public static SuFile getSuFile(String path) {
        return new SuFile(path);
    }
    
    /**
     * 列出目录内容（自动判断是否需要 root）
     */
    public static File[] listFiles(String path) {
        File dir = new File(path);
        File[] files = dir.listFiles();
        
        // 如果普通方式可以列出，就返回
        if (files != null) {
            return files;
        }
        
        // 否则尝试用 root 方式
        if (isRootAvailable()) {
            SuFile suDir = new SuFile(path);
            return suDir.listFiles();
        }
        
        return null;
    }
    
    /**
     * 列出目录内容（强制使用 root）
     */
    public static File[] listFilesAsRoot(String path) {
        if (!isRootAvailable()) {
            return null;
        }
        SuFile suDir = new SuFile(path);
        return suDir.listFiles();
    }
    
    /**
     * 检查文件是否存在
     */
    public static boolean exists(String path) {
        File file = new File(path);
        if (file.exists()) {
            return true;
        }
        if (isRootAvailable()) {
            return new SuFile(path).exists();
        }
        return false;
    }
    
    /**
     * 检查是否是目录
     */
    public static boolean isDirectory(String path) {
        File file = new File(path);
        if (file.exists()) {
            return file.isDirectory();
        }
        if (isRootAvailable()) {
            return new SuFile(path).isDirectory();
        }
        return false;
    }
    
    /**
     * 检查是否可读
     */
    public static boolean canRead(String path) {
        File file = new File(path);
        if (file.canRead()) {
            return true;
        }
        if (isRootAvailable()) {
            return new SuFile(path).canRead();
        }
        return false;
    }
    
    public interface RootCallback {
        void onResult(boolean hasRoot);
    }
    
    /**
     * 读取文件内容（自动判断是否需要 root）
     */
    public static String readFile(String path) throws IOException {
        return readDocument(path).getText();
    }

    /** Return exact text plus encoding metadata, with no readLine newline normalization. */
    public static TextFileCodec.Document readDocument(String path) throws IOException {
        File file = new File(path);
        if (file.exists() && file.canRead()) {
            return AtomicTextFileStore.read(file);
        }
        if (!isRootAvailable()) throw new IOException("无法读取文件: 没有权限");
        try (InputStream input = SuFileInputStream.open(path);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
            return TextFileCodec.decode(buffer.toByteArray());
        }
    }
    
    /**
     * 写入文件内容（自动判断是否需要 root）
     */
    public static void writeFile(String path, String content) throws IOException {
        File file = new File(path);
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && parent.canWrite() && (!file.exists() || (file.canRead() && file.canWrite()))) {
            AtomicTextFileStore.write(file, content);
            return;
        }
        if (!isRootAvailable()) throw new IOException("无法写入文件: 没有权限");
        writeAsRoot(path, content);
    }

    private static void writeAsRoot(String path, String content) throws IOException {
        SuFile target = new SuFile(path).getCanonicalFile();
        if (target.exists() && !target.isFile()) throw new IOException("目标不是普通文件");
        SuFile parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("目标目录不存在");
        byte[] bytes = target.exists() ? readDocument(target.getPath()).encode(content)
                : TextFileCodec.encodeNew(content);
        SuFile staging = new SuFile(parent, ".vkyra-save-" + UUID.randomUUID() + ".tmp");
        if (!staging.createNewFile()) throw new IOException("无法创建临时文件，原文件未修改");
        try {
            String temporary = quoteShellPath(staging.getPath());
            if (!Shell.cmd("chmod 600 " + temporary).exec().isSuccess()) {
                throw new IOException("无法保护临时文件，原文件未修改");
            }
            try (OutputStream output = SuFileOutputStream.open(staging)) {
                output.write(bytes);
                output.flush();
            }
            // stat/chown/chmod are supplied by Android toybox. Keep owner/mode on root-owned files.
            // libsu's remote OutputStream has no local fd. sync before the same-directory rename.
            if (!Shell.cmd(RootFileCommands.commit(staging.getPath(), target.getPath(), target.exists()))
                    .exec().isSuccess()) {
                throw new IOException("原子保存失败，原文件未修改");
            }
        } finally {
            if (staging.exists()) staging.delete();
        }
    }

    static String quoteShellPath(String path) {
        return RootFileCommands.quote(path);
    }
}
