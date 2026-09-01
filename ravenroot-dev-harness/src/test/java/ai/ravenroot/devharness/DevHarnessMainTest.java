package ai.ravenroot.devharness;

import ai.ravenroot.server.security.AuthenticationConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two claims of {@link DevHarnessMain} that rested only on the code being read correctly.
 *
 * <p>Both were untested in the first revision, and both are load-bearing in a way that is easy to
 * miss: one is <b>condition 2 of H28</b> (never exposed on a reachable network), which the module's
 * own pom describes as a fact enforced by the build — a description that was not true while nothing
 * checked it. The other is the id restriction that makes the id-to-variable derivation injective,
 * which avoids the identifier-collision defect in {@code EnvironmentCredentialResolver}.
 */
class DevHarnessMainTest {

    // -----------------------------------------------------------------------------------------
    // Condition 2: the listener is loopback, and no environment can move it
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("every bind address an operator could set is replaced by loopback")
    void everyBindAddressAnOperatorCouldSetIsReplacedByLoopback() {
        String loopback = InetAddress.getLoopbackAddress().getHostAddress();
        // The realistic ways someone exposes a server: the wildcards, a LAN address, a hostname,
        // and the IPv6 forms. None of them may survive.
        for (String declared : List.of("0.0.0.0", "::", "[::]", "192.168.1.50", "10.0.0.7",
                "0.0.0.0%0", "example.internal", "127.0.0.1", "")) {
            Map<String, String> forced =
                    DevHarnessMain.loopbackOnly(Map.of("RAVENROOT_BIND_ADDRESS", declared));
            assertEquals(loopback, forced.get("RAVENROOT_BIND_ADDRESS"),
                    "RAVENROOT_BIND_ADDRESS=" + declared + " was not forced to loopback");
        }
    }

    @Test
    @DisplayName("an absent bind address is still forced, not merely left alone")
    void anAbsentBindAddressIsStillForced() {
        // RavenrootServerMain defaults an absent variable to 127.0.0.1 anyway, so "leave it alone"
        // would happen to work today. It would stop working the moment that default changed, and
        // nothing here would have noticed -- so the value is written rather than relied upon.
        Map<String, String> forced = DevHarnessMain.loopbackOnly(Map.of());
        assertEquals(InetAddress.getLoopbackAddress().getHostAddress(),
                forced.get("RAVENROOT_BIND_ADDRESS"));
    }

    @Test
    @DisplayName("the caller's environment is copied, never mutated")
    void theCallersEnvironmentIsCopiedNeverMutated() {
        // System.getenv() is unmodifiable, so a mutating implementation would throw at startup
        // rather than misbehave -- but a caller passing a mutable map must not have it rewritten
        // underneath, and the copy is also what keeps the socket and the authenticator reading the
        // same value.
        Map<String, String> original = new HashMap<>();
        original.put("RAVENROOT_BIND_ADDRESS", "0.0.0.0");
        original.put("RAVENROOT_PORT", "9999");

        Map<String, String> forced = DevHarnessMain.loopbackOnly(original);

        assertEquals("0.0.0.0", original.get("RAVENROOT_BIND_ADDRESS"), "the caller's map was mutated");
        assertEquals("9999", forced.get("RAVENROOT_PORT"), "unrelated entries must survive");
        assertThrows(UnsupportedOperationException.class,
                () -> forced.put("RAVENROOT_BIND_ADDRESS", "0.0.0.0"),
                "the returned map must not be writable by a later caller");
    }

    @Test
    @DisplayName("the authenticator is decided from the same loopback address the socket uses")
    void theAuthenticatorIsDecidedFromTheSameAddressTheSocketUses() {
        // This is the decay this guard exists to prevent, and it is not hypothetical: if the socket
        // were forced to loopback while AuthenticationConfiguration still read the raw environment,
        // a deployment declaring 0.0.0.0 would take the network-facing branch -- which REFUSES to
        // start without RAVENROOT_AUTH_MODE. The two must be computed from one value.
        var configuration = AuthenticationConfiguration.fromEnvironment(
                DevHarnessMain.loopbackOnly(Map.of("RAVENROOT_BIND_ADDRESS", "0.0.0.0")), 18080);

        assertTrue(configuration.bindAddress().getAddress().isLoopbackAddress(),
                "the authenticator was decided against a non-loopback address");
        // On loopback with no declared mode the product chooses `disabled`; asserted so that a
        // future change to that default is visible here rather than only in the bench's behaviour.
        assertEquals("disabled", configuration.mode());
    }

