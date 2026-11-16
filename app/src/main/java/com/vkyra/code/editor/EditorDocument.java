package com.vkyra.code.editor;

import java.io.File;
import java.util.UUID;

/** A document buffer belongs to a session, independently of a page or a file path. */
public final class EditorDocument {
    public String id = UUID.randomUUID().toString();
    public String filePath;
    public String content = "";
    public int cursorLine;
    public int cursorColumn;
    public int scrollY;
    public boolean isModified;

    public EditorDocument(String path) {
        filePath = path;
    }

    public String getFileName() {
        return filePath == null ? "untitled.cpp" : new File(filePath).getName();
    }

    public EditorDocument copy() {
        EditorDocument copy = new EditorDocument(filePath);
        copy.id = id;
        copy.content = content;
        copy.cursorLine = cursorLine;
        copy.cursorColumn = cursorColumn;
        copy.scrollY = scrollY;
        copy.isModified = isModified;
        return copy;
    }
}
