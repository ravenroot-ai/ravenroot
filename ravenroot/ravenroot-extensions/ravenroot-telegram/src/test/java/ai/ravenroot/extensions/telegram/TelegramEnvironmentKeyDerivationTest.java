package ai.ravenroot.extensions.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telegram's two derivation sites hold one severity and one posture.
 *
 * <p>Telegram was already severe; the fix here is the posture. {@code EnvironmentTelegramProfileResolver}
 * derived outside any {@code try}, so a malformed identifier would have propagated out of
 * {@code resolve} while the AMQP sites answered empty. The seam is called directly, past the ASCII
 * guard, which is unchanged.
 */
class TelegramEnvironmentKeyDerivationTest {
    private static final List<String> UNPAIRED_SURROGATES =
            List.of("\uD800", "\uD801", "\uDBFF", "\uDC00", "\uDFFF");

    @Test void profileDerivationRejectsSurrogates() {
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentTelegramProfileResolver.environmentVariableName(malformed, "p"));
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentTelegramProfileResolver.environmentVariableName("t", malformed));
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
        assertEquals("RAVENROOT_TELEGRAM_PROFILE_616C7068612DC3B1_70",
                EnvironmentTelegramProfileResolver.environmentVariableName("alpha-ñ", "p"));
        assertEquals("RAVENROOT_TELEGRAM_PROFILE_616C7068612DC3B8_70",
                EnvironmentTelegramProfileResolver.environmentVariableName("alpha-ø", "p"));
    }

    @Test void resolveFailsClosedRatherThanPropagating() {
        var resolver = new EnvironmentTelegramProfileResolver(Map.of());
        for (String malformed : UNPAIRED_SURROGATES) {
            assertTrue(resolver.resolve(malformed, "p").isEmpty());
            assertTrue(resolver.resolve("t", malformed).isEmpty());
        }
        assertTrue(resolver.resolve("bad!id", "p").isEmpty());
        assertTrue(resolver.resolve(null, "p").isEmpty());
    }

    @Test void unfilteredCredentialPathFailsClosed() {
        var resolver = new EnvironmentTelegramCredentialResolver(Map.of());
        for (String malformed : UNPAIRED_SURROGATES) {
            assertThrows(IllegalArgumentException.class,
                    () -> EnvironmentTelegramCredentialResolver.environmentVariableName(malformed));
            assertTrue(resolver.resolve(malformed).isEmpty());
        }
    }
}