    @Test
    @DisplayName("a raw 0.0.0.0 environment would have taken the network branch, so the guard is not vacuous")
    void theGuardIsNotVacuous() {
        // ANTI-FALSE-GREEN. Without this, every assertion above would pass just as well if
        // AuthenticationConfiguration ignored the bind address entirely. It does not: the same
        // environment WITHOUT the forcing refuses to start.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> AuthenticationConfiguration.fromEnvironment(
                        Map.of("RAVENROOT_BIND_ADDRESS", "0.0.0.0"), 18080));
        assertTrue(refused.getMessage().contains("RAVENROOT_AUTH_MODE"));
    }

    // -----------------------------------------------------------------------------------------
    // The id restriction, and the injectivity that rests on it
    // -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("an admissible id is returned unchanged")
    void anAdmissibleIdIsReturnedUnchanged() {
        for (String id : List.of("ollama-local", "a", "0", "qwen3", "lm-studio-2", "x--y", "a1-b2")) {
            assertEquals(id, DevHarnessMain.requireAdmissibleId(id));
        }
    }

    @Test
    @DisplayName("every character class outside the alphabet is refused, at any position")
    void everyCharacterClassOutsideTheAlphabetIsRefused() {
        // Underscore first, because it is the one that would break injectivity rather than merely
        // look wrong: `a_b` and `a-b` would derive the same variable segment.
        for (String id : List.of("a_b", "_", "ollama_local",
                "A", "Ollama-Local", "oLlama",          // uppercase collides with the derivation too
                "a.b", "a b", "a/b", "a:b", "a+b", "猫", "a\tb",
                "-leading", "", "-")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DevHarnessMain.requireAdmissibleId(id), "accepted '" + id + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> DevHarnessMain.requireAdmissibleId(null));
    }

    @Test
    @DisplayName("an illegal character is caught wherever it sits, not only at the end")
    void anIllegalCharacterIsCaughtWhereverItSits() {
        // The regression test for the structure this method used to have: a flag reassigned inside
        // a loop whose condition short-circuits reads correctly, but decays into "only the last
        // character decides" after one plausible edit. Here the bad character is first, middle and
        // last in turn, so that decay fails this test instead of shipping.
        for (String id : List.of("_ab", "a_b", "ab_")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DevHarnessMain.requireAdmissibleId(id), "accepted '" + id + "'");
        }
    }

    @Test
    @DisplayName("the id-to-variable derivation is injective over every admissible id up to length three")
    void theDerivationIsInjectiveOverEveryAdmissibleIdUpToLengthThree() {
        // Exhaustive rather than sampled. The alphabet is small enough to enumerate, and injectivity
        // is precisely the property a sampled test would miss: a collision needs two specific ids.
        // Three characters is where hyphens can appear in every position relative to each other.
        List<Character> alphabet = List.of('a', 'b', '0', '9', '-');
        Map<String, String> seen = new LinkedHashMap<>();
        Set<String> admitted = new HashSet<>();

        for (String id : enumerate(alphabet, 3)) {
            try {
                DevHarnessMain.requireAdmissibleId(id);
            } catch (IllegalArgumentException refused) {
                continue;
            }
            admitted.add(id);
            String segment = DevHarnessMain.variableSegment(id);
            String previous = seen.put(segment, id);
            assertEquals(null, previous, () -> "collision: '" + id + "' and '" + previous
                    + "' both derive " + segment);
        }

        // ANTI-VACUITY: an injectivity proof over an empty set is not a proof, so the size is
        // pinned. Re-derive it before touching it -- the alphabet above has 5 characters, of which
        // 4 are non-hyphen, and the only rejection reachable here is the leading hyphen:
        //
        //   length 1:  4              = 4     (the non-hyphen characters)
        //   length 2:  4 * 5          = 20    (non-hyphen first, anything second)
        //   length 3:  4 * 5 * 5      = 100
        //                              -----
        //                               124
        //
        // As a cross-check the rejected ids are 1 + 5 + 25 = 31, and 124 + 31 = 155 = 5 + 25 + 125,
        // which is every string of length 1..3 over the alphabet.
        //
        // The correct total is not "5 + 20 + 100 = 125" -- the first term
        // and in the sum, sitting directly above a correct assertEquals(124, ...). That is worse
        // than no comment: the whole reason it exists is to let the next reader re-derive the
        // number, and it would have led them to "correct" a healthy test into failing.
        assertEquals(124, admitted.size(),
                "the admitted set changed shape; re-derive this number before adjusting it");
        assertEquals(admitted.size(), seen.size());
    }

    @Test
    @DisplayName("the derivation is what the README documents, and nothing is lost in it")
    void theDerivationIsWhatTheReadmeDocuments() {
        assertEquals("OLLAMA_LOCAL", DevHarnessMain.variableSegment("ollama-local"));
        assertEquals("QWEN3", DevHarnessMain.variableSegment("qwen3"));
        // Hyphen and underscore must not be able to meet: the underscore side is unreachable,
        // because requireAdmissibleId refuses it. That is the whole argument, so it is stated as a
        // pair rather than left implicit.
        assertEquals("A_B", DevHarnessMain.variableSegment("a-b"));
        assertThrows(IllegalArgumentException.class, () -> DevHarnessMain.requireAdmissibleId("a_b"));
        assertNotEquals(DevHarnessMain.variableSegment("a-b"), DevHarnessMain.variableSegment("ab"));
        assertFalse(DevHarnessMain.variableSegment("ollama-local").contains("-"));
    }

    /** Every string of length 1..{@code maxLength} over {@code alphabet}. */
    private static List<String> enumerate(List<Character> alphabet, int maxLength) {
        var all = new java.util.ArrayList<String>();
        List<String> previous = List.of("");
        for (int length = 1; length <= maxLength; length++) {
            var current = new java.util.ArrayList<String>();
            for (String prefix : previous) {
                for (char character : alphabet) {
                    current.add(prefix + character);
                }
            }
            all.addAll(current);
            previous = current;
        }
        return all;
    }
}
