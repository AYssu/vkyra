package com.vkyra.code.editor;

import com.vkyra.code.editor.language.TextMateLanguageAdapter;

/** GNU Make tokenization and language configuration, preserving recipe tabs. */
public final class MakefileLanguage extends TextMateLanguageAdapter {
    public MakefileLanguage() { super("source.makefile", true); }
}
