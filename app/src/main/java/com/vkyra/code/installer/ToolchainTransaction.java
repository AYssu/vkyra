package com.vkyra.code.installer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Same-filesystem staged installation, with a recovery journal and one owner per toolchain. */
public final class ToolchainTransaction {
    @FunctionalInterface public interface Preparation { int prepare(File staging, InstallCancellation cancellation) throws IOException; }
    @FunctionalInterface public interface Validator { void validate(File root) throws IOException; }
    @FunctionalInterface public interface Faults { void at(String boundary) throws IOException; }
    @FunctionalInterface public interface Action<T> { T run() throws IOException; }
    private static final ConcurrentHashMap<String, ReentrantLock> OWNERS = new ConcurrentHashMap<>();
    private final Faults faults;

    public ToolchainTransaction() { this(boundary -> { }); }
    public ToolchainTransaction(Faults faults) { this.faults = faults; }

    public int install(File root, String target, String marker, boolean force, InstallCancellation cancellation,
                       Preparation preparation, Validator validator) throws IOException {
        return withOwner(root, target, () -> installOwned(root.getCanonicalFile(), target, marker, force,
                cancellation, preparation, validator));
    }

    public static <T> T withOwner(File root, String target, Action<T> action) throws IOException {
        checkName(target);
        File canonical = root.getCanonicalFile();
        Path work = new File(canonical, ".toolchain-transactions/" + target).toPath();
        ReentrantLock lock = OWNERS.computeIfAbsent(work.toString(), ignored -> new ReentrantLock());
        if (!lock.tryLock()) throw new IOException("此工具链已有安装任务，请稍后重试");
        try {
            Files.createDirectories(work);
            try (FileChannel channel = FileChannel.open(work.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                try (FileLock fileLock = channel.tryLock()) {
                    if (fileLock == null) throw new IOException("此工具链已有安装任务，请稍后重试");
                    return action.run();
                } catch (OverlappingFileLockException busy) {
                    throw new IOException("此工具链已有安装任务，请稍后重试", busy);
                }
            }
        } finally { lock.unlock(); }
    }

    private int installOwned(File rootFile, String target, String marker, boolean force,
                             InstallCancellation cancellation, Preparation preparation, Validator validator) throws IOException {
        checkName(marker);
        Path root = rootFile.toPath();
        Path work = root.resolve(".toolchain-transactions").resolve(target);
        Path staged = work.resolve("staged"), backup = work.resolve("backup"), journal = work.resolve("state.properties");
        recover(root, staged, backup, journal);
        cancellation.check();
        if (!force && Files.isRegularFile(root.resolve(marker))) {
            try { validator.validate(rootFile); return 0; }
            catch (IOException incomplete) { /* An invalid prior installation is replaced transactionally. */ }
        }
        deleteTree(staged);
        deleteTree(backup);
        Files.createDirectories(staged);
        Properties state = new Properties();
        state.setProperty("phase", "staging");
        writeJournal(journal, state);
        boolean activating = false;
        try {
            int count = preparation.prepare(staged.toFile(), cancellation);
            cancellation.check();
            validator.validate(staged.toFile());
            Files.write(staged.resolve(marker), new byte[0], StandardOpenOption.CREATE_NEW);
            List<String> names = new ArrayList<>();
            try (var entries = Files.list(staged)) {
                entries.forEach(path -> names.add(path.getFileName().toString()));
            }
            // The target and completion marker are always in the same recoverable activation set.
            names.sort(String::compareTo);
            if (!names.contains(target)) throw new IOException("安装包缺少工具链目录");
            state.setProperty("count", Integer.toString(names.size()));
            for (int i = 0; i < names.size(); i++) {
                checkName(names.get(i));
                state.setProperty("name." + i, names.get(i));
                state.setProperty("old." + i, Boolean.toString(exists(root.resolve(names.get(i)))));
            }
            Files.createDirectories(backup);
            state.setProperty("phase", "committing");
            writeJournal(journal, state);
            activating = true;
            for (String name : names) {
                cancellation.check();
                faults.at("before-backup:" + name);
                if (exists(root.resolve(name))) move(root.resolve(name), backup.resolve(name));
                faults.at("after-backup:" + name);
                cancellation.check();
                move(staged.resolve(name), root.resolve(name));
                faults.at("after-activate:" + name);
            }
            cancellation.check();
            faults.at("before-commit");
            cancellation.check();
            state.setProperty("phase", "committed");
            writeJournal(journal, state);
            // After this durable decision the new toolchain is authoritative, even if cleanup fails.
            try { clean(staged, backup, journal); } catch (IOException deferredCleanup) { }
            return count;
        } catch (IOException | RuntimeException failure) {
            boolean interrupted = Thread.interrupted();
            try {
                if (activating) rollback(root, backup, state);
                clean(staged, backup, journal);
            } catch (IOException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw failure;
        }
    }

    /** Next install restores a previously interrupted activation before consulting its marker. */
    private static void recover(Path root, Path staged, Path backup, Path journal) throws IOException {
        if (!Files.exists(journal)) {
            if (exists(backup)) throw new IOException("发现无恢复记录的旧工具链备份，已保留，请检查安装状态");
            deleteTree(staged);
            return;
        }
        Properties state = new Properties();
        try (InputStream input = Files.newInputStream(journal)) { state.load(input); }
        String phase = state.getProperty("phase");
        if ("committing".equals(phase)) rollback(root, backup, state);
        else if (!"staging".equals(phase) && !"committed".equals(phase)) throw new IOException("无效的安装恢复记录");
        clean(staged, backup, journal);
    }

    private static void rollback(Path root, Path backup, Properties state) throws IOException {
        final int count;
        try { count = Integer.parseInt(state.getProperty("count")); }
        catch (RuntimeException invalid) { throw new IOException("无效的安装恢复记录", invalid); }
        if (count < 1 || count > 32) throw new IOException("无效的安装恢复记录");
        for (int i = count - 1; i >= 0; i--) {
            String name = state.getProperty("name." + i);
            checkName(name);
            Path previous = backup.resolve(name), live = root.resolve(name);
            if (exists(previous)) {
                deleteTree(live);
                move(previous, live);
            } else if (!Boolean.parseBoolean(state.getProperty("old." + i))) deleteTree(live);
        }
    }

    private static void clean(Path staged, Path backup, Path journal) throws IOException {
        deleteTree(staged);
        deleteTree(backup);
        Files.deleteIfExists(journal);
    }

    private static void writeJournal(Path journal, Properties state) throws IOException {
        Path temporary = journal.resolveSibling("state.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) { state.store(output, "Toolchain activation recovery"); }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
        move(temporary, journal);
    }

    private static void move(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            // Same-parent filesystem renames remain recoverable by the write-ahead journal.
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }

    private static void checkName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_.+-]+") || name.equals(".") || name.equals(".."))
            throw new IOException("无效的工具链路径");
    }

    /** Does not follow symlinks, including a dangling link or a link to a previous toolchain. */
    public static void deleteTree(Path path) throws IOException {
        if (!exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
