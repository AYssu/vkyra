package com.vkyra.code.lsp;

import org.eclipse.lsp4j.TextEdit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Validates a complete edit set before changing text. Positions use LSP UTF-16 units. */
public final class LspTextEdits {
    private LspTextEdits() {}

    public static String apply(String original, List<TextEdit> edits) {
        List<Replacement> replacements = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            TextEdit edit = edits.get(i);
            if (edit == null || edit.getRange() == null || edit.getNewText() == null) {
                throw new IllegalArgumentException("Incomplete language-server edit");
            }
            int start = offset(original, edit.getRange().getStart().getLine(),
                    edit.getRange().getStart().getCharacter());
            int end = offset(original, edit.getRange().getEnd().getLine(),
                    edit.getRange().getEnd().getCharacter());
            if (start > end) throw new IllegalArgumentException("Reversed edit range");
            replacements.add(new Replacement(start, end, i, edit.getNewText()));
        }
        replacements.sort(Comparator.comparingInt((Replacement r) -> r.start)
                .thenComparingInt(r -> r.end).thenComparingInt(r -> r.index));
        int occupiedEnd = -1;
        for (Replacement replacement : replacements) {
            if (replacement.start < occupiedEnd) {
                throw new IllegalArgumentException("Overlapping language-server edits");
            }
            occupiedEnd = Math.max(occupiedEnd, replacement.end);
        }
        // Reverse application preserves the server's ordering for insertions at the same offset.
        StringBuilder result = new StringBuilder(original);
        for (int i = replacements.size() - 1; i >= 0; i--) {
            Replacement replacement = replacements.get(i);
            result.replace(replacement.start, replacement.end, replacement.text);
        }
        return result.toString();
    }

    private static int offset(String text, int line, int character) {
        if (line < 0 || character < 0) throw new IllegalArgumentException("Negative edit position");
        int start = 0;
        for (int current = 0; current < line; current++) {
            int newline = text.indexOf('\n', start);
            if (newline == -1) throw new IllegalArgumentException("Edit line outside document");
            start = newline + 1;
        }
        int end = text.indexOf('\n', start);
        if (end == -1) end = text.length();
        if (end > start && text.charAt(end - 1) == '\r') end--;
        if (character > end - start) throw new IllegalArgumentException("Edit column outside document");
        int position = start + character;
        if (position > start && position < end && Character.isHighSurrogate(text.charAt(position - 1))
                && Character.isLowSurrogate(text.charAt(position))) {
            throw new IllegalArgumentException("Edit splits a Unicode surrogate pair");
        }
        return position;
    }

    private static final class Replacement {
        final int start, end, index;
        final String text;
        Replacement(int start, int end, int index, String text) {
            this.start = start; this.end = end; this.index = index; this.text = text;
        }
    }
}
