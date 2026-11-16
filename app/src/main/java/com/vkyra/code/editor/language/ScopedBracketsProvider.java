package com.vkyra.code.editor.language;

import org.eclipse.tm4e.core.internal.grammar.tokenattrs.StandardTokenType;

import java.util.List;

import io.github.rosemoe.sora.lang.brackets.BracketsProvider;
import io.github.rosemoe.sora.lang.brackets.PairedBracket;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Spans;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;

/** Reuses Sora's matcher, exposing only characters classified as code by TextMate. */
final class ScopedBracketsProvider implements BracketsProvider {
    private final BracketsProvider matcher;
    private final Spans spans;

    ScopedBracketsProvider(BracketsProvider matcher, Spans spans) {
        this.matcher = matcher;
        this.spans = spans;
    }

    @Override public PairedBracket getPairedBracketAt(Content text, int index) {
        if (matcher == null || spans == null || index < 0 || index > text.length()) return null;
        Spans.Reader reader = spans.read();
        try {
            return matcher.getPairedBracketAt(new CodeOnlyContent(text, reader), index);
        } catch (IndexOutOfBoundsException | IllegalStateException staleAnalysis) {
            return null;
        } finally {
            reader.moveToLine(-1);
        }
    }

    /** A read-only view for OnlineBracketsMatcher's length()/charAt() calls; no copy or reparse. */
    private static final class CodeOnlyContent extends Content {
        private final Content original;
        private final Spans.Reader reader;
        private final CharPosition position = new CharPosition();
        private int currentLine = -1;
        private List<Span> currentSpans;

        CodeOnlyContent(Content original, Spans.Reader reader) {
            super(null, false);
            this.original = original;
            this.reader = reader;
        }

        @Override public int length() { return original == null ? 0 : original.length(); }

        @Override public char charAt(int index) {
            original.getIndexer().getCharPosition(index, position);
            if (position.line != currentLine) {
                currentLine = position.line;
                currentSpans = reader.getSpansOnLine(currentLine);
            }
            int low = 0, high = currentSpans.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (currentSpans.get(middle).getColumn() <= position.column) low = middle + 1;
                else high = middle;
            }
            if (low == 0) return ' ';
            Object tokenType = currentSpans.get(low - 1).getExtra();
            if (!(tokenType instanceof Integer) || (Integer) tokenType != StandardTokenType.Other) return ' ';
            return original.charAt(index);
        }
    }
}
