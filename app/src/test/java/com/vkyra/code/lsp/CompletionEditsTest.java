package com.vkyra.code.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CompletionEditsTest {
    @Test public void explicitServerRangeReplacesSuffixAndAdditionalIncludeAtomically() {
        String original = "// 中文😀\nint main() { vectSuffix; }\n";
        CompletionItem item = new CompletionItem("vector");
        item.setTextEdit(Either.forLeft(new TextEdit(range(1, 13, 23), "vector")));
        item.setAdditionalTextEdits(List.of(new TextEdit(range(0, 0, 0), "#include <vector>\n")));
        var result = CompletionEdits.plan(original, item, 1, 17, 4);
        assertEquals("#include <vector>\n// 中文😀\nint main() { vector; }\n", result.text);
        assertEquals(result.text.indexOf("vector; }") + 6, result.cursor);
    }

    @Test public void insertReplaceUsesInsertRangeAndCursorRemainsUtf16Correct() {
        String original = "😀 文";
        CompletionItem item = new CompletionItem("文本");
        item.setTextEdit(Either.forRight(new InsertReplaceEdit("文本", range(0, 3, 4), range(0, 3, 4))));
        var result = CompletionEdits.plan(original, item, 0, 4, 1);
        assertEquals("😀 文本", result.text);
        assertEquals(5, result.cursor);
    }

    @Test public void plainInsertTextUsesTypedPrefix() {
        CompletionItem item = new CompletionItem("display label");
        item.setInsertText("print()");
        var result = CompletionEdits.plan("pri", item, 0, 3, 3);
        assertEquals("print()", result.text);
        assertEquals(7, result.cursor);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidAdditionalRangeRejectsEntireProposal() {
        CompletionItem item = new CompletionItem("valid");
        item.setAdditionalTextEdits(List.of(new TextEdit(range(8, 0, 0), "bad")));
        CompletionEdits.plan("va", item, 0, 2, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void overlappingAdditionalEditRejectsEntireProposal() {
        CompletionItem item = new CompletionItem("valid");
        item.setAdditionalTextEdits(List.of(new TextEdit(range(0, 0, 1), "bad")));
        CompletionEdits.plan("va", item, 0, 2, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unnegotiatedSnippetIsRejectedWithoutRemovingLiteralDollarSyntax() {
        CompletionItem item = new CompletionItem("func");
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setInsertText("func(${1:value})$0");
        CompletionEdits.plan("fu", item, 0, 2, 2);
    }

    private static Range range(int line, int start, int end) {
        return new Range(new Position(line, start), new Position(line, end));
    }
}
