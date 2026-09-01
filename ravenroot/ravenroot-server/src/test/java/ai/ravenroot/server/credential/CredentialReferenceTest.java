package ai.ravenroot.server.credential;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of a minted reference and the scan {@link CredentialAdmission} is built on.
 *
 * <p>These are properties of a static utility, so they would normally not earn their own file. They
 * do here because the ownership rule depends on them and would fail <b>open</b> if any of them broke:
 * a scan that missed an occurrence admits a graph naming someone else's credential, and a
 * {@link CredentialReference#isMinted} that answered {@code false} for a real reference would send it
 * to the operator's environment resolver instead of the store.</p>
 */
class CredentialReferenceTest {

    /**
     * <b>A mint is not predictable, and not derived from anything.</b>
     *
     * <p>A thousand is not a statistical claim about 128 bits — it could not be — it is a guard
     * against the failure that actually happens: a generator seeded per call, or one that returns a
     * constant because somebody replaced {@code SecureRandom} with a stub.</p>
     */
    @Test
    void mintingATousandReferencesProducesAThousandReferences() {
        Set<String> minted = new HashSet<>();
        for (int index = 0; index < 1000; index++) {
            String reference = CredentialReference.mint();
            assertTrue(CredentialReference.isMinted(reference), reference);
            minted.add(reference);
        }
        assertEquals(1000, minted.size(), "a repeated mint would silently overwrite a credential");
    }

    /**
     * <b>The two namespaces are disjoint, and this is the assertion that says so.</b>
     *
     * <p>Everything else in this feature leans on it: the resolver chain is not a precedence rule
     * because of it, and {@link CredentialAdmission} can leave operator references alone because of
     * it. The near-misses matter more than the obvious cases — one character short, one too long, a
     * non-hex digit, the prefix in the middle rather than at the start.</p>
     */
    @Test
    void onlyTheMintedShapeIsRecognised() {
        String valid = CredentialReference.PREFIX + "0123456789abcdef0123456789abcdef";
        assertTrue(CredentialReference.isMinted(valid));

        for (String near : java.util.List.of(
                "openai-main",
                "",
                CredentialReference.PREFIX,
                CredentialReference.PREFIX + "0123456789abcdef0123456789abcde",
                CredentialReference.PREFIX + "0123456789abcdef0123456789abcdef0",
                CredentialReference.PREFIX + "0123456789ABCDEF0123456789ABCDEF",
                CredentialReference.PREFIX + "0123456789abcdef0123456789abcdeg",
                "x" + CredentialReference.PREFIX + "0123456789abcdef0123456789abcdef",
                "  " + valid)) {
            assertFalse(CredentialReference.isMinted(near),
                    () -> "'" + near + "' is not a minted reference and must not be treated as one");
        }
        assertFalse(CredentialReference.isMinted(null));
    }

    /**
     * <b>Uppercase hex is not a valid reference but IS found by the scan, and that asymmetry is
     * deliberate.</b>
     *
     * <p>{@link CredentialReference#isMinted} is strict because it decides which store answers.
     * {@link CredentialReference#foundIn} is lenient because it decides what to <em>refuse</em>, and a
     * document round-tripped through something that changed case must not become a way past the
     * ownership check. Lenient in the refusing direction, strict in the resolving one.</p>
     */
    @Test
    void theScanFindsAReferenceWhateverCaseTheDocumentCarriesItIn() {
        String canonical = CredentialReference.PREFIX + "0123456789abcdef0123456789abcdef";
        String shouted = CredentialReference.PREFIX + "0123456789ABCDEF0123456789ABCDEF";

        assertEquals(Set.of(canonical),
                CredentialReference.foundIn(shouted.getBytes(StandardCharsets.UTF_8)),
                "an uppercased reference must still be recognised, and normalised to the stored form");
    }

    /**
     * <b>The scan finds a reference anywhere in the document, in any element or attribute.</b>
     *
     * <p>This is why the scan is over bytes rather than over parsed node properties: the cases below
     * are all places a reference can legitimately or illegitimately sit, and a parser-based check
     * would need each of them enumerated in advance. The last two — a description and a prompt — are
     * the deliberate over-match, and it is the safe direction: refusing a graph that quotes somebody
     * else's reference costs an edit, admitting one that uses it costs a credential.</p>
     */
    @Test
    void everyOccurrenceIsFoundWhereverItSits() {
        String first = CredentialReference.PREFIX + "11111111111111111111111111111111";
        String second = CredentialReference.PREFIX + "22222222222222222222222222222222";
        String document = """
                <graphml>
                  <node id="a"><data key="credentialRef">FIRST</data></node>
                  <node id="b" someAttribute="SECOND"/>
                  <node id="c"><data key="description">as agreed, use FIRST</data></node>
                </graphml>
                """.replace("FIRST", first).replace("SECOND", second);

        assertEquals(Set.of(first, second),
                CredentialReference.foundIn(document.getBytes(StandardCharsets.UTF_8)),
                "the scan must not depend on where in the document a reference appears");
    }

    /**
     * <b>Invalid UTF-8 does not hide a reference.</b>
     *
     * <p>The reason {@code foundIn} decodes as ISO-8859-1 rather than UTF-8, stated in its Javadoc and
     * checked here rather than asserted: a malformed sequence decoded as UTF-8 becomes a replacement
     * character, and a replacement that consumed the byte starting a match would be a way to smuggle a
     * reference past the ownership check. Latin-1 is total — every byte maps to exactly one character
     * — so no such consumption is possible.</p>
     */
    @Test
    void aMalformedByteBeforeAReferenceDoesNotHideIt() {
        String reference = CredentialReference.PREFIX + "33333333333333333333333333333333";
        byte[] prefix = {(byte) 0xC3, (byte) 0x28, (byte) 0xF0, (byte) 0x9F};
        byte[] tail = reference.getBytes(StandardCharsets.US_ASCII);
        byte[] document = new byte[prefix.length + tail.length];
        System.arraycopy(prefix, 0, document, 0, prefix.length);
        System.arraycopy(tail, 0, document, prefix.length, tail.length);

        assertEquals(Set.of(reference), CredentialReference.foundIn(document),
                "a truncated multi-byte sequence must not swallow the start of a reference");
    }

    /** An empty or absent document names nothing, and must not throw on the admission path. */
    @Test
    void anEmptyDocumentNamesNothing() {
        assertTrue(CredentialReference.foundIn(null).isEmpty());
        assertTrue(CredentialReference.foundIn(new byte[0]).isEmpty());
        assertTrue(CredentialReference.foundIn("no references here".getBytes(StandardCharsets.UTF_8))
                .isEmpty());
    }
}
