package com.vkyra.code.editor.language;

import com.vkyra.code.R;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Exercises the shipped grammars through the actual TM4E/Joni engine. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26, application = android.app.Application.class)
public class TextMateGrammarTest {
    private static Registry registry;

    @BeforeClass public static void loadUpstreamGrammars() {
        File root = new File("src/main/assets/textmate");
        if (!root.isDirectory()) root = new File("app/src/main/assets/textmate");
        assertTrue("Bundled grammars must be present", root.isDirectory());
        Map<String, File> sources = new HashMap<>();
        sources.put("source.c", new File(root, "cpp/c.tmLanguage.json"));
        sources.put("source.cpp", new File(root, "cpp/cpp.tmLanguage.json"));
        sources.put("source.c.platform", new File(root, "cpp/platform.tmLanguage.json"));
        sources.put("source.cpp.embedded.macro", new File(root, "cpp/cpp.embedded.macro.tmLanguage.json"));
        sources.put("source.json", new File(root, "json/json.tmLanguage.json"));
        sources.put("source.makefile", new File(root, "make/make.tmLanguage.json"));
        registry = new Registry();
        // Match production's GrammarRegistry.addGrammar: register all mutually referenced sources.
        sources.values().forEach(source -> registry.addGrammar(IGrammarSource.fromFile(source)));
    }

    @Test public void cppCommentsStringsAndRawStringsCannotCreateFallbackSymbolsOrBraces() {
        String code = "int real = 1; // int fake = 2; }\n"
                + "/* { int hidden = 3;\n still hidden } */\n"
                + "const char* text = \"int quoted = 4; { } 中文😀\";\n"
                + "auto raw = R\"tag({ int rawFake;\n } 中文😀)tag\";\n"
                + "void actual() { real++; }\n";
        String masked = CppLexicalSnapshot.codeOnly(code, grammar("source.cpp"));
        assertEquals(code.length(), masked.length());
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') assertEquals('\n', masked.charAt(i));
        }
        assertTrue(masked.contains("int real = 1;"));
        assertTrue(masked.contains("void actual() { real++; }"));
        assertFalse(masked.contains("fake"));
        assertFalse(masked.contains("hidden"));
        assertFalse(masked.contains("quoted"));
        assertFalse(masked.contains("rawFake"));
        assertEquals(1, masked.chars().filter(value -> value == '{').count());
        assertEquals(1, masked.chars().filter(value -> value == '}').count());
    }

    @Test public void cppEscapedQuotesAndCrLfKeepExactUtf16Positions() {
        String code = "auto 文本 = \"escaped \\\" } 😀\";\r\nint after = 2;\r\n";
        String masked = CppLexicalSnapshot.codeOnly(code, grammar("source.cpp"));
        assertEquals(code.length(), masked.length());
        assertEquals(code.indexOf("after"), masked.indexOf("after"));
        assertTrue(masked.contains("auto 文本 ="));
        assertFalse(masked.contains("}"));
        assertEquals(code.indexOf('\r'), masked.indexOf('\r'));
    }

    @Test public void interruptedRawStringLineRetriesFromOriginalStateUsingRealGrammar() {
        String code = "auto raw = R\"tag({ int fake;\n } 中文😀)tag\";\nvoid actual() {}\n";
        IGrammar grammar = grammar("source.cpp");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        String masked = CppLexicalSnapshot.codeOnly(code, (line, incoming, budget) -> {
            int call = calls.getAndIncrement();
            if (call == 1) assertNull("The retry cannot start inside the partially scanned raw string", incoming);
            ITokenizeLineResult<IToken[]> actual = grammar.tokenizeLine(line, incoming, budget);
            if (call != 0) return actual;
            // Simulate the library reaching its deadline after entering a multiline string.
            // Keep its real tokens/stack so using that partial stack would corrupt the retry.
            return new ITokenizeLineResult<IToken[]>() {
                @Override public IToken[] getTokens() { return actual.getTokens(); }
                @Override public IStateStack getRuleStack() { return actual.getRuleStack(); }
                @Override public boolean isStoppedEarly() { return true; }
            };
        }, System::nanoTime);
        assertTrue(masked.contains("auto raw ="));
        assertTrue(masked.contains("void actual() {}"));
        assertFalse(masked.contains("fake"));
        assertFalse(masked.contains("中文"));
        assertEquals(code.length(), masked.length());
    }

    @Test public void cppEmojiBeforeVisibleCodeDoesNotShiftMaskOffsets() {
        String code = "auto text = \"😀\"; int visible = 1; // 😀 { hidden }";
        String masked = CppLexicalSnapshot.codeOnly(code, grammar("source.cpp"));
        assertEquals(code.length(), masked.length());
        assertTrue(masked.contains("; int visible = 1;"));
        assertEquals(code.indexOf("visible"), masked.indexOf("visible"));
        assertFalse(masked.contains("hidden"));
        assertFalse(masked.contains("{"));
        assertFalse(masked.contains("}"));
    }

    @Test public void jsonUnicodeAndEscapedBracesAreStrings() {
        String line = "{\"中文😀\": \"escaped \\\" } {\", \"n\": 42}";
        IToken[] tokens = grammar("source.json").tokenizeLine(line).getTokens();
        assertTrue(nonCodeAt(tokens, line.indexOf("中文")));
        assertTrue(nonCodeAt(tokens, line.indexOf("} {")));
        assertFalse(nonCodeAt(tokens, 0));
        assertFalse(nonCodeAt(tokens, line.lastIndexOf('}')));
    }

    @Test public void makefileRecipeVariableAndCommentUseUpstreamScopes() {
        IGrammar grammar = grammar("source.makefile");
        var target = grammar.tokenizeLine("all: 中文.o");
        String recipe = "\t$(CXX) -o app 中文.cpp # } ignored";
        var result = grammar.tokenizeLine(recipe, target.getRuleStack(), java.time.Duration.ofSeconds(2));
        assertTrue(hasScope(result.getTokens(), "meta.scope.recipe.makefile"));
        assertTrue(hasScope(result.getTokens(), "variable"));
        String comment = "# } ignored 中文😀";
        assertTrue(nonCodeAt(grammar.tokenizeLine(comment).getTokens(), comment.indexOf("ignored")));
    }

    private static IGrammar grammar(String scope) {
        IGrammar grammar = registry.grammarForScopeName(scope);
        assertNotNull(scope, grammar);
        return grammar;
    }

    private static boolean nonCodeAt(IToken[] tokens, int offset) {
        for (IToken token : tokens) {
            if (token.getStartIndex() <= offset && token.getEndIndex() > offset) {
                return CppLexicalSnapshot.isNonCode(token);
            }
        }
        return false;
    }

    private static boolean hasScope(IToken[] tokens, String fragment) {
        for (IToken token : tokens) for (String scope : token.getScopes()) {
            if (scope.contains(fragment)) return true;
        }
        return false;
    }
}
