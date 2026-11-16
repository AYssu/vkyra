package com.vkyra.code.editor;

import com.vkyra.code.editor.language.TextMateLanguageAdapter;

/** JSON tokenization, bracket configuration and newline rules come from the upstream grammar. */
public final class JsonLanguage extends TextMateLanguageAdapter {
    public JsonLanguage() { super("source.json", false); }
}
