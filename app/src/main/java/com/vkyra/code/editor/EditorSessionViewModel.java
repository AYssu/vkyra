package com.vkyra.code.editor;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.vkyra.code.files.RootFileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns buffers and serialized disk work across Activity recreation. */
public final class EditorSessionViewModel extends AndroidViewModel {
    public final List<EditorDocument> documents = new ArrayList<>();
    public String activeDocumentId;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "vkyra-editor-io"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EditorSessionStore store;
    private final MutableLiveData<String> persistenceError = new MutableLiveData<>();
    private final MutableLiveData<Boolean> ready = new MutableLiveData<>(false);
    private String key;
    private boolean loading;

    public EditorSessionViewModel(@NonNull Application application) {
        super(application);
        store = new EditorSessionStore(new File(application.getNoBackupFilesDir(), "editor-sessions"));
    }

    public LiveData<Boolean> ready() { return ready; }
    public LiveData<String> persistenceError() { return persistenceError; }

    public void load(String sessionKey) {
        if (loading) return;
        loading = true;
        key = sessionKey;
        io.execute(() -> {
            EditorSessionStore.Snapshot snapshot = null;
            String error = null;
            try {
                snapshot = store.read(key);
                if (snapshot != null) for (EditorDocument document : snapshot.documents) {
                    if (!document.isModified && document.filePath != null) {
                        try { document.content = RootFileUtils.readFile(document.filePath); }
                        catch (java.io.IOException unavailable) {
                            // A missing/unreadable source must not destroy its recovered copy.
                            document.isModified = true;
                            error = "部分文件无法重新读取，已保留恢复的草稿";
                        }
                    }
                }
            }
            catch (Exception failure) { error = failure.getMessage(); }
            EditorSessionStore.Snapshot restored = snapshot;
            String recoveryError = error;
            main.post(() -> {
                if (restored != null) {
                    documents.addAll(restored.documents);
                    activeDocumentId = restored.activeDocumentId;
                }
                if (recoveryError != null) persistenceError.setValue(recoveryError);
                ready.setValue(true);
            });
        });
    }

    public void persist() {
        if (!Boolean.TRUE.equals(ready.getValue())) return;
        EditorSessionStore.Snapshot snapshot = EditorSessionStore.Snapshot.copyOf(documents, activeDocumentId);
        io.execute(() -> {
            try { store.write(key, snapshot); }
            catch (Exception failure) { persistenceError.postValue(failure.getMessage()); }
        });
    }

    public void executeIo(Runnable work) { io.execute(work); }

    public EditorDocument activeDocument() {
        for (EditorDocument document : documents) {
            if (document.id.equals(activeDocumentId)) return document;
        }
        return null;
    }

    @Override protected void onCleared() {
        // Finish queued durable commits; nothing here references a page.
        io.shutdown();
    }
}
