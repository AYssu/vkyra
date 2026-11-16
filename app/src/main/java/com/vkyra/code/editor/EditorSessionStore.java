package com.vkyra.code.editor;

import android.util.AtomicFile;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned, atomic draft snapshots. Call only from a session's IO executor. */
public final class EditorSessionStore {
    public static final int MAX_DOCUMENTS = 32;
    private static final int MAX_SNAPSHOT_BYTES = 64 * 1024 * 1024;
    private final File directory;
    private final Gson gson = new Gson();
    private final Set<String> damagedSessions = new HashSet<>();

    public EditorSessionStore(File directory) {
        this.directory = directory;
    }

    public static final class Snapshot {
        public int version = 1;
        public String activeDocumentId;
        public List<EditorDocument> documents = new ArrayList<>();

        public static Snapshot copyOf(List<EditorDocument> documents, String activeId) {
            Snapshot snapshot = new Snapshot();
            snapshot.activeDocumentId = activeId;
            for (EditorDocument document : documents) snapshot.documents.add(document.copy());
            return snapshot;
        }
    }

    public Snapshot read(String key) throws IOException {
        AtomicFile file = file(key);
        // openRead() also recovers AtomicFile's backup after an interrupted commit.
        if (!file.getBaseFile().exists()
                && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        byte[] bytes;
        try (java.io.InputStream input = file.openRead();
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) output.size() + count > MAX_SNAPSHOT_BYTES) {
                    damagedSessions.add(key);
                    throw new IOException("Draft snapshot exceeds the recovery limit");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        try {
            Snapshot snapshot = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), Snapshot.class);
            validate(snapshot);
            return snapshot;
        } catch (JsonParseException | IllegalArgumentException e) {
            damagedSessions.add(key);
            throw new IOException("Draft snapshot is invalid; the original has been retained", e);
        }
    }

    public void write(String key, Snapshot snapshot) throws IOException {
        validate(snapshot);
        byte[] bytes = gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SNAPSHOT_BYTES) throw new IOException("Draft snapshot is too large");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create draft directory");
        }
        AtomicFile file = file(key);
        if (damagedSessions.contains(key) && file.getBaseFile().exists()) {
            // Preserve the unreadable data even after the next successful draft commit.
            File archive = new File(directory, file.getBaseFile().getName()
                    + "." + java.util.UUID.randomUUID() + ".corrupt");
            java.nio.file.Files.copy(file.getBaseFile().toPath(), archive.toPath());
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    archive.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            damagedSessions.remove(key);
        }
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            throw e;
        }
    }

    private AtomicFile file(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return new AtomicFile(new File(directory, name + ".json"));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void validate(Snapshot snapshot) {
        if (snapshot == null || snapshot.version != 1 || snapshot.documents == null
                || snapshot.documents.size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("Unsupported draft session");
        }
        Set<String> ids = new HashSet<>();
        for (EditorDocument document : snapshot.documents) {
            if (document == null || document.id == null || document.id.isEmpty()
                    || !ids.add(document.id) || document.content == null
                    || document.cursorLine < 0 || document.cursorColumn < 0 || document.scrollY < 0) {
                throw new IllegalArgumentException("Invalid draft document");
            }
        }
        if (!snapshot.documents.isEmpty() && !ids.contains(snapshot.activeDocumentId)) {
            throw new IllegalArgumentException("Missing active draft document");
        }
    }
}
