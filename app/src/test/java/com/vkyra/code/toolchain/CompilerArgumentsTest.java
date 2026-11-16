package com.vkyra.code.toolchain;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class CompilerArgumentsTest {
    @Test public void quotedPathsAndEmptyArgumentsRemainSingleWords() {
        assertEquals(Arrays.asList("-I/my source/include", "-DNAME=hello world", "", "literal space"),
                CompilerArguments.parse("-I'/my source/include' -DNAME=\"hello world\" '' literal\\ space"));
    }
    @Test public void shellCharactersStayLiteralAndQuotesAreEscaped() {
        assertEquals(Arrays.asList("$(touch", "file)", ";"), CompilerArguments.parse("$(touch file) ;"));
        assertEquals("'a'\\''b'", CompilerArguments.shellQuote("a'b"));
        assertEquals("'a b' 'x'", CompilerArguments.shellCommand(Arrays.asList("a b", "x")));
    }
    @Test public void quotedNonSpecialBackslashesArePreserved() {
        assertEquals(Arrays.asList("-DWIN=C:\\temp"), CompilerArguments.parse("\"-DWIN=C:\\temp\""));
    }
    @Test public void unfinishedQuoteIsReported() {
        try { CompilerArguments.parse("-I'unfinished"); fail(); } catch (IllegalArgumentException expected) { }
    }
}
