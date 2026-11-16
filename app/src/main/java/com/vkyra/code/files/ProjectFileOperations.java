package com.vkyra.code.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/** Filesystem-only explorer operations. Callers perform these operations off the UI thread. */
public final class ProjectFileOperations {
    private ProjectFileOperations() {}

    public static boolean isBasename(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")) return false;
        for (int i=0;i<name.length();i++) {
            char character=name.charAt(i);
            if (character=='/' || character=='\\' || Character.isISOControl(character)) return false;
        }
        return true;
    }

    public static boolean contains(File root, File candidate) {
        if (root==null || candidate==null) return false;
        return normalized(candidate).startsWith(normalized(root));
    }

    /** Include both lexical aliases and physical aliases when guarding an opened buffer. */
    public static void requireUnprotected(File selected, List<File> protectedPaths) throws IOException {
        Path selectedPath=normalized(selected);
        boolean symbolicLink=Files.isSymbolicLink(selectedPath);
        Path physicalSelected=symbolicLink ? selectedPath : selectedPath.toRealPath();
        for (File protectedFile:protectedPaths) {
            if (contains(selected,protectedFile)) throw new IOException("请先关闭相关标签并切出此目录，再执行操作");
            // Removing a symlink itself does not modify the target accessed through another alias.
            if (!symbolicLink) {
                Path physicalProtected=protectedFile.getCanonicalFile().toPath();
                if (physicalProtected.startsWith(physicalSelected))
                    throw new IOException("请先关闭相关标签并切出此目录，再执行操作");
            }
        }
    }

    /** Rename a single entry in its existing parent; never request replacement of a destination. */
    public static File rename(File source, String basename) throws IOException {
        if (!isBasename(basename)) throw new IOException("名称必须是单个文件名，不能包含路径、控制字符或 . / ..");
        Path original=normalized(source);
        Path parent=original.getParent();
        if (parent==null) throw new IOException("不能重命名文件系统根目录");
        Path destination=parent.resolve(basename);
        if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.FileAlreadyExistsException(basename);
        Files.move(original,destination);
        return destination.toFile();
    }

    /** walkFileTree defaults to NOFOLLOW_LINKS: dangling and directory links are removed as entries. */
    public static void deleteRecursively(File selected) throws IOException {
        Path path=normalized(selected);
        if (path.getParent()==null) throw new IOException("不能删除文件系统根目录");
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException failure) throws IOException {
                throw failure;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure) throws IOException {
                if (failure!=null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path normalized(File file) {
        if (file==null) throw new IllegalArgumentException("Missing file");
        return file.toPath().toAbsolutePath().normalize();
    }
}
