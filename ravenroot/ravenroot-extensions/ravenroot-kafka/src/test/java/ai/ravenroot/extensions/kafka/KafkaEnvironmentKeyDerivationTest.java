package ai.ravenroot.extensions.kafka;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kafka's three derivation sites hold one severity and one posture.
 *
 * <p>Kafka was already on the severe side of the divergence, so severity here is a regression pin.
 * The <em>posture</em> now matches it: both profile resolvers previously derived outside any
 * {@code try}, so a malformed identifier would have propagated out of {@code resolve} while the AMQP
 * sites answered empty for the same input. The seam is called directly, past
 * the {@code [A-Za-z0-9][A-Za-z0-9_-]{0,63}} guard, which is left exactly as it was.
 */
class KafkaEnvironmentKeyDerivationTest {
    private static final List<String> UNPAIRED_SURROGATES =
            List.of("\uD800", "\uD801", "\uDBFF", "\uDC00", "\uDFFF");

    @Test void profileDerivationRejectsSurrogates() {
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentKafkaProfileResolver.environmentVariableName(malformed, "p"));
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentKafkaProfileResolver.environmentVariableName("t", malformed));
        }
    }

    @Test void consumerProfileDerivationRejectsSurrogates() {
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentKafkaConsumerProfileResolver.environmentVariableName(malformed, "p"));
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentKafkaConsumerProfileResolver.environmentVariableName("t", malformed));
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
        assertEquals("RAVENROOT_KAFKA_PROFILE_616C7068612DC3B1_70",
                EnvironmentKafkaProfileResolver.environmentVariableName("alpha-ñ", "p"));
        assertEquals("RAVENROOT_KAFKA_PROFILE_616C7068612DC3B8_70",
                EnvironmentKafkaProfileResolver.environmentVariableName("alpha-ø", "p"));
    }

    /** Posture: the refusal is absorbed at the resolver, never propagated out of {@code resolve}. */
    @Test void resolveFailsClosedRatherThanPropagating() {
        var profiles = new EnvironmentKafkaProfileResolver(Map.of());
        var consumers = new EnvironmentKafkaConsumerProfileResolver(Map.of());
        for (String malformed : UNPAIRED_SURROGATES) {
            assertTrue(profiles.resolve(malformed, "p").isEmpty());
            assertTrue(consumers.resolve(malformed, "p").isEmpty());
        }
        assertTrue(profiles.resolve("bad!id", "p").isEmpty());
        assertTrue(consumers.resolve(null, "p").isEmpty());
    }

    /** The unfiltered credential path, where the posture is observable end to end today. */
    @Test void unfilteredCredentialPathFailsClosed() {
        var resolver = new EnvironmentKafkaCredentialResolver(Map.of());
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentKafkaCredentialResolver.environmentVariableName(malformed));
            assertTrue(resolver.resolve(malformed).isEmpty());
        }
    }
}
