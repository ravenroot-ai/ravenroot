package ai.ravenroot.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A malformed reference must not resolve to the secret provisioned for the
 * well-formed reference {@code "?"}.
 *
 * <h2>The defect, stated in bytes</h2>
 * <p>{@code String.getBytes(UTF_8)} does not fail on an unpaired UTF-16 surrogate, it substitutes —
 * and the substitute is a single byte {@code 0x3F}, an ASCII question mark, not U+FFFD. So the
 * substituting derivation sends every unpaired surrogate to the key {@code RAVENROOT_CREDENTIAL_3F}, which
 * is <em>also</em> the key of the perfectly well-formed, schema-valid, operator-provisionable
 * reference {@code "?"}. Two distinct references, one secret. Unlike the six strict connector sites, the
 * reference here can originate in graph content — the {@code credentialRef} of an HTTP or LLM node —
 * so the collision is reachable by an author of a graph rather than only by a mistyped operator
 * profile.
 *
 * <h2>Why {@code 0x3F} is spelled out below and never recomputed</h2>
 * <p>{@code RAVENROOT_CREDENTIAL_3F} is written as a literal, transcribed from the measurement in
 * the connector checks, and never obtained by calling {@link EnvironmentCredentialResolver#environmentVariableName}
 * on {@code "?"} and comparing the result to itself. A test that recomputes its own expectation
 * through the code under test asserts only that the code agrees with itself: it would stay green if
 * the substitution byte changed, and it would stay green if the whole derivation were replaced by a
 * constant. The literal is the independent half of the assertion, and it is what makes the two
 * halves of the collision class — {@code "?"} and an unpaired surrogate — visibly meet at the same
 * string.
 */
class EnvironmentCredentialResolverMalformedReferenceTest {

    /**
     * The environment variable the reference {@code "?"} legitimately owns. Hand-written:
     * {@code '?'} is U+003F, one UTF-8 byte {@code 0x3F}, rendered {@code "3F"} by the derivation's
     * upper-case hex. This is also the key every substituted unpaired surrogate lands on.
     */
    private static final String QUESTION_MARK_KEY = "RAVENROOT_CREDENTIAL_3F";

    /** The five unpaired surrogates measured on JDK 21; each encoded to a single {@code 3F}. */
    private static final List<String> UNPAIRED_SURROGATES =
            List.of("\uD800", "\uD801", "\uDBFF", "\uDC00", "\uDFFF");

    /**
     * The well-formed half of the collision class, pinned to its literal key. The reference
     * {@code "?"} is encodable and keeps its key; strict encoding only prevents malformed references
     * from arriving there too.
     */
    @Test
    void theWellFormedQuestionMarkReferenceKeepsItsOwnKey() {
        assertEquals(QUESTION_MARK_KEY, EnvironmentCredentialResolver.environmentVariableName("?"),
                "the reference \"?\" must derive to the single-byte key 0x3F");
    }

    /**
     * The confidentiality statement, end to end and with a real secret in the map: one variable is
     * provisioned, for {@code "?"}, and a reference nobody provisioned anything for must not read it.
     * Substitution makes each surrogate resolve to {@code the-question-mark-secret}.
     */
    @Test
    void aMalformedReferenceDoesNotResolveToTheSecretProvisionedForQuestionMark() {
        var resolver = new EnvironmentCredentialResolver(
                Map.of(QUESTION_MARK_KEY, "the-question-mark-secret"));

        try (var owner = resolver.get("?").orElseThrow(
                () -> new AssertionError("the well-formed reference \"?\" must still resolve"))) {
            assertEquals("the-question-mark-secret", new String(owner.copy()));
        }

        for (String malformed : UNPAIRED_SURROGATES) {
            assertTrue(resolver.get(malformed).isEmpty(),
                    () -> "the malformed reference U+" + Integer.toHexString(malformed.charAt(0)).toUpperCase()
                            + " resolved to a secret; it can only be the one provisioned for \"?\", "
                            + "because " + QUESTION_MARK_KEY + " is the only variable in the environment");
            assertNotEquals(malformed, "?", "sanity: the two halves must be distinct references");
        }
    }

    /**
     * The derivation itself now reports instead of substituting, so a malformed reference has no key
     * at all — which is also why two references malformed in two different ways can no longer share
     * one. Substitution instead makes every call below return {@link #QUESTION_MARK_KEY}.
     */
    @Test
    void aMalformedReferenceHasNoEnvironmentVariableNameAtAll() {
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentCredentialResolver.environmentVariableName(malformed),
                    () -> "U+" + Integer.toHexString(malformed.charAt(0)).toUpperCase()
                            + " still produced a key, and a substituted key is somebody else's key");
        }
    }

    /**
     * {@link EnvironmentCredentialResolver#get} stays total: the refusal raised by the derivation is
     * absorbed and answered with an empty {@code Optional}, like every other reason this method
     * declines. A malformed reference must not become an exception on a graph-reachable path.
     */
    @Test
    void getAbsorbsTheRefusalRatherThanPropagatingIt() {
        var resolver = new EnvironmentCredentialResolver(Map.of());
        for (String malformed : UNPAIRED_SURROGATES) {
            assertTrue(resolver.get(malformed).isEmpty(),
                    () -> "get must answer empty, not throw, for a malformed reference");
        }
    }

    /**
     * The per-site property no codec-level test can see: this resolver hands the codec the reference
     * exactly as it received it — no {@code strip}, no case fold, no character substitution of its
     * own. A seam in this repository previously folded case and replaced characters before
     * calling the codec, which silently reintroduces many-to-one upstream of a strictly injective
     * encoder; the strictness of the codec cannot detect that, only a test at the site can. The
     * expectations are literal single-byte hex — {@code 'a'}=61, {@code 'A'}=41, {@code ' '}=20 — so
     * a normalising seam inserted here later fails this test rather than passing it by agreeing with
     * itself.
     */
    @Test
    void theReferenceReachesTheDerivationUnmodified() {
        assertEquals("RAVENROOT_CREDENTIAL_61", EnvironmentCredentialResolver.environmentVariableName("a"));
        assertEquals("RAVENROOT_CREDENTIAL_41", EnvironmentCredentialResolver.environmentVariableName("A"),
                "case must not be folded before the derivation");
        assertEquals("RAVENROOT_CREDENTIAL_2061", EnvironmentCredentialResolver.environmentVariableName(" a"),
                "a leading space must survive: trimming first is itself many-to-one");
        assertEquals("RAVENROOT_CREDENTIAL_6120", EnvironmentCredentialResolver.environmentVariableName("a "),
                "a trailing space must survive: trimming first is itself many-to-one");
    }
}
