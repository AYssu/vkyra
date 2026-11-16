package com.vkyra.code.installer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Only extracts the selected toolchain into a fresh staging directory. */
public final class ToolchainArchives {
    @FunctionalInterface public interface Progress { void file(int current, int total, String name); }
    private static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200_000;

    private ToolchainArchives() { }

    public static int tarXz(InputStream asset, File staging, String target, InstallCancellation cancellation,
                            Progress progress) throws IOException {
        // Register the outer asset: closing it also unblocks a decoder waiting for more input.
        try (InputStream owned = asset) {
            cancellation.attach(owned);
            try (XZInputStream xz = new XZInputStream(new BufferedInputStream(owned));
                 TarArchiveInputStream archive = new TarArchiveInputStream(xz)) {
                Path root = staging.toPath().resolve(target);
                Files.createDirectories(root);
                List<Link> links = new ArrayList<>();
                Budget budget = new Budget();
                TarArchiveEntry entry;
                while (true) {
                    cancellation.check();
                    entry = archive.getNextEntry();
                    if (entry == null) break;
                    Path destination = destination(staging.toPath(), root, entry.getName());
                    budget.entry(entry.getSize());
                    if (entry.isDirectory()) Files.createDirectories(destination);
                    else if (entry.isSymbolicLink() || entry.isLink()) {
                        Path linkTarget = entry.isSymbolicLink()
                                ? destination.getParent().resolve(entry.getLinkName()).normalize()
                                : staging.toPath().resolve(entry.getLinkName()).normalize();
                        if (java.nio.file.Paths.get(entry.getLinkName()).isAbsolute() || !linkTarget.startsWith(root))
                            throw new IOException("安装包包含目录外的链接");
                        links.add(new Link(destination, linkTarget, entry.isSymbolicLink()));
                    } else if (entry.isFile()) copy(archive, destination, budget, cancellation, (entry.getMode() & 0111) != 0);
                    else throw new IOException("安装包包含不支持的文件类型");
                    report(progress, budget.count, entry.getName());
                }
                for (Link link : links) {
                    cancellation.check();
                    Files.createDirectories(link.destination.getParent());
                    if (link.symbolic) Files.createSymbolicLink(link.destination, link.destination.getParent().relativize(link.target));
                    else Files.createLink(link.destination, link.target);
                }
                cancellation.check();
                return budget.count;
            } finally { cancellation.detach(owned); }
        }
    }

    public static int zip(InputStream asset, File staging, String target, InstallCancellation cancellation,
                          Progress progress) throws IOException {
        try (InputStream owned = asset) {
            cancellation.attach(owned);
            try (ZipInputStream archive = new ZipInputStream(new BufferedInputStream(owned))) {
                Path root = staging.toPath().resolve(target);
                Files.createDirectories(root);
                Budget budget = new Budget();
                ZipEntry entry;
                while (true) {
                    cancellation.check();
                    entry = archive.getNextEntry();
                    if (entry == null) break;
                    Path destination = destination(staging.toPath(), root, entry.getName());
                    budget.entry(entry.getSize());
                    if (entry.isDirectory()) Files.createDirectories(destination);
                    else copy(archive, destination, budget, cancellation, true);
                    archive.closeEntry();
                    report(progress, budget.count, entry.getName());
                }
                cancellation.check();
                return budget.count;
            } finally { cancellation.detach(owned); }
        }
    }

    private static Path destination(Path staging, Path root, String name) throws IOException {
        Path relative = java.nio.file.Paths.get(name);
        Path path = staging.resolve(relative).normalize();
        if (relative.isAbsolute() || !path.startsWith(root)) throw new IOException("安装包文件超出工具链目录");
        return path;
    }

    private static void copy(InputStream source, Path destination, Budget budget,
                             InstallCancellation cancellation, boolean executable) throws IOException {
        Files.createDirectories(destination.getParent());
        long length = 0;
        try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = source.read(buffer)) != -1) {
                cancellation.check();
                length += read;
                budget.bytes += read;
                if (length > MAX_FILE_BYTES || budget.bytes > MAX_BYTES) throw new IOException("工具链安装包解压内容过大");
                output.write(buffer, 0, read);
            }
        }
        if (executable && !destination.toFile().setExecutable(true, true)) throw new IOException("无法设置工具执行权限");
    }

    public static void requireFiles(File root, String target, String... relativeFiles) throws IOException {
        Path owned = new File(root, target).toPath();
        for (String name : relativeFiles) {
            Path file = owned.resolve(name);
            if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(owned.toRealPath()))
                throw new IOException("安装包缺少必要工具：" + name);
            if (!Files.isExecutable(file)) throw new IOException("工具不可执行：" + name);
        }
    }

    private static void report(Progress progress, int count, String name) {
        if (progress != null) progress.file(count, -1, name.length() > 60 ? "…" + name.substring(name.length() - 59) : name);
    }

    private static final class Link {
        final Path destination, target;
        final boolean symbolic;
        Link(Path destination, Path target, boolean symbolic) {
            this.destination = destination; this.target = target; this.symbolic = symbolic;
        }
    }

    private static final class Budget {
        int count;
        long bytes;
        void entry(long size) throws IOException {
            if (++count > MAX_ENTRIES || size > MAX_FILE_BYTES) throw new IOException("工具链安装包内容过大");
        }
    }
}
