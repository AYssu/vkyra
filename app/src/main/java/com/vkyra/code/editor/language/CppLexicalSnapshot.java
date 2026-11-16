package com.vkyra.code.editor.language;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Scope masks for the optional local symbol fallback; clangd owns semantic navigation. */
public final class CppLexicalSnapshot {
    static final long SNAPSHOT_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long LINE_BUDGET_MILLIS = 200;
    private static final long RECOVERY_BUDGET_MILLIS = 1000;
    private CppLexicalSnapshot() { }

    @FunctionalInterface interface Tokenizer {
        ITokenizeLineResult<IToken[]> tokenize(String line, IStateStack state, Duration budget);
    }

    /** Preserves every UTF-16 offset and newline while masking strings/comments using TextMate. */
    public static String codeOnly(String source) {
        return codeOnly(source, EditorLanguages.grammar("source.cpp"));
    }

    static String codeOnly(String source, IGrammar grammar) {
        return codeOnly(source, grammar::tokenizeLine, System::nanoTime);
    }

    static String codeOnly(String source, Tokenizer tokenizer, LongSupplier nanoTime) {
        long started = nanoTime.getAsLong();
        StringBuilder masked = new StringBuilder(source);
        IStateStack state = null;
        int offset = 0;
        String[] lines = source.split("\n", -1);
        for (String line : lines) {
            checkCancelled();
            long remaining = SNAPSHOT_BUDGET_NANOS - (nanoTime.getAsLong() - started);
            if (remaining < TimeUnit.MILLISECONDS.toNanos(1)) { mask(masked, offset, source.length()); break; }
            var tokens = tokenizer.tokenize(line, state, lineBudget(remaining, LINE_BUDGET_MILLIS));
            checkCancelled();
            if (tokens.isStoppedEarly()) {
                // TM4E's 200ms wall-clock limit includes lazy Joni scanner/regex compilation.
                // Retry once with up to 1s for the remaining lazy rules, within the 3s snapshot
                // budget, from the SAME complete incoming state. This runs on the symbol worker.
                // A partially scanned stack is never safe as the starting state of a whole line.
                remaining = SNAPSHOT_BUDGET_NANOS - (nanoTime.getAsLong() - started);
                if (remaining >= TimeUnit.MILLISECONDS.toNanos(1)) {
                    tokens = tokenizer.tokenize(line, state, lineBudget(remaining, RECOVERY_BUDGET_MILLIS));
                    checkCancelled();
                }
            }
            if (tokens.isStoppedEarly()) {
                // An incomplete lexical state must not expose a string/comment as source symbols.
                mask(masked, offset, source.length());
                break;
            } else {
                state = tokens.getRuleStack();
                int points = line.codePointCount(0, line.length());
                for (IToken token : tokens.getTokens()) {
                    if (isNonCode(token)) {
                        // TM4E/Joni token offsets count Unicode code points; editor/LSP offsets are UTF-16.
                        int start = line.offsetByCodePoints(0, Math.min(points, token.getStartIndex()));
                        int end = line.offsetByCodePoints(0, Math.min(points, token.getEndIndex()));
                        mask(masked, offset + start, offset + end);
                    }
                }
            }
            offset += line.length() + 1;
        }
        checkCancelled();
        return masked.toString();
    }

    private static Duration lineBudget(long remainingNanos, long limitMillis) {
        // TM4E treats a Duration that truncates to zero milliseconds as UNLIMITED.
        // Callers stop with less than 1ms remaining, so this always stays positive and bounded.
        return Duration.ofMillis(Math.min(limitMillis, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }

    public static boolean isNonCode(IToken token) {
        for (String scope : token.getScopes()) {
            if (scope.startsWith("comment.") || scope.equals("comment")
                    || scope.startsWith("string.") || scope.equals("string")) return true;
        }
        return false;
    }

    private static void mask(StringBuilder source, int start, int end) {
        for (int index = Math.max(0, start); index < end && index < source.length(); index++) {
            char value = source.charAt(index);
            if (value != '\n' && value != '\r') source.setCharAt(index, ' ');
        }
    }
}
