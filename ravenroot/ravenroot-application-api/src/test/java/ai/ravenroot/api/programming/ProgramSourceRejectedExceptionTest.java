package ai.ravenroot.api.programming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delimiting of compiler text, asserted where it lives rather than through a route.
 *
 * <p>The text this type carries is produced by a compiler about code its author wrote, so it is
 * theirs to read — but it is still text this product did not author, and every consumer of it would
 * otherwise have to remember the same four rules. They are enforced once, in a total function, on the
 * only route to the field.</p>
 */
class ProgramSourceRejectedExceptionTest {

    /** The measured GraalPy diagnostic for the motivating failure, carried through unchanged. */
    @Test
    void aRealPythonDiagnosticSurvivesIntact() {
        String measured = "IndentationError: expected an indented block after function definition "
                + "on line 1 (artifact-1, line 2)";

        var rejected = new ProgramSourceRejectedException(measured, 2, 0);

        assertEquals(measured, rejected.diagnostic(),
                "delimiting must not mangle the ordinary case; it exists for the hostile one");
        assertEquals(2, rejected.line());
        assertEquals(0, rejected.column());
    }

    /**
     * Graal.js appends a caret display to a syntax error: the offending source line, a newline, and a
     * caret. Everything from the first line break onward is dropped.
     *
     * <p>That trailing display is the only part of a real diagnostic that echoes raw source, so
     * dropping it removes the whole class of hostile characters at the source instead of filtering
     * them afterwards — and loses nothing that names the cause.</p>
     */
    @Test
    void onlyTheFirstLineSurvives() {
        String measured = "SyntaxError: artifact-1:1:31 Expected ident but found ;\n"
                + "(function (request) { return { ; })\n"
                + "                               ^\n";

        assertEquals("SyntaxError: artifact-1:1:31 Expected ident but found ;",
                ProgramSourceRejectedException.delimit(measured));
    }

    /**
     * The regression this suite failed to catch the first time, and the reason it failed.
     *
     * <p>The previous version of this test asserted the delimiting rule against a string the test
     * itself invented. It therefore proved the rule and said nothing about whether the rule was a good
     * one — and it was not: it replaced {@code <}, {@code >} and {@code &} with {@code ?}, and
     * <b>Graal.js names the offending token inside the first line</b>, so the replacement landed on
     * exactly the character the diagnostic exists to identify.</p>
     *
     * <p>Every input below is a real first line, measured by driving Graal.js 25.2.4 through the real
     * worker. An invented string could not have found this, which is why these are quoted rather than
     * composed.</p>
     */
    @Test
    void aTokenTheCompilerNamedIsNeverDestroyed() {
        // source: function (request) => { return 1; }
        assertEquals("SyntaxError: artifact-1:1:20 Expected { but found =>",
                ProgramSourceRejectedException.delimit(
                        "SyntaxError: artifact-1:1:20 Expected { but found =>"));
        // source: function (request) { return <div>hi</div>; }
        assertEquals("SyntaxError: artifact-1:1:29 Expected an operand but found <",
                ProgramSourceRejectedException.delimit(
                        "SyntaxError: artifact-1:1:29 Expected an operand but found <"));
        // source: function (request) { if (a &&& b) return 1; }
        assertEquals("SyntaxError: artifact-1:1:30 Expected an operand but found &",
                ProgramSourceRejectedException.delimit(
                        "SyntaxError: artifact-1:1:30 Expected an operand but found &"));
    }

    /**
     * A line-oriented sink cannot be made to see a second record, and no sink can be made to see a tag.
     *
     * <p>The guarantee is narrower than the one it replaces, and is exactly the HTML tag-open state: a
     * {@code <} followed by an ASCII letter, {@code /}, {@code !} or {@code ?} is separated from what
     * follows it. Nothing is removed, so the assertion is about what a parser could build out of the
     * text rather than about which characters are present in it.</p>
     */
    @Test
    void controlCharactersVanishAndNoTagCanBeOpened() {
        // Every control character below is written as an ESCAPE, never as the raw byte. A raw
        // control byte in a source file is what scripts/check_no_raw_control_bytes.py exists to
        // refuse -- it got in here once, by a tooling round trip rather than by hand, and this
        // file is the worst place in the repository to carry one: it is the file that proves
        // control characters are sanitised.
        //
        // The escape is deliberately not spelled out in this comment, and the reason is narrower
        // than "it would not compile". Java resolves codepoint escapes during lexing, in comments as
        // well as in code, but what that costs depends on which escape: a line terminator breaks the
        // build loudly, a malformed sequence is refused outright, and the one this file actually
        // uses would compile fine -- the lexer would simply materialise the control character INSIDE
        // the comment. That last case is the dangerous one and the quiet one: the byte is then real
        // in the parsed text, and a tooling round trip that rewrites the file from that form puts it
        // back, which is exactly how it got in here. Compiling therefore proves nothing; not writing
        // it is what keeps it out.
        String hostile = "SyntaxError: \u0007bad\ttoken <script>alert(1)</script> <!-- <?php & a < b";

        String delimited = ProgramSourceRejectedException.delimit(hostile);

        for (int index = 0; index < delimited.length(); index++) {
            char character = delimited.charAt(index);
            assertTrue(character >= 0x20 && character != 0x7f,
                    "a character below 0x20 survived at " + index + " of: " + delimited);
        }
        for (int index = delimited.indexOf('<'); index >= 0; index = delimited.indexOf('<', index + 1)) {
            if (index + 1 >= delimited.length()) {
                continue;
            }
            char next = delimited.charAt(index + 1);
            assertFalse(next == '!' || next == '/' || next == '?'
                            || (next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z'),
                    "a tag could be opened at " + index + " of: " + delimited);
        }
        assertEquals("SyntaxError: bad token < script>alert(1)< /script> < !-- < ?php & a < b", delimited);
    }

    /** The bound is reused rather than reinvented, and it is a bound on what is stored. */
    @Test
    void theDiagnosticIsBoundedInLength() {
        String flood = "SyntaxError: " + "x".repeat(10_000);

        String delimited = ProgramSourceRejectedException.delimit(flood);

        assertEquals(ProgramSourceRejectedException.MAX_DIAGNOSTIC_LENGTH, delimited.length());
        assertTrue(delimited.startsWith("SyntaxError: "), "the head is what names the cause");
    }

    /**
     * A refusal with nothing readable in it says so, and says it in product-authored words.
     *
     * <p>Deliberately not "the request was rejected as invalid": that sentence is the defect. This one
     * states what is actually known — the source was refused and the runtime gave no reason.</p>
     */
    @Test
    void aRefusalWithNoTextStillSaysWhatIsKnown() {
        assertEquals(ProgramSourceRejectedException.UNSTATED_DIAGNOSTIC,
                ProgramSourceRejectedException.delimit(null));
        assertEquals(ProgramSourceRejectedException.UNSTATED_DIAGNOSTIC,
                ProgramSourceRejectedException.delimit("   \n ignored"));
    }

    /** A negative position is not a position. */
    @Test
    void negativePositionsCollapseToUnknown() {
        var rejected = new ProgramSourceRejectedException("SyntaxError: invalid syntax", -4, -1);

        assertEquals(0, rejected.line());
        assertEquals(0, rejected.column());
    }
}
