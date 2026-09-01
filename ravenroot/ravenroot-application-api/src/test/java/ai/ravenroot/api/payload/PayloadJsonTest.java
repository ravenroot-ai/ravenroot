package ai.ravenroot.api.payload;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The grammar and the budgets, asserted where they are implemented rather than through a transport. */
class PayloadJsonTest {
    private static final PayloadLimits SMALL = new PayloadLimits(4096, 4, 3, 20, 8, 4);

    /**
     * {@code PayloadJson.read} takes bytes only: the {@code String} overload it used to
     * carry checked every internal budget except {@code maxEncodedBytes}, which let a document that
     * satisfied every per-element budget still land several times over the encoded-size ceiling (see
     * {@link #aThousandOneKibibyteStringsSatisfyEveryInternalBudgetButNotTheEncodedByteCeiling()}). This
     * pair of overloads keeps every String literal in this file readable as a literal, while routing
     * through the one production entry point that actually enforces the ceiling — a test calling the
     * removed overload directly is exactly the mistake the single byte-oriented entry point prevents.
     */
    private static PayloadValue read(String json, PayloadLimits limits) {
        return PayloadJson.read(json.getBytes(StandardCharsets.UTF_8), limits);
    }

    private static PayloadValue read(byte[] utf8, PayloadLimits limits) {
        return PayloadJson.read(utf8, limits);
    }

    @Test
    void encodingIsCanonicalSoTheSameValueAlwaysProducesTheSameBytes() {
        var first = new LinkedHashMap<String, PayloadValue>();
        first.put("b", PayloadValue.of(1L));
        first.put("a", PayloadValue.of(2L));
        var second = new LinkedHashMap<String, PayloadValue>();
        second.put("a", PayloadValue.of(2L));
        second.put("b", PayloadValue.of(1L));

        assertEquals(PayloadJson.write(PayloadValue.map(first)), PayloadJson.write(PayloadValue.map(second)));
        assertEquals("{\"a\":2,\"b\":1}", PayloadJson.write(PayloadValue.map(first)));
    }

    @Test
    void integralAndFractionalNumbersKeepDistinctIdentities() {
        assertInstanceOf(PayloadValue.IntegerValue.class, read("7", PayloadLimits.DEFAULTS));
        assertInstanceOf(PayloadValue.DecimalValue.class, read("7.0", PayloadLimits.DEFAULTS));
        assertInstanceOf(PayloadValue.DecimalValue.class, read("7e2", PayloadLimits.DEFAULTS));
    }

    @Test
    void everyKindRoundTripsThroughTheEncoding() {
        for (PayloadValue value : List.of(PayloadValue.NULL, PayloadValue.of(true), PayloadValue.of(-9L),
                PayloadValue.of(2.5d), PayloadValue.of("a \" b \\ c \n d"),
                PayloadValue.list(PayloadValue.of(1L), PayloadValue.NULL),
                PayloadValue.map(Map.of("k", PayloadValue.list())))) {
            assertEquals(value, read(PayloadJson.write(value), PayloadLimits.DEFAULTS),
                    "round trip failed for " + PayloadJson.write(value));
        }
    }

    @Test
    void theJavaProjectionIsTheInteriorRepresentationTheEngineAlreadyCarries() {
        PayloadValue value = PayloadValue.map(Map.of("n", PayloadValue.of(3L), "s", PayloadValue.of("x")));
        Object projected = value.toJava();
        assertInstanceOf(Map.class, projected);
        assertEquals(3L, ((Map<?, ?>) projected).get("n"));
        assertEquals("x", ((Map<?, ?>) projected).get("s"));
        assertEquals(value, PayloadValue.fromJava(projected, PayloadLimits.DEFAULTS));
    }

    @Test
    void aDuplicateKeyIsRefusedRatherThanResolvedByLastWins() {
        var rejection = assertThrows(PayloadException.class,
                () -> read("{\"a\":1,\"a\":2}", PayloadLimits.DEFAULTS));
        assertEquals(PayloadException.Reason.DUPLICATE_KEY, rejection.reason());
    }

    @Test
    void theGrammarAdmitsNoConvenienceExtensions() {
        for (String hostile : List.of("{'a':1}", "{a:1}", "[1,]", "{\"a\":1,}", "[1] [2]", "01",
                "+1", ".5", "1.", "NaN", "Infinity", "{\"a\":1} trailing", "\"unterminated",
                "[1,2", "{\"a\"}", "// comment\n1")) {
            assertThrows(PayloadException.class, () -> read(hostile, PayloadLimits.DEFAULTS),
                    "accepted a document outside the grammar: " + hostile);
        }
    }

