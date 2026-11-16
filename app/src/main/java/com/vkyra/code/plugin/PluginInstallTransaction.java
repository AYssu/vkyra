package com.vkyra.code.plugin;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owns plugin directory changes. Scripts run at the final path; the previous version remains
 * in the journal until all phases succeed. An interrupted install is recovered on next launch.
 * Script side effects outside the plugin directory cannot be rolled back. */
public final class PluginInstallTransaction implements AutoCloseable {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Set<String> ACTIVE = new HashSet<>();
    private final File root;
    private final File journal;
    private final File target;
    private final File staging;
    private final File backup;
    private final String key;
    private boolean finished;

    public static boolean isValidPluginId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public static File resolvePluginDirectory(File root, String id) throws IOException {
        if (!isValidPluginId(id)) throw new IOException("插件标识仅允许字母、数字、点、横线和下划线，且必须以字母或数字开头");
        File canonicalRoot = root.getCanonicalFile();
        File result = new File(canonicalRoot, id);
        if (Files.isSymbolicLink(result.toPath()) || !canonicalRoot.equals(result.getCanonicalFile().getParentFile())) {
            throw new IOException("插件目录超出允许范围");
        }
        return result;
    }

    public static synchronized PluginInstallTransaction begin(File root, String id) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        Files.createDirectories(canonicalRoot.toPath());
        recoverInterruptedInstalls(canonicalRoot);
        File target = resolvePluginDirectory(canonicalRoot, id);
        String key = target.getAbsolutePath();
        if (ACTIVE.contains(key)) throw new IOException("该插件正在安装");
        File journals = new File(canonicalRoot, ".install-transactions");
        if (Files.isSymbolicLink(journals.toPath())) throw new IOException("无效的安装日志目录");
        Files.createDirectories(journals.toPath());
        File journal = new File(journals, UUID.randomUUID().toString());
        Files.createDirectory(journal.toPath());
        Files.write(new File(journal, "id").toPath(), id.getBytes(StandardCharsets.UTF_8));
        PluginInstallTransaction transaction = new PluginInstallTransaction(canonicalRoot, journal, target, key);
        Files.createDirectory(transaction.staging.toPath());
        ACTIVE.add(key);
        return transaction;
    }

    private PluginInstallTransaction(File root, File journal, File target, String key) {
        this.root = root;
        this.journal = journal;
        this.target = target;
        this.key = key;
        staging = new File(journal, "staging");
        backup = new File(journal, "backup");
    }

    public File getStagingDirectory() { return staging; }
    public File getTargetDirectory() { return target; }

    public synchronized void activate() throws IOException {
        requireOpen();
        resolvePluginDirectory(root, target.getName());
        Files.createFile(new File(journal, "activating").toPath());
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) move(target, backup);
        try {
            move(staging, target);
        } catch (IOException failure) {
            rollback();
            throw failure;
        }
    }

    public synchronized void commit() throws IOException {
        requireOpen();
        if (!new File(journal, "activating").isFile() || staging.exists()) throw new IOException("插件尚未激活");
        Files.createFile(new File(journal, "committed").toPath());
        finished = true;
        release();
        // Committed journals can safely be cleaned by recovery if deletion is interrupted.
        try { deleteTree(journal); } catch (IOException ignored) { }
    }

    /** Failed process cleanup must remain blocked even after an app restart. */
    public synchronized void blockRecovery() throws IOException {
        writeMetadata(new File(journal, "cleanup-blocked"), new byte[0]);
    }

    public synchronized void rollback() throws IOException {
        if (finished) return;
        try {
            rollbackJournal(root, journal, target);
            finished = true;
        } finally {
            release();
        }
    }

    @Override public void close() throws IOException { rollback(); }

    private void requireOpen() throws IOException {
        if (finished) throw new IOException("安装事务已结束");
    }

    private void release() { synchronized (PluginInstallTransaction.class) { ACTIVE.remove(key); } }

    /** Must run off the UI thread before listing plugins or starting installation. */
    public static synchronized void recoverInterruptedInstalls(File root) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File journals = new File(canonicalRoot, ".install-transactions");
        if (Files.isSymbolicLink(journals.toPath())) throw new IOException("无效的安装日志目录");
        File[] transactions = journals.listFiles();
        if (transactions == null) return;
        for (File journal : transactions) {
            if (!journal.isDirectory() || Files.isSymbolicLink(journal.toPath())) continue;
            File idFile = new File(journal, "id");
            if (!idFile.isFile() || idFile.length() > 128) continue;
            String id = new String(Files.readAllBytes(idFile.toPath()), StandardCharsets.UTF_8);
            File target = resolvePluginDirectory(canonicalRoot, id);
            if (ACTIVE.contains(target.getAbsolutePath())) continue;
            if (new File(journal, "committed").isFile()) deleteTree(journal);
            else rollbackJournal(canonicalRoot, journal, target);
        }
    }

    private static void rollbackJournal(File root, File journal, File target) throws IOException {
        if (new File(journal, "cleanup-blocked").exists()) {
            throw new IOException("插件进程清理失败；请停止插件外部进程后处理保留的安装日志");
        }
        resolvePluginDirectory(root, target.getName());
        File backup = new File(journal, "backup");
        File staging = new File(journal, "staging");
        if (backup.exists()) {
            File restoring = new File(journal, "restoring");
            if (!restoring.exists()) Files.createFile(restoring.toPath());
            deleteTree(target);
            move(backup, target);
        } else if (!new File(journal, "restoring").exists()
                && new File(journal, "activating").isFile() && !staging.exists()) {
            deleteTree(target);
        }
        deleteTree(journal);
    }

    private static void move(File source, File destination) throws IOException {
        if (!source.renameTo(destination)) throw new IOException("无法切换插件目录: " + source.getName());
    }

    static void writeMetadata(File file, byte[] data) throws IOException {
        File temporary = File.createTempFile(".metadata-", ".tmp", file.getParentFile());
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(data);
                stream.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    /** Deletes links themselves, never follows them into another tree. */
    public static void deleteTree(File file) throws IOException {
        if (file == null || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取目录: " + file.getName());
            for (File child : children) deleteTree(child);
        }
        Files.delete(file.toPath());
    }
}
