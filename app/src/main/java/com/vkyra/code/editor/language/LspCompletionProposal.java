package com.vkyra.code.editor.language;

import android.widget.Toast;

import com.vkyra.code.lsp.CompletionEdits;

import org.eclipse.lsp4j.CompletionItemTag;

import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.lang.completion.CompletionItemKind;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

/** Thin UI adapter; uses LSP4J's edit model and the shared validated workspace-edit planner. */
public final class LspCompletionProposal extends CompletionItem {
    private final org.eclipse.lsp4j.CompletionItem item;
    private final String snapshot;

    public LspCompletionProposal(org.eclipse.lsp4j.CompletionItem item, int prefixLength, String snapshot) {
        super(item.getLabel(), item.getDetail());
        this.item = item;
        this.snapshot = snapshot;
        this.prefixLength = prefixLength;
        sortText = item.getSortText();
        filterText = item.getFilterText();
        if (item.getKind() != null) {
            try { kind = CompletionItemKind.valueOf(item.getKind().name()); }
            catch (IllegalArgumentException ignored) { kind = CompletionItemKind.Text; }
        }
        deprecated = item.getTags() != null && item.getTags().contains(CompletionItemTag.Deprecated);
    }

    @Override public void performCompletion(CodeEditor editor, Content text, int line, int column) {
        try {
            if (!snapshot.equals(text.toString())) throw new IllegalStateException("Document changed");
            CompletionEdits.Result result = CompletionEdits.plan(snapshot, item, line, column, prefixLength);
            // Apply the smallest contiguous change so unchanged lines keep editor analysis state.
            int prefix = 0;
            while (prefix < snapshot.length() && prefix < result.text.length()
                    && snapshot.charAt(prefix) == result.text.charAt(prefix)) prefix++;
            if (prefix > 0 && prefix < snapshot.length()
                    && Character.isHighSurrogate(snapshot.charAt(prefix - 1))) prefix--;
            int oldEnd = snapshot.length(), newEnd = result.text.length();
            while (oldEnd > prefix && newEnd > prefix
                    && snapshot.charAt(oldEnd - 1) == result.text.charAt(newEnd - 1)) {
                oldEnd--;
                newEnd--;
            }
            if (oldEnd < snapshot.length() && oldEnd > prefix
                    && Character.isHighSurrogate(snapshot.charAt(oldEnd - 1))) {
                oldEnd++;
                newEnd++;
            }
            text.replace(prefix, oldEnd, result.text.substring(prefix, newEnd));
            var position = text.getIndexer().getCharPosition(result.cursor);
            editor.setSelection(position.line, position.column);
        } catch (RuntimeException invalid) {
            Toast.makeText(editor.getContext(), "补全已过期，请重新触发", Toast.LENGTH_SHORT).show();
        }
    }
}
