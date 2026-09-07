package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.persistence.AgentBudgetVector;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AgentAuthorityBudgetFingerprintTest {
    @Test void canonicalVectorsHaveDistinctVersionedDomains() {
        var policy = baseline();
        // Independently encoded using big-endian Python struct, UTF-8 lengths and lexical scope order.
        assertEquals("a17375d5f0fb5c845d6774a2afda431f319e61961e4c6b8a9de3cee3358b278f", policy.policyFingerprint());
        assertEquals("bb8f83b47f7cc91d68c03ed760a88957c6627929f4a053497ace53b44a0ebde5", policy.rateCardFingerprint());
        assertNotEquals(policy.policyFingerprint(), policy.rateCardFingerprint());
    }

    @Test void scopeOrderIsCanonicalButConfiguredInternalRootPresenceIsSignificant() {
        var policy = baseline();
        var reordered = copy(copy(policy, 11, new LinkedHashSet<>(List.of("data-b", "data-a"))),
                12, new LinkedHashSet<>(List.of("tool:use", "runtime:delegate")));
        assertEquals(policy.policyFingerprint(), reordered.policyFingerprint());
        var withRoot = copy(policy, 12, Set.of("tool:use", "runtime:delegate", "runtime:root"));
        assertNotEquals(policy.policyFingerprint(), withRoot.policyFingerprint());
        assertEquals(policy.rateCardFingerprint(), withRoot.rateCardFingerprint());
    }

    @Test void everyCoveredPolicyInputChangesOnlyItsPolicyDomain() {
        var p = baseline();
        for (var changed : List.of(copy(p, 2, "policy-v2"), copy(p, 5, p.rootLifetime().plusSeconds(1)),
                copy(p, 5, p.rootLifetime().plusNanos(1)), copy(p, 7, 101L), copy(p, 8, 21L),
                copy(p, 11, Set.of("data-a")), copy(p, 12, Set.of("tool:use")))) {
            assertNotEquals(p.policyFingerprint(), changed.policyFingerprint());
            assertEquals(p.rateCardFingerprint(), changed.rateCardFingerprint());
        }
        long[] values = {10, 1000, 1000, 10000, 10000, 20, 4, 4, 4};
        for (int i = 0; i < values.length; i++) {
            long[] changed = values.clone(); changed[i]++;
            var maxima = new AgentBudgetVector(changed[0], changed[1], changed[2], changed[3], changed[4],
                    changed[5], changed[6], changed[7], changed[8]);
            assertNotEquals(p.policyFingerprint(), copy(p, 6, maxima).policyFingerprint(), "maximum " + i);
            assertEquals(p.rateCardFingerprint(), copy(p, 6, maxima).rateCardFingerprint());
        }
    }

    @Test void everyEconomicInputChangesOnlyItsRateDomainAndBootIsDiagnostic() {
        var p = baseline();
        for (var changed : List.of(copy(p, 3, "rate-v2"), copy(p, 4, "EUR"), copy(p, 9, 2L), copy(p, 10, 4L))) {
            assertNotEquals(p.rateCardFingerprint(), changed.rateCardFingerprint());
            assertEquals(p.policyFingerprint(), changed.policyFingerprint());
        }
        for (var changed : List.of(copy(p, 0, "other-runtime"), copy(p, 1, 99L))) {
            assertEquals(p.policyFingerprint(), changed.policyFingerprint());
            assertEquals(p.rateCardFingerprint(), changed.rateCardFingerprint());
        }
    }

    @Test void deadlinesUseExactSecondsAndNanosWithCauseFreeRepresentationalRefusal() {
        var p = baseline();
        assertEquals(Instant.EPOCH.plusSeconds(3600).plusNanos(123), p.rootDeadlineAt(Instant.EPOCH));
        assertEquals(Instant.MIN.plus(p.rootLifetime()), p.rootDeadlineAt(Instant.MIN));
        for (var invalid : List.of(p, copy(p, 5, Duration.ofSeconds(Long.MAX_VALUE)))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> invalid.rootDeadlineAt(Instant.MAX));
            assertNull(failure.getCause());
            assertEquals("rootLifetime cannot form a finite deadline (RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS)", failure.getMessage());
        }
        var nanos = copy(p, 5, Duration.ofNanos(1));
        assertEquals(Instant.MAX, nanos.rootDeadlineAt(Instant.MAX.minusNanos(1)));
    }

    private static AgentAuthorityBudgetPolicy baseline() {
        return new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofSeconds(3600, 123), new AgentBudgetVector(10, 1000, 1000, 10000, 10000, 20, 4, 4, 4),
                100, 20, 1, 3, Set.of("data-a", "data-b"), Set.of("runtime:delegate", "tool:use"));
    }

    // Record-field mutation is confined to tests and never used by the canonical encoder.
    static AgentAuthorityBudgetPolicy copy(AgentAuthorityBudgetPolicy p, int index, Object value) {
        Object[] fields = {p.runtimeInstanceId(), p.bootEpoch(), p.policyVersion(), p.rateCardVersion(), p.currency(),
                p.rootLifetime(), p.rootMaxima(), p.maximumInputTokensPerTurn(), p.maximumOutputTokensPerTurn(),
                p.inputTokenRateMicros(), p.outputTokenRateMicros(), p.dataScopes(), p.authorityScopes()};
        fields[index] = value;
        try {
            return (AgentAuthorityBudgetPolicy) AgentAuthorityBudgetPolicy.class.getDeclaredConstructors()[0].newInstance(fields);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
