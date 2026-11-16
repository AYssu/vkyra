package com.vkyra.code.editor;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26, manifest = Config.NONE)
public class EditorSessionStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File directory;
    private EditorSessionStore store;

    @Before public void createStore() throws Exception {
        directory = temporary.newFolder("sessions");
        store = new EditorSessionStore(directory);
    }

    @Test public void restoresTwoDistinctUntitledBuffersAndActiveCursor() throws Exception {
        EditorDocument first = new EditorDocument(null);
        first.content = "未保存\r\n😀";
        first.isModified = true;
        EditorDocument second = new EditorDocument(null);
        second.content = "another draft";
        second.cursorLine = 3;
        second.cursorColumn = 8;
        second.scrollY = 240;
        store.write("project", EditorSessionStore.Snapshot.copyOf(Arrays.asList(first, second), second.id));

        EditorSessionStore.Snapshot recovered = new EditorSessionStore(directory).read("project");
        assertEquals(2, recovered.documents.size());
        assertNotEquals(recovered.documents.get(0).id, recovered.documents.get(1).id);
        assertEquals("未保存\r\n😀", recovered.documents.get(0).content);
        assertTrue(recovered.documents.get(0).isModified);
        assertEquals(second.id, recovered.activeDocumentId);
        assertEquals(8, recovered.documents.get(1).cursorColumn);
        assertEquals(240, recovered.documents.get(1).scrollY);
    }

    @Test public void snapshotDoesNotChangeWhenLiveBufferChanges() {
        EditorDocument document = new EditorDocument("/project/main.cpp");
        document.content = "before";
        EditorSessionStore.Snapshot snapshot = EditorSessionStore.Snapshot.copyOf(Arrays.asList(document), document.id);
        document.content = "after";
        assertEquals("before", snapshot.documents.get(0).content);
    }

    @Test public void invalidReplacementDoesNotDestroyDurableDraft() throws Exception {
        EditorDocument document = new EditorDocument(null);
        document.content = "valuable draft";
        EditorSessionStore.Snapshot snapshot = EditorSessionStore.Snapshot.copyOf(Arrays.asList(document), document.id);
        store.write("session", snapshot);
        snapshot.documents.add(document.copy());
        assertThrows(IllegalArgumentException.class, () -> store.write("session", snapshot));
        assertEquals("valuable draft", store.read("session").documents.get(0).content);
    }

    @Test public void interruptedAtomicWriteRecoversBackup() throws Exception {
        EditorDocument document = new EditorDocument(null);
        document.content = "committed";
        store.write("session", EditorSessionStore.Snapshot.copyOf(Arrays.asList(document), document.id));
        File file = directory.listFiles()[0];
        assertTrue(file.renameTo(new File(file.getPath() + ".bak")));
        Files.write(file.toPath(), "partial".getBytes(StandardCharsets.UTF_8));
        assertEquals("committed", store.read("session").documents.get(0).content);
    }

    @Test public void corruptDraftIsReportedAndNotDeleted() throws Exception {
        EditorDocument document = new EditorDocument(null);
        store.write("session", EditorSessionStore.Snapshot.copyOf(Arrays.asList(document), document.id));
        File file = directory.listFiles()[0];
        Files.write(file.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> store.read("session"));
        assertEquals("{broken", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        store.write("session", EditorSessionStore.Snapshot.copyOf(Arrays.asList(document), document.id));
        File[] archives = directory.listFiles((parent, name) -> name.endsWith(".corrupt"));
        assertEquals(1, archives.length);
        assertEquals("{broken", new String(Files.readAllBytes(archives[0].toPath()), StandardCharsets.UTF_8));
        assertEquals(document.id, store.read("session").activeDocumentId);
    }

    @Test public void sessionKeyIsNotUsedAsAnUntrustedPath() throws Exception {
        store.write("../../outside", new EditorSessionStore.Snapshot());
        assertEquals(1, directory.listFiles().length);
        assertEquals(directory.getCanonicalFile(), directory.listFiles()[0].getCanonicalFile().getParentFile());
        assertNull(store.read("a different project"));
    }
}