    @Test
    void anUnescapedControlCharacterInAStringIsRefused() {
        String withRawControlCharacter = "\"a" + (char) 1 + "b\"";
        assertThrows(PayloadException.class,
                () -> read(withRawControlCharacter, PayloadLimits.DEFAULTS));
        // The same character is accepted when escaped, so the rule is about the encoding rather than
        // about the value: a payload may legitimately carry a control character.
        assertEquals(PayloadValue.of("a" + (char) 1 + "b"),
                read("\"a\\u0001b\"", PayloadLimits.DEFAULTS));
    }

    @Test
    void anIntegralLiteralOutsideSixtyFourBitsIsRefusedRatherThanSilentlyDowngraded() {
        var rejection = assertThrows(PayloadException.class,
                () -> read("92233720368547758080", PayloadLimits.DEFAULTS));
        assertEquals(PayloadException.Reason.UNSUPPORTED_TYPE, rejection.reason());
    }

    @Test
    void everyBudgetIsEnforcedDuringTheParse() {
        assertEquals(PayloadException.Reason.DEPTH_LIMIT_EXCEEDED,
                assertThrows(PayloadException.class, () -> read("[[[[[1]]]]]", SMALL)).reason());
        assertEquals(PayloadException.Reason.COLLECTION_LIMIT_EXCEEDED,
                assertThrows(PayloadException.class, () -> read("[1,2,3,4]", SMALL)).reason());
        assertEquals(PayloadException.Reason.TEXT_TOO_LONG,
                assertThrows(PayloadException.class, () -> read("\"123456789\"", SMALL)).reason());
        assertEquals(PayloadException.Reason.KEY_TOO_LONG,
                assertThrows(PayloadException.class, () -> read("{\"abcde\":1}", SMALL)).reason());
        assertEquals(PayloadException.Reason.VALUE_COUNT_LIMIT_EXCEEDED,
                assertThrows(PayloadException.class,
                        () -> read("[[[1,2,3],[1,2,3],[1,2,3]],[[1,2,3],[1,2,3],[1,2,3]],"
                                + "[[1,2,3],[1,2,3],[1,2,3]]]", SMALL)).reason());
        assertEquals(PayloadException.Reason.TOO_LARGE,
                assertThrows(PayloadException.class,
                        () -> read("[1]".repeat(4096).getBytes(StandardCharsets.UTF_8), SMALL))
                        .reason());
    }

    /**
     * The exact measured shape: a document built entirely from values that individually
     * satisfy every per-element {@link PayloadLimits#DEFAULTS} budget, yet whose canonical encoding
     * runs to several times the encoded-size ceiling. Internal budgets do not imply the encoded-size
     * one, and previously that gap was exploitable through {@code PayloadJson}'s since-removed
     * {@code read(String, PayloadLimits)} overload, which enforced every budget below except this one.
     *
     * <p>1000 elements of 1024 ASCII characters each, inside one array:</p>
     * <ul>
     *   <li>1001 values counted (the array plus its 1000 elements) against a 10 000 ceiling;</li>
     *   <li>a 1000-element collection against a 1000 ceiling;</li>
     *   <li>each 1024-character string against a 32 768-character ceiling;</li>
     *   <li>and a 1 027 001-byte encoding against a 262 144-byte ceiling — 3.92 times over.</li>
     * </ul>
     *
     * <p>Now that {@code read} takes only {@code byte[]}, there is exactly one path this document can
     * take, and this test pins that {@code PayloadJson.read(byte[], PayloadLimits)} refuses it. A
     * regression that reintroduced a {@code String} overload sharing the old gap would not fail this
     * test by itself — it would need to skip the byte[] overload entirely. That asymmetry was closed
     * by removing the choice rather than by teaching the removed overload to measure.</p>
     */
    @Test
    void aThousandOneKibibyteStringsSatisfyEveryInternalBudgetButNotTheEncodedByteCeiling() {
        var document = new StringBuilder("[");
        for (int index = 0; index < 1000; index++) {
            if (index > 0) {
                document.append(',');
            }
            document.append('"').append("x".repeat(1024)).append('"');
        }
        document.append(']');
        byte[] encoded = document.toString().getBytes(StandardCharsets.UTF_8);

        assertEquals(1_027_001, encoded.length,
                "the measured fixture drifted from the documented numbers");
        assertTrue(encoded.length > PayloadLimits.DEFAULTS.maxEncodedBytes(),
                "the fixture must exceed the encoded-byte ceiling to exercise anything");

        var rejection = assertThrows(PayloadException.class,
                () -> PayloadJson.read(encoded, PayloadLimits.DEFAULTS));
        assertEquals(PayloadException.Reason.TOO_LARGE, rejection.reason());

        // The assertion above alone would not establish the claim in this test's name. The encoded-byte
        // check runs first, so the parse never happens and nothing in it has been shown to satisfy any
        // internal budget -- a fixture that also violated the depth or value-count ceiling would produce
        // exactly the same TOO_LARGE. Reading the same bytes again under DEFAULTS with only
        // maxEncodedBytes raised is what proves the rest: every other budget is unchanged, so an
        // acceptance here can only mean the document was inside all of them, and the encoded size was
        // the single reason it was refused.
        var onlyTheByteCeilingRaised = new PayloadLimits(
                2 * 1024 * 1024,
                PayloadLimits.DEFAULTS.maxDepth(),
                PayloadLimits.DEFAULTS.maxCollectionSize(),
                PayloadLimits.DEFAULTS.maxValueCount(),
                PayloadLimits.DEFAULTS.maxTextLength(),
                PayloadLimits.DEFAULTS.maxKeyLength());
        var accepted = assertInstanceOf(PayloadValue.ListValue.class,
                PayloadJson.read(encoded, onlyTheByteCeilingRaised));
        assertEquals(1000, accepted.values().size(),
                "the fixture must be inside every budget except the encoded-byte ceiling");
    }

