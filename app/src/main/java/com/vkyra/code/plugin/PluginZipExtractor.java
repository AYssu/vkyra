package com.vkyra.code.plugin;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 插件 ZIP 解压工具。
 * 使用项目已有的 zip4j，避免 ZipInputStream 顺序扫描和重复读取 Content URI。
 */
public final class PluginZipExtractor {

    // Toolchain plugins can be large: cap actual bytes, not just ZIP-declared sizes.
    static final long MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 16L * 1024 * 1024 * 1024;
    static final long MAX_CONFIG_BYTES = 1024 * 1024;
    static final int MAX_ENTRIES = 250_000;
    private static final int COPY_BUFFER_SIZE = 256 * 1024;
    private static final int PROGRESS_UPDATE_INTERVAL_MS = 200;

    public interface ProgressCallback {
        void onStage(String stage);

        void onProgress(int percentDone, String detail);
    }

    private PluginZipExtractor() {
    }

    /**
     * 将用户选择的 ZIP 复制到应用缓存目录，后续可随机访问读取 .config 和解压。
     */
    public static File copyUriToCache(Context context, Uri zipUri, ProgressCallback callback) throws Exception {
        File cacheDir = new File(context.getCacheDir(), "plugin_install");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("无法创建插件缓存目录");
        }

        purgeOldCacheFiles(cacheDir);

        File tempZip = File.createTempFile("plugin_", ".zip", cacheDir);
        if (callback != null) {
            callback.onStage("复制插件包到本地...");
            callback.onProgress(0, tempZip.getName());
        }

        long totalBytes = queryUriSize(context, zipUri);
        if (totalBytes > MAX_ARCHIVE_BYTES) {
            tempZip.delete();
            throw new IOException("插件压缩包超过 4 GiB 上限");
        }
        long copiedBytes = 0;
        long lastProgressAt = 0;

