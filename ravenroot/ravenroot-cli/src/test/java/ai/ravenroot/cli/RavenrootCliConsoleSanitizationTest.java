package ai.ravenroot.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The CLI error stream is the operator's own console, not a
 * machine-parsed JSON-lines format, so {@code RavenrootCli.sanitizeForConsole} is a different tool
 * from {@code ai.ravenroot.server.audit.JsonStrings} -- a visible replacement rather than an escape
 * sequence, since nothing here re-parses the output and a literal {@code \n} would just be confusing
 * text on a human's terminal. What both tools must share is the one property that matters: a raw
 * newline in a document-derived value must never reach the stream, because that is what would let a
 * value print what looks like a second, unrelated diagnostic line.
 */
class RavenrootCliConsoleSanitizationTest {
    @Test
    void nullBecomesEmptyString() {
        assertEquals("", RavenrootCli.sanitizeForConsole(null));
    }

    @Test
    void ordinaryTextIsUnchanged() {
        assertEquals("hello world!", RavenrootCli.sanitizeForConsole("hello world!"));
    }

    /**
     * The claim that matters: a value that could forge an extra console line must not contain a real
     * newline anywhere in the sanitized result, and the same for carriage return and an arbitrary
     * sub-0x20 control character -- built from its code point rather than typed literally, so this
     * source file never carries a raw control byte.
     */
    @Test
    void newlineCarriageReturnAndControlCharactersAreNeutralizedNotEscaped() {
        String canary = "before" + '\n' + "after" + '\r' + "tab" + '\t' + "ctrl" + (char) 1 + "end";

        String sanitized = RavenrootCli.sanitizeForConsole(canary);

        assertFalse(sanitized.chars().anyMatch(character -> character < 0x20),
                () -> "a raw control character (including newline/carriage return) survived: " + sanitized);
        // Not JSON-escaped: a literal backslash-n would itself be confusing on an operator's console
        // for a value that was never going to be machine-parsed.
        assertFalse(sanitized.contains("\\n"), () -> "this is console text, not JSON: " + sanitized);
        assertEquals("before?after?tab?ctrl?end", sanitized);
    }
}
