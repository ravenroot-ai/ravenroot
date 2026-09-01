package ai.ravenroot.server.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one escaping implementation every JSON-lines sink shares. */
class JsonStringsTest {
    @Test
    void nullBecomesEmptyString() {
        assertEquals("", JsonStrings.escape(null));
    }

    @Test
    void theSixNamedEscapesAreUsedRatherThanTheGenericFallback() {
        assertEquals("\\\\", JsonStrings.escape("\\"));
        assertEquals("\\\"", JsonStrings.escape("\""));
        assertEquals("\\n", JsonStrings.escape("\n"));
        assertEquals("\\r", JsonStrings.escape("\r"));
        assertEquals("\\t", JsonStrings.escape("\t"));
        assertEquals("\\b", JsonStrings.escape("\b"));
        assertEquals("\\f", JsonStrings.escape("\f"));
    }

    /**
     * The gap the previous, narrower copies all shared: every character below {@code 0x20} that is
     * not one of the six named escapes must still become valid JSON, not survive as a raw byte.
     */
    @Test
    void everySubHexTwentyCharacterNotNamedAboveGetsTheGenericEscape() {
        for (char character = 0; character < 0x20; character++) {
            if ("\n\r\t\b\f".indexOf(character) >= 0) {
                continue;
            }
            char current = character;
            String escaped = JsonStrings.escape(String.valueOf(current));
            assertEquals(String.format("\\u%04x", (int) current), escaped,
                    () -> "character 0x" + Integer.toHexString(current) + " was not generically escaped");
        }
    }

    @Test
    void charactersAtAndAboveSpaceAreNeverEscaped() {
        assertEquals("hello world!", JsonStrings.escape("hello world!"));
    }

    /**
     * Proved directly against the shared implementation: a
     * value containing the canary must produce a result with no real newline, tab or sub-0x20
     * character in it anywhere -- not merely that the escaped substrings are present. The control
     * character is built from its code point rather than typed literally, so the source file itself
     * never carries a raw control byte.
     */
    @Test
    void aCanaryContainingTabNewlineAndAControlCharacterLeavesNoRawOccurrenceBehind() {
        String canary = "tab" + '\t' + "newline" + '\n' + "ctrl" + (char) 1 + "end";

        String escaped = JsonStrings.escape(canary);

        assertFalse(escaped.chars().anyMatch(character -> character < 0x20),
                () -> "a raw control character survived escaping: " + escaped);
        assertTrue(escaped.contains("\\t"));
        assertTrue(escaped.contains("\\n"));
        assertTrue(escaped.contains("\\u0001"));
    }
}