        try (InputStream in = new BufferedInputStream(context.getContentResolver().openInputStream(zipUri));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tempZip), COPY_BUFFER_SIZE)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
                copiedBytes += len;
                checkInterrupted();
                if (copiedBytes > MAX_ARCHIVE_BYTES) throw new IOException("插件压缩包超过 4 GiB 上限");
                if (callback != null && shouldPublishCopyProgress(lastProgressAt, copiedBytes, totalBytes)) {
                    lastProgressAt = System.currentTimeMillis();
                    if (totalBytes > 0) {
                        int percent = (int) Math.min(99, (copiedBytes * 100L) / totalBytes);
                        callback.onProgress(percent, "复制中 " + percent + "%  "
                            + formatSize(copiedBytes) + " / " + formatSize(totalBytes));
                    } else {
                        callback.onProgress(-1, "复制中 " + formatSize(copiedBytes) + " ...");
                    }
                }
            }
        } catch (Exception failure) {
            deleteQuietly(tempZip);
            throw failure;
        }

        if (callback != null) {
            callback.onProgress(100, "复制完成");
        }
        return tempZip;
    }

    private static void publishByteExtractProgress(
        ProgressCallback callback,
        long extractedBytes,
        long totalBytes,
        int fileIndex,
        int fileCount,
        String fileName) {
        int percent = totalBytes > 0
            ? (int) Math.min(100, (extractedBytes * 100L) / totalBytes)
            : 0;
        String detail = "解压中 " + percent + "%  "
            + formatSize(extractedBytes) + " / " + formatSize(totalBytes)
            + "\n(" + fileIndex + "/" + fileCount + ") " + shortenFileName(fileName);
        callback.onProgress(percent, detail);
    }

    private static String shortenFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "处理中...";
        }
        if (fileName.length() <= 48) {
            return fileName;
        }
        return "..." + fileName.substring(fileName.length() - 45);
    }

    private static boolean shouldPublishCopyProgress(long lastProgressAt, long copiedBytes, long totalBytes) {
        long now = System.currentTimeMillis();
        if (now - lastProgressAt < PROGRESS_UPDATE_INTERVAL_MS) {
            return false;
        }
        if (totalBytes <= 0) {
            return copiedBytes > 0;
        }
        return copiedBytes < totalBytes;
    }

    private static boolean shouldPublishByteProgress(long lastProgressAt, long extractedBytes, long totalBytes) {
        if (totalBytes > 0 && extractedBytes >= totalBytes) {
            return true;
        }
        long now = System.currentTimeMillis();
        return now - lastProgressAt >= PROGRESS_UPDATE_INTERVAL_MS;
    }

    private static long calculateTotalUncompressedBytes(List<FileHeader> headers) {
        long total = 0;
        for (FileHeader header : headers) {
            if (header.isDirectory()) {
                continue;
            }
            long size = header.getUncompressedSize();
            if (size <= 0) {
                size = header.getCompressedSize();
            }
            if (size > 0) {
                total += size;
            }
        }
        return total > 0 ? total : 1;
    }

    private static void extractEntry(
        ZipFile zip,
        FileHeader header,
        File targetDir,
        String targetCanonicalPath,
        long[] extractedBytes,
        long totalBytes,
        int fileIndex,
        int fileCount,
        ProgressCallback callback,
        long[] lastProgressAt) throws Exception {
        String entryName = header.getFileName();
        if (entryName == null || entryName.isEmpty()) {
            return;
        }

        checkInterrupted();
        if (entryName.indexOf('\\') >= 0 || entryName.startsWith("/") || entryName.matches("^[A-Za-z]:.*")) {
            throw new IOException("不支持的 ZIP 路径: " + entryName);
        }
        File outFile = new File(targetDir, entryName);
        String outCanonicalPath = outFile.getCanonicalPath();
        if (!outCanonicalPath.startsWith(targetCanonicalPath + File.separator)
            && !outCanonicalPath.equals(targetCanonicalPath)) {
            throw new SecurityException("ZIP entry outside target dir: " + entryName);
        }

        if (header.isDirectory() || entryName.endsWith("/")) {
            Files.createDirectories(outFile.toPath());
            return;
        }

        File parent = outFile.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        try (InputStream in = new BufferedInputStream(zip.getInputStream(header), COPY_BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), COPY_BUFFER_SIZE)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int len;
            while ((len = in.read(buffer)) != -1) {
                checkInterrupted();
                if (len > MAX_EXPANDED_BYTES - extractedBytes[0]) throw new IOException("插件解压内容超过 16 GiB 上限");
                out.write(buffer, 0, len);
                extractedBytes[0] += len;
                if (callback != null
                    && shouldPublishByteProgress(lastProgressAt[0], extractedBytes[0], totalBytes)) {
                    lastProgressAt[0] = System.currentTimeMillis();
                    publishByteExtractProgress(
                        callback, extractedBytes[0], totalBytes, fileIndex, fileCount, entryName);
                }
            }
        }
    }

    /**
     * 从本地 ZIP 中读取 .config，无需解压全部文件。
     * 大型插件包（如 NDK）打开索引可能耗时，需配合 callback 提示用户。
     */
    public static String readConfig(File zipFile, ProgressCallback callback) throws Exception {
        if (callback != null) {
            callback.onStage("正在读取插件配置...");
            callback.onProgress(-1, "分析 " + formatSize(zipFile.length()) + " 压缩包，建立文件索引...");
        }

        try (ZipFile zip = new ZipFile(zipFile)) {
            List<FileHeader> headers = zip.getFileHeaders();
            if (callback != null) {
                callback.onProgress(-1, "索引完成（" + headers.size() + " 个条目），查找 .config ...");
            }

            validateHeaders(headers);
            FileHeader header = findConfigHeader(headers);
            if (header == null) {
                return null;
            }

            if (callback != null) {
                callback.onProgress(-1, "读取配置文件...");
            }

            if (header.getUncompressedSize() > MAX_CONFIG_BYTES) throw new IOException("插件配置超过 1 MiB 上限");
            ByteArrayOutputStream content = new ByteArrayOutputStream(
                (int) Math.min(Math.max(header.getUncompressedSize(), 256), 64 * 1024));
            try (InputStream in = zip.getInputStream(header)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) {
                    checkInterrupted();
                    if (len > MAX_CONFIG_BYTES - content.size()) throw new IOException("插件配置超过 1 MiB 上限");
                    content.write(buffer, 0, len);
                }
            }
            return content.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static String readConfig(File zipFile) throws Exception {
        return readConfig(zipFile, null);
    }

    /**
     * 流式解压本地 ZIP，按已解压字节数汇报进度（大文件也会持续更新）。
     */
    public static void extract(File zipFile, File targetDir, ProgressCallback callback) throws Exception {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IllegalStateException("无法创建插件目录");
        }

        if (callback != null) {
            callback.onStage("解压插件文件...");
            callback.onProgress(-1, "分析 " + formatSize(zipFile.length()) + " 压缩包，建立文件索引...");
        }

        try (ZipFile zip = new ZipFile(zipFile)) {
            List<FileHeader> headers = zip.getFileHeaders();
            validateHeaders(headers);
            int fileCount = headers.size();
            if (fileCount == 0) {
                throw new IllegalStateException("压缩包为空");
            }

            long totalBytes = calculateTotalUncompressedBytes(headers);
            long usableSpace = targetDir.getUsableSpace();
            if (usableSpace > 0 && totalBytes > usableSpace) throw new IOException("可用空间不足，无法解压插件");
            String targetCanonicalPath = targetDir.getCanonicalPath();
            long[] extractedBytes = {0};
            long[] lastProgressAt = {0};

            if (callback != null) {
                callback.onProgress(0, "开始解压，共 " + fileCount + " 个条目，"
                    + formatSize(totalBytes) + " 待写入");
            }

            int fileIndex = 0;
            for (FileHeader header : headers) {
                fileIndex++;
                extractEntry(
                    zip, header, targetDir, targetCanonicalPath,
                    extractedBytes, totalBytes, fileIndex, fileCount,
                    callback, lastProgressAt);
            }

            if (callback != null) {
                publishByteExtractProgress(
                    callback, extractedBytes[0], totalBytes, fileCount, fileCount, "完成");
            }
        }

        applyPluginPermissions(targetDir);

        if (callback != null) {
            callback.onProgress(100, "解压完成");
        }
    }

    public static void deleteQuietly(File file) {
        try { PluginInstallTransaction.deleteTree(file); } catch (IOException ignored) { }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("插件安装已取消");
    }

    private static void validateHeaders(List<FileHeader> headers) throws IOException {
        if (headers.size() > MAX_ENTRIES) throw new IOException("插件文件数量超过上限");
        long total = 0;
        Set<String> names = new HashSet<>();
        for (FileHeader header : headers) {
            String name = header.getFileName();
            if (name == null || !names.add(name)) throw new IOException("插件包含重复或无效路径");
            if (header.isEncrypted()) throw new IOException("不支持加密的插件包");
            if (!header.isDirectory()) {
                long size = header.getUncompressedSize();
                if (size < 0 || size > MAX_EXPANDED_BYTES - total) throw new IOException("插件解压内容超过 16 GiB 上限");
                total += size;
            }
        }
    }

    private static FileHeader findConfigHeader(List<FileHeader> headers) {
        if (headers == null) {
            return null;
        }
        FileHeader fallback = null;
        for (FileHeader header : headers) {
            if (header.isDirectory()) {
                continue;
            }
            String name = header.getFileName();
            if (".config".equals(name)) {
                return header;
            }
            if (name.endsWith("/.config")) {
                fallback = header;
            }
        }
        return fallback;
    }

    private static void applyPluginPermissions(File rootDir) {
        if (rootDir == null || !rootDir.exists()) {
            return;
        }
        File[] children = rootDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.exists()) {
                continue;
            }
            String name = child.getName();
            if (child.isDirectory()) {
                if ("bin".equals(name) || name.contains("lib") || name.endsWith(".d")) {
                    child.setExecutable(true, false);
                }
                applyPluginPermissions(child);
            } else if (name.endsWith(".so") || !name.contains(".")) {
                child.setExecutable(true, false);
            }
        }
    }

    private static void purgeOldCacheFiles(File cacheDir) {
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("plugin_")
                && now - file.lastModified() > 24 * 60 * 60 * 1000L) {
                file.delete();
            }
        }
    }

    private static long queryUriSize(Context context, Uri zipUri) {
        try (Cursor cursor = context.getContentResolver().query(
            zipUri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