    @Test
    void theSameBudgetsApplyToAValueBuiltInMemoryWithNoDocumentToCount() {
        assertEquals(PayloadException.Reason.DEPTH_LIMIT_EXCEEDED,
                assertThrows(PayloadException.class,
                        () -> PayloadValue.fromJava(List.of(List.of(List.of(List.of(List.of("x"))))), SMALL))
                        .reason());
        assertEquals(PayloadException.Reason.UNSUPPORTED_TYPE,
                assertThrows(PayloadException.class,
                        () -> PayloadValue.fromJava(new Object(), PayloadLimits.DEFAULTS)).reason());
        assertEquals(PayloadException.Reason.UNSUPPORTED_TYPE,
                assertThrows(PayloadException.class,
                        () -> PayloadValue.fromJava(Map.of(1, "keyed by a number"), PayloadLimits.DEFAULTS))
                        .reason());
    }

    @Test
    void aRejectionCarriesTheDetailInTheChannelTheCallerCannotSee() {
        var rejection = assertThrows(PayloadException.class,
                () -> read("{\"secret-key\":1,\"secret-key\":2}", PayloadLimits.DEFAULTS));
        assertFalse(rejection.getMessage().contains("secret-key"),
                "the public message carried submitted content: " + rejection.getMessage());
        assertEquals("secret-key", rejection.diagnosticDetail().get("key"));
        assertTrue(rejection.incidentId().matches("[0-9a-f]{16}"), rejection.incidentId());
    }

    @Test
    void twoRejectionsAreDistinguishableByIncident() {
        var first = assertThrows(PayloadException.class, () -> read("{", PayloadLimits.DEFAULTS));
        var second = assertThrows(PayloadException.class, () -> read("{", PayloadLimits.DEFAULTS));
        assertFalse(first.incidentId().equals(second.incidentId()));
    }

    /**
     * {@code readEscape} accepts any four hex digits, so a lone surrogate decodes successfully and an
     * embedded caller can hand one to {@code fromJava} directly. Emitting it raw produced a string
     * that is not valid UTF-16, so the strict writer was the one component able to emit a document its
     * own reader rejects — visible as {@code ?} once the bytes are encoded.
     */
    @Test
    void aLoneSurrogateIsEscapedSoTheWriterCannotEmitInvalidUtf8() {
        for (String source : new String[]{"\"\\ud800\"", "\"\\udc00\"", "\"a\\ud800b\""}) {
            String encoded = PayloadJson.write(read(source, PayloadLimits.DEFAULTS));

            assertFalse(encoded.chars().anyMatch(character -> Character.isSurrogate((char) character)),
                    "a lone surrogate was emitted raw: " + encoded);
            assertEquals(encoded, new String(encoded.getBytes(StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8), "the encoded form does not survive UTF-8: " + encoded);
            assertEquals(read(source, PayloadLimits.DEFAULTS),
                    read(encoded, PayloadLimits.DEFAULTS),
                    "escaping the surrogate changed the value: " + encoded);
        }
    }

    /** A well-formed pair must retain its exact valid UTF-8 encoding. */
    @Test
    void aWellFormedSurrogatePairIsStillEmittedRaw() {
        String encoded = PayloadJson.write(read("\"\\ud83d\\ude00\"", PayloadLimits.DEFAULTS));

        assertEquals("\"\uD83D\uDE00\"", encoded);
        assertEquals(encoded, new String(encoded.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
