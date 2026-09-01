package ai.ravenroot.extensions.amqp091;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AMQP's three derivation sites hold one severity and one posture.
 *
 * <p>AMQP is the connector the other five were unified <em>onto</em>: it was already severe and
 * already absorbed the refusal at the resolver. These are therefore regression pins — they exist so
 * that the behaviour the other five now share cannot drift away here unnoticed, which is exactly how
 * six copies came to disagree in the first place. What did change is the guard's collocation in
 * {@code EnvironmentAmqpConsumerPolicyResolver}: it moved out of the derivation and into an early
 * exit in {@code resolve}, matching the other five, and the last test below pins that it still bites.
 */
class AmqpEnvironmentKeyDerivationTest {
    private static final List<String> UNPAIRED_SURROGATES =
            List.of("\uD800", "\uD801", "\uDBFF", "\uDC00", "\uDFFF");

    @Test void profileDerivationRejectsSurrogates() {
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentAmqpProfileResolver.environmentVariableName(malformed, "p"));
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentAmqpProfileResolver.environmentVariableName("t", malformed));
        }
    }

    @Test void consumerPolicyDerivationRejectsSurrogates() {
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentAmqpConsumerPolicyResolver.variableName(malformed, "p"));
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentAmqpConsumerPolicyResolver.variableName("t", malformed));
        }
    }

    /**
     * The seam hands the identifier to the codec unmangled — no case folding, no normalization.
     *
     * <p><strong>This does not exercise substitution, and its previous name claimed it did.</strong>
     * {@code alpha-ñ} and {@code alpha-ø} are both well formed and both encodable, so
     * {@code getBytes(UTF_8)} never merges them — it collapses only input it cannot encode. The
     * assertion therefore held identically under the permissive encoder, and stayed green under
     * exactly the regression its name nominated while three of its siblings went red in the same run.
     * A name that promises a guard the body does not stand is worse than no test, because it is
     * counted as coverage.
     *
     * <p>The substitution property is covered where it can actually fail: {@code EnvironmentKeyCodecTest}
     * pins that every unpaired surrogate and the well-formed {@code "?"} share one permissive key, and
     * the {@code ...RejectsSurrogates} tests above pin the refusal at this connector's own seams.
     *
     * <p>What remains here is a real per-site property that nothing else covers. The codec test proves
     * the <em>codec</em> is byte-preserving; only a test at this seam can prove that <em>this</em>
     * resolver reaches it without folding case, stripping accents or normalizing first. The expected
     * key is written out literally instead of being recomputed through the codec, so a derivation that
     * pre-normalized would fail rather than agree with itself.
     */
    @Test void derivationPreservesBytesAndDoesNotFoldAccents() {
        assertEquals("RAVENROOT_AMQP091_PROFILE_616C7068612DC3B1_70",
                EnvironmentAmqpProfileResolver.environmentVariableName("alpha-ñ", "p"));
        assertEquals("RAVENROOT_AMQP091_PROFILE_616C7068612DC3B8_70",
                EnvironmentAmqpProfileResolver.environmentVariableName("alpha-ø", "p"));
    }

    @Test void unfilteredCredentialPathFailsClosed() {
        var resolver = new EnvironmentAmqpCredentialResolver(Map.of());
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentAmqpCredentialResolver.environmentVariableName(malformed));
            assertTrue(resolver.resolve(malformed).isEmpty());
        }
    }

    /** The guard survived the move: {@code resolve} still refuses a non-ASCII identity outright. */
    @Test void consumerPolicyGuardStillRejectsAfterMovingToAnEarlyExit() {
        var resolver = new EnvironmentAmqpConsumerPolicyResolver(Map.of());
        assertTrue(resolver.resolve("bad!id", "p").isEmpty());
        assertTrue(resolver.resolve("t", "bad!id").isEmpty());
        assertTrue(resolver.resolve(null, "p").isEmpty());
        assertTrue(resolver.resolve("t", null).isEmpty());
        assertTrue(resolver.resolve("-leading", "p").isEmpty());
        assertTrue(resolver.resolve("t", "a".repeat(65)).isEmpty());
        for (String malformed : UNPAIRED_SURROGATES) {
            assertTrue(resolver.resolve(malformed, "p").isEmpty());
        }
    }
}
