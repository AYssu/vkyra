package com.vkyra.code.editor.language;

import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Controlled tokenizer outcomes, with no sleeps or CPU-speed assertions. */
public class CppLexicalSnapshotTest {
    @Test public void retryStartsFromLastCompleteStateAndOnlyPublishesCompleteTokens() {
        IStateStack first = () -> 1, poisoned = () -> 99, second = () -> 2;
        AtomicInteger calls = new AtomicInteger();
        String masked = CppLexicalSnapshot.codeOnly("real\nfake\nactual", (line, incoming, budget) -> {
            assertTrue(budget.toMillis() > 0);
            switch (calls.getAndIncrement()) {
                case 0: assertEquals(200, budget.toMillis()); assertNull(incoming); return result(first, false);
                case 1: assertEquals(200, budget.toMillis()); assertSame(first, incoming); return result(poisoned, true);
                case 2: assertEquals(1000, budget.toMillis()); assertSame(first, incoming); return result(second, false, nonCode(0, 4));
                case 3: assertEquals(200, budget.toMillis()); assertSame(second, incoming); return result(second, false);
                default: throw new AssertionError("Unexpected extra scan");
            }
        }, () -> 0);
        assertEquals("real\n    \nactual", masked);
        assertEquals(4, calls.get());
    }

    @Test public void repeatedTimeoutMasksCurrentLineAndTailWithoutTrustingPartialTokens() {
        AtomicInteger calls = new AtomicInteger();
        String masked = CppLexicalSnapshot.codeOnly("int safe;\nR\"tag(\nfake }\r\nactual", (line, incoming, budget) -> {
            int call = calls.getAndIncrement();
            return result(null, call != 0);
        }, () -> 0);
        assertEquals("int safe;\n      \n      \r\n      ", masked);
        assertEquals(3, calls.get());
        assertFalse(masked.contains("fake"));
    }

    @Test public void totalBudgetPreservesCompletedLinesAndMasksOnlyUnprocessedTail() {
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        String masked = CppLexicalSnapshot.codeOnly("int safe;\nint unprocessed;", (line, incoming, budget) -> {
            calls.incrementAndGet();
            now.set(CppLexicalSnapshot.SNAPSHOT_BUDGET_NANOS);
            return result(null, false);
        }, now::get);
        assertEquals("int safe;\n                ", masked);
        assertEquals(1, calls.get());
    }

    @Test public void exhaustedBudgetDoesNotAttemptRetryOrAdvanceWithIncompleteState() {
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        String masked = CppLexicalSnapshot.codeOnly("fake\nactual", (line, incoming, budget) -> {
            calls.incrementAndGet(); now.set(CppLexicalSnapshot.SNAPSHOT_BUDGET_NANOS);
            return result(() -> 99, true);
        }, now::get);
        assertEquals("    \n      ", masked);
        assertEquals(1, calls.get());
    }

    @Test public void subMillisecondRemainderStopsInsteadOfDisablingUpstreamTimeout() {
        AtomicInteger ticks = new AtomicInteger();
        String masked = CppLexicalSnapshot.codeOnly("actual", (line, incoming, budget) -> {
            throw new AssertionError("Less than 1ms must not turn the upstream limit into unlimited");
        }, () -> ticks.getAndIncrement() == 0 ? 0 : CppLexicalSnapshot.SNAPSHOT_BUDGET_NANOS - 1);
        assertEquals("      ", masked);
    }

    @Test public void coldRecoveryBudgetIsLargerButClampedToRemainingSnapshotTime() {
        AtomicLong now = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        String masked = CppLexicalSnapshot.codeOnly("actual", (line, incoming, budget) -> {
            if (calls.getAndIncrement() == 0) {
                assertEquals(200, budget.toMillis());
                now.set(CppLexicalSnapshot.SNAPSHOT_BUDGET_NANOS - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500));
                return result(null, true);
            }
            assertEquals(500, budget.toMillis());
            assertTrue(budget.toMillis() > 200 && budget.toMillis() <= 1000);
            assertTrue(budget.toNanos() <= CppLexicalSnapshot.SNAPSHOT_BUDGET_NANOS - now.get());
            return result(null, false);
        }, now::get);
        assertEquals("actual", masked);
        assertEquals(2, calls.get());
    }

    @Test public void cancellationBeforeScanNeverCallsTokenizer() {
        Thread.currentThread().interrupt();
        try {
            CppLexicalSnapshot.codeOnly("actual", (line, incoming, budget) -> {
                throw new AssertionError("Cancelled work must not tokenize");
            }, () -> 0);
            fail("Cancellation must propagate");
        } catch (CancellationException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
    }

    @Test public void cancellationAfterTimedOutCallNeverRetriesOrScansNextLine() {
        AtomicInteger calls = new AtomicInteger();
        try {
            CppLexicalSnapshot.codeOnly("fake\nactual", (line, incoming, budget) -> {
                calls.incrementAndGet(); Thread.currentThread().interrupt();
                return result(null, true);
            }, () -> 0);
            fail("Cancellation must propagate");
        } catch (CancellationException expected) { assertEquals(1, calls.get()); }
        finally { Thread.interrupted(); }
    }

    private static ITokenizeLineResult<IToken[]> result(IStateStack state, boolean stopped, IToken... tokens) {
        return new ITokenizeLineResult<IToken[]>() {
            @Override public IToken[] getTokens() { return tokens; }
            @Override public IStateStack getRuleStack() { return state; }
            @Override public boolean isStoppedEarly() { return stopped; }
        };
    }
    private static IToken nonCode(int start, int end) {
        return new IToken() {
            private int tokenStart = start;
            @Override public int getStartIndex() { return tokenStart; }
            @Override public void setStartIndex(int value) { tokenStart = value; }
            @Override public int getEndIndex() { return end; }
            @Override public java.util.List<String> getScopes() { return Collections.singletonList("string.quoted.double.cpp"); }
        };
    }
}
