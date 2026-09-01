package ai.ravenroot.api.security;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The properties the single derivation must hold.
 *
 * <p>Every one of these runs on input the connectors' {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}} guard
 * would reject. That is deliberate and is the whole point: the measured divergence is invisible
 * from behind that guard, so a test that stayed behind it would pass on the defect. The guard is not
 * widened to let this input through — the codec is called directly, past it.
 */
class EnvironmentKeyCodecTest {

    /** Every unpaired surrogate {@code String.getBytes(UTF_8)} would have collapsed onto {@code 3F}. */
    private static final List<String> UNPAIRED_SURROGATES =
            List.of("\uD800", "\uD801", "\uDBFF", "\uDC00", "\uDC01", "\uDFFF");

    @Test void unpairedSurrogatesAreReportedRatherThanSubstituted() {
        for (String malformed : UNPAIRED_SURROGATES) {
            IllegalArgumentException raised = assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentKeyCodec.hex(malformed));
            assertTrue(raised.getMessage().contains("malformed UTF-16"), raised.getMessage());
        }
    }

    /**
     * The exact failure the severity choice exists to prevent, stated as the collision it would be.
     *
     * <p>Under the permissive encoding these six distinct identifiers all produce the same key
     * component. The substitute is <em>not</em> U+FFFD, as several javadocs in this tree used to say
     * — {@code String.getBytes(UTF_8)} replaces with a single {@code 0x3F}, an ASCII question mark.
     * That matters: it puts the well-formed identifier {@code "?"} inside the same collision class,
     * so the aliasing is not confined to malformed input. This test pins the premise itself, so that
     * if a future JDK changes the replacement the reasoning written on {@link EnvironmentKeyCodec}
     * fails loudly instead of quietly becoming fiction.
     */
    @Test void permissiveEncodingWouldHaveCollapsedThemAllOntoOneKey() {
        Set<String> permissive = new HashSet<>();
        for (String malformed : UNPAIRED_SURROGATES) {
            permissive.add(java.util.HexFormat.of().withUpperCase()
                    .formatHex(malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        assertEquals(Set.of("3F"), permissive,
                "the permissive encoding is many-to-one on unpaired surrogates");

        assertEquals("3F", java.util.HexFormat.of().withUpperCase()
                        .formatHex("?".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "and the collision class contains a well-formed identifier, not only malformed ones");

        assertNotEquals(EnvironmentKeyCodec.hex("?"), "");
        assertThrows(IllegalArgumentException.class, () -> EnvironmentKeyCodec.hex("\uD800"),
                "the strict codec separates them by refusing the malformed one outright");
    }

    @Test void nullIsRejectedAsAnIllegalArgumentLikeEveryOtherRefusal() {
        assertThrows(IllegalArgumentException.class, () -> EnvironmentKeyCodec.hex(null));
    }

    /** Pins the wire form, so the six sites agree on bytes and not merely on severity. */
    @Test void encodesToUpperCaseHexOfUtf8Bytes() {
        assertEquals("", EnvironmentKeyCodec.hex(""));
        assertEquals("7072696D617279", EnvironmentKeyCodec.hex("primary"));
        assertEquals("7461212D62", EnvironmentKeyCodec.hex("ta!-b"));
        assertEquals("C3B8", EnvironmentKeyCodec.hex("ø"));
        assertEquals("F09F9880", EnvironmentKeyCodec.hex("😀"));
    }

    /** A well-formed surrogate pair is not malformed input and must encode, not fail. */
    @Test void wellFormedSurrogatePairsEncode() {
        assertEquals("F0908080", EnvironmentKeyCodec.hex("𐀀"));
    }

    @Property(tries = 500)
    void distinctWellFormedIdentifiersNeverShareAKey(
            @ForAll("wellFormed") String left, @ForAll("wellFormed") String right) {
        Assume.that(!left.equals(right));
        assertNotEquals(EnvironmentKeyCodec.hex(left), EnvironmentKeyCodec.hex(right));
    }

    @Property(tries = 500)
    void concatenationIsUnambiguousBecauseEveryByteIsTwoCharacters(@ForAll("wellFormed") String value) {
        String key = EnvironmentKeyCodec.hex(value);
        assertEquals(0, key.length() % 2);
        assertEquals(value, new String(java.util.HexFormat.of().parseHex(key),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    @Provide Arbitrary<String> wellFormed() {
        Arbitrary<Integer> scalar = Arbitraries.oneOf(
                Arbitraries.integers().between(1, 0xD7FF),
                Arbitraries.integers().between(0xE000, 0x10FFFF));
        return scalar.list().ofMinSize(1).ofMaxSize(12).map(codePoints -> {
            StringBuilder value = new StringBuilder();
            codePoints.forEach(value::appendCodePoint);
            return value.toString();
        });
    }
}
