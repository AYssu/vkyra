package com.vkyra.code.lsp;

import static org.junit.Assert.*;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.Test;
import java.util.Arrays;

public class LspTextEditsTest {
    private static TextEdit edit(int l, int c, int el, int ec, String text) {
        return new TextEdit(new Range(new Position(l, c), new Position(el, ec)), text);
    }
    @Test public void appliesMultipleRangesUsingOriginalOffsetsAndPreservesCrLf() {
        assertEquals("😀 renamed\r\nrenamed;", LspTextEdits.apply("😀 value\r\nvalue;", Arrays.asList(
                edit(0, 3, 0, 8, "renamed"), edit(1, 0, 1, 5, "renamed"))));
    }
    @Test public void multilineRangeDoesNotAppendANewline() {
        assertEquals("beforeafter", LspTextEdits.apply("before\nremove\nafter", Arrays.asList(edit(0, 6, 2, 0, ""))));
    }
    @Test public void samePositionInsertionsKeepServerOrder() {
        assertEquals("firstsecond!", LspTextEdits.apply("!", Arrays.asList(
                edit(0, 0, 0, 0, "first"), edit(0, 0, 0, 0, "second"))));
    }
    @Test public void rejectsOverlapAndInvalidUtf16BeforeChangingAnything() {
        assertThrows(IllegalArgumentException.class, () -> LspTextEdits.apply("abcdef", Arrays.asList(
                edit(0, 1, 0, 4, "x"), edit(0, 2, 0, 5, "y"))));
        assertThrows(IllegalArgumentException.class, () -> LspTextEdits.apply("😀", Arrays.asList(edit(0, 1, 0, 2, "x"))));
        assertThrows(IllegalArgumentException.class, () -> LspTextEdits.apply("a", Arrays.asList(edit(9, 0, 9, 0, "x"))));
    }
}
