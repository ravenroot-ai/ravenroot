package ai.ravenroot.cli.remote;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MinimalJson#write} is the write side {@code RemoteBackend#result} needs to hand the
 * {@code payload} sub-value it parsed out of a response body back to the caller as JSON text. These
 * tests exercise it directly, package-private access, the same way {@link MinimalJson#parse} itself
 * is only ever tested through its callers elsewhere -- except this one new method has no other test
 * yet, so it gets one of its own.
 */
class MinimalJsonTest {

    @Test
    void writesScalarsAndNull() {
        assertEquals("null", MinimalJson.write(null));
        assertEquals("true", MinimalJson.write(Boolean.TRUE));
        assertEquals("false", MinimalJson.write(Boolean.FALSE));
        assertEquals("42", MinimalJson.write(42L));
        assertEquals("3.5", MinimalJson.write(3.5d));
    }

    @Test
    void escapesStringsIncludingControlCharacters() {
        assertEquals("\"plain\"", MinimalJson.write("plain"));
        assertEquals("\"a\\\"b\\\\c\"", MinimalJson.write("a\"b\\c"));
        assertEquals("\"line\\nbreak\"", MinimalJson.write("line\nbreak"));
        assertEquals("\"tab\\ttab\"", MinimalJson.write("tab\ttab"));
        assertEquals("\"cr\\rreturn\"", MinimalJson.write("cr\rreturn"));

        // A control character with no dedicated shorthand escape (here: BEL, code point 7) falls back
        // to the generic four-hex-digit form. Both the input and the expected output are built from
        // char/codepoint arithmetic rather than a literal escape sequence typed into this source file,
        // deliberately, to keep this test unambiguous about what byte sequence it is checking for.
        char bellCharacter = (char) 7;
        String input = "bell" + bellCharacter + "end";
        String backslash = String.valueOf((char) 92);
        String expected = "\"bell" + backslash + "u0007end\"";
        assertEquals(expected, MinimalJson.write(input));
    }

    @Test
    void writesArraysAndObjectsCompactly() {
        assertEquals("[1,2,3]", MinimalJson.write(List.of(1L, 2L, 3L)));

        var object = new LinkedHashMap<String, Object>();
        object.put("a", 1L);
        object.put("b", List.of("x", "y"));
        assertEquals("{\"a\":1,\"b\":[\"x\",\"y\"]}", MinimalJson.write(object));
    }

    @Test
    void roundTripsThroughParseAndWrite() {
        String original = "{\"status\":\"COMPLETED\",\"visitedNodes\":[\"start\",\"end\"],\"degraded\":false}";
        Object parsed = MinimalJson.parse(original);
        Object reparsed = MinimalJson.parse(MinimalJson.write(parsed));
        assertEquals(parsed.toString(), reparsed.toString());
    }
}
