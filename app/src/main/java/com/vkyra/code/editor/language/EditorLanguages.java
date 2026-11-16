package com.vkyra.code.editor.language;

import android.content.Context;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.registry.IThemeSource;

import java.io.InputStream;

import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;

/** One registry for the bundled upstream TextMate grammars; each editor owns its own analyzer. */
public final class EditorLanguages {
    private static boolean initialized;

    private EditorLanguages() { }

    public static synchronized void initialize(Context context) {
        if (initialized) return;
        if (context == null) throw new IllegalStateException("Editor languages require an application context");
        FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(context.getApplicationContext().getAssets()));
        try {
            loadTheme(context, "vkyra-light", false);
            loadTheme(context, "vkyra-dark", true);
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json");
            // GrammarRegistry subscribes to theme changes when first created.
            ThemeRegistry.getInstance().setTheme("vkyra-light");
            initialized = true;
        } catch (Exception error) {
            throw new IllegalStateException("Bundled editor grammar or theme is invalid", error);
        }
    }

    private static void loadTheme(Context context, String name, boolean dark) throws Exception {
        String path = "textmate/themes/" + name + ".json";
        try (InputStream input = context.getAssets().open(path)) {
            ThemeModel theme = new ThemeModel(IThemeSource.fromInputStream(input, path, null), name);
            theme.setDark(dark);
            ThemeRegistry.getInstance().loadTheme(theme, false);
        }
    }

    public static synchronized TextMateLanguage create(String scope, boolean tabs) {
        requireInitialized();
        TextMateLanguage language = TextMateLanguage.create(scope, false);
        language.setTabSize(4);
        language.useTab(tabs);
        language.setAutoCompleteEnabled(false); // C++ completion is provided by clangd and the editor adapter.
        return language;
    }

    public static synchronized TextMateColorScheme colorScheme(boolean dark) {
        requireInitialized();
        ThemeRegistry registry = ThemeRegistry.getInstance();
        registry.setTheme(dark ? "vkyra-dark" : "vkyra-light");
        return TextMateColorScheme.create(registry);
    }

    public static synchronized IGrammar grammar(String scope) {
        requireInitialized();
        IGrammar grammar = GrammarRegistry.getInstance().findGrammar(scope);
        if (grammar == null) throw new IllegalArgumentException("Unsupported grammar: " + scope);
        return grammar;
    }

    private static void requireInitialized() {
        if (!initialized) throw new IllegalStateException("Call EditorLanguages.initialize(applicationContext) before creating a language");
    }
}
