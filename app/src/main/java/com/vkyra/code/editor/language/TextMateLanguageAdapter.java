package com.vkyra.code.editor.language;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

/** Adapts the existing language entry points without keeping a second handwritten tokenizer. */
public class TextMateLanguageAdapter implements Language {
    protected final TextMateLanguage syntax;
    private final AnalyzeManager analysis;

    public TextMateLanguageAdapter(String scope, boolean tabs) {
        syntax = EditorLanguages.create(scope, tabs);
        analysis = new TextMateAnalysisAdapter(syntax.getAnalyzeManager());
    }

    @NonNull @Override public AnalyzeManager getAnalyzeManager() { return analysis; }
    @Override public int getInterruptionLevel() { return syntax.getInterruptionLevel(); }
    @Override public void requireAutoComplete(@NonNull ContentReference content, @NonNull CharPosition position,
                                            @NonNull CompletionPublisher publisher, @NonNull Bundle extraArguments) {
        syntax.requireAutoComplete(content, position, publisher, extraArguments);
    }
    @Override public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        return syntax.getIndentAdvance(content, line, column);
    }
    @Override public boolean useTab() { return syntax.useTab(); }
    @NonNull @Override public Formatter getFormatter() { return syntax.getFormatter(); }
    @Nullable @Override public SymbolPairMatch getSymbolPairs() { return syntax.getSymbolPairs(); }
    @Nullable @Override public NewlineHandler[] getNewlineHandlers() { return syntax.getNewlineHandlers(); }
    @Override public void destroy() {
        analysis.destroy();
        syntax.destroy();
    }
}
