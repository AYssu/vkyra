package com.vkyra.code.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import java.util.ArrayList;
import java.util.List;

/** Plans a completion and its additional edits against one immutable document snapshot. */
public final class CompletionEdits {
    private CompletionEdits() { }

    public static Result plan(String snapshot, CompletionItem item, int line, int column, int prefixLength) {
        if (item.getInsertTextFormat() == InsertTextFormat.Snippet) {
            throw new IllegalArgumentException("Server returned a snippet without negotiated snippet support");
        }
        TextEdit primary;
        if (item.getTextEdit() != null) {
            primary = item.getTextEdit().isLeft() ? item.getTextEdit().getLeft()
                    : new TextEdit(item.getTextEdit().getRight().getInsert(), item.getTextEdit().getRight().getNewText());
        } else {
            primary = new TextEdit(new Range(new Position(line, column - prefixLength), new Position(line, column)),
                    item.getInsertText() == null ? item.getLabel() : item.getInsertText());
        }
        List<TextEdit> edits = new ArrayList<>();
        edits.add(primary);
        if (item.getAdditionalTextEdits() != null) edits.addAll(item.getAdditionalTextEdits());
        String result = LspTextEdits.apply(snapshot, edits); // validate every range before UI mutation
        int start = offset(snapshot, primary.getRange().getStart());
        int cursor = start + primary.getNewText().length();
        for (int i = 1; i < edits.size(); i++) {
            TextEdit edit = edits.get(i);
            int otherStart = offset(snapshot, edit.getRange().getStart());
            int otherEnd = offset(snapshot, edit.getRange().getEnd());
            if (otherStart < start) cursor += edit.getNewText().length() - (otherEnd - otherStart);
        }
        return new Result(result, cursor);
    }

    private static int offset(String text, Position position) {
        int offset = 0;
        for (int line = 0; line < position.getLine(); line++) offset = text.indexOf('\n', offset) + 1;
        return offset + position.getCharacter();
    }

    public static final class Result {
        public final String text;
        public final int cursor;
        Result(String text, int cursor) { this.text = text; this.cursor = cursor; }
    }
}
