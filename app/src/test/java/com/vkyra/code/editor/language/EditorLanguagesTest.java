package com.vkyra.code.editor.language;

import android.app.Application;

import com.vkyra.code.editor.CppLanguage;
import com.vkyra.code.editor.JsonLanguage;
import com.vkyra.code.editor.MakefileLanguage;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.StandardTokenType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.rosemoe.sora.lang.brackets.OnlineBracketsMatcher;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.SpanFactory;
import io.github.rosemoe.sora.text.Content;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26, application = Application.class)
public class EditorLanguagesTest {
    @Before public void initialize() { EditorLanguages.initialize(RuntimeEnvironment.getApplication()); }

    @Test public void allLanguagesUseTheSharedTextMateAnalyzerAndOfficialConfiguration() {
        CppLanguage cpp = new CppLanguage(RuntimeEnvironment.getApplication());
        JsonLanguage json = new JsonLanguage();
        MakefileLanguage make = new MakefileLanguage();
        try {
            assertTrue(cpp.getAnalyzeManager() instanceof TextMateAnalysisAdapter);
            assertTrue(json.getAnalyzeManager() instanceof TextMateAnalysisAdapter);
            assertTrue(make.getAnalyzeManager() instanceof TextMateAnalysisAdapter);
            assertFalse(cpp.useTab());
            assertFalse(json.useTab());
            assertTrue(make.useTab());
            assertNotNull(cpp.getNewlineHandlers());
            assertNotNull(json.getSymbolPairs());
            assertNotNull(EditorLanguages.colorScheme(true));
            assertNotNull(EditorLanguages.colorScheme(false));
        } finally { cpp.destroy(); json.destroy(); make.destroy(); }
    }

    @Test public void fallbackIndexPublishesWholeSnapshotsAndIgnoresQuotedOrCommentSymbols() throws Exception {
        CppLanguage cpp = new CppLanguage(RuntimeEnvironment.getApplication());
        try {
            cpp.extractIdentifiers("int 真实 = 1; // int fake; }\nvoid 工作() {\n真实++;\n}\n");
            assertTrue(cpp.getExtractedVariables().contains("真实"));
            assertFalse(cpp.getExtractedVariables().contains("fake"));
            assertNotNull(cpp.getFunctionDefinition("工作"));
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 25; i++) tasks.add(cpp.extractIdentifiersAsync("int value" + i + ";\n"));
            tasks.get(tasks.size() - 1).get(5, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of("value24"), cpp.getExtractedVariables());
            for (CompletableFuture<Void> task : tasks) assertTrue(task.isDone());
            try { cpp.getExtractedFunctions().add("corrupt"); fail("Published functions must be immutable"); }
            catch (UnsupportedOperationException expected) { }
        } finally { cpp.destroy(); }
        assertTrue(cpp.extractIdentifiersAsync("int afterDestroy;").isCancelled());
    }

    @Test public void nativeSoraMatcherUsesGrammarScopesToSkipStringAndCommentBraces() {
        String code = "void test() {\n  auto s = \"😀 } {\"; // } fake\n  /* { } */\n}\n";
        Content content = new Content(code);
        IGrammar grammar = EditorLanguages.grammar("source.cpp");
        MappedSpans.Builder spans = new MappedSpans.Builder();
        IStateStack state = null;
        String[] lines = code.split("\n", -1);
        for (int line = 0; line < lines.length; line++) {
            var tokens = grammar.tokenizeLine(lines[line], state, Duration.ofSeconds(2));
            state = tokens.getRuleStack();
            int points = lines[line].codePointCount(0, lines[line].length());
            for (var token : tokens.getTokens()) {
                int column = lines[line].offsetByCodePoints(0, Math.min(points, token.getStartIndex()));
                Span span = SpanFactory.obtain(column, 1L);
                span.setExtra(CppLexicalSnapshot.isNonCode(token) ? StandardTokenType.String : StandardTokenType.Other);
                spans.add(line, span);
            }
        }
        var provider = new ScopedBracketsProvider(new OnlineBracketsMatcher(new char[]{'{', '}'}, 100000), spans.build());
        var pair = provider.getPairedBracketAt(content, code.indexOf('{') + 1);
        assertNotNull(pair);
        assertEquals(code.indexOf('{'), pair.leftIndex);
        assertEquals(code.lastIndexOf('}'), pair.rightIndex);
        assertNull(provider.getPairedBracketAt(content, code.indexOf("} fake") + 1));
        assertNull(provider.getPairedBracketAt(content, code.indexOf("} {") + 1));
    }
}
