package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.persistence.AgentBudgetVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentAuthorityBudgetPolicyTest {
    private static final AgentBudgetVector MAXIMA = new AgentBudgetVector(10, 10, 10, 10, 10, 10, 10, 10, 10);

    @Test
    void directScopesPermitSlashAndTheExistingTwoHundredFiftySixCharacterBoundary() {
        String longScope = "a" + "x".repeat(255);
        var policy = policy("runtime-a", "policy-v1", "rate-v1",
                Set.of("tenant/data", longScope), Set.of("runtime:delegate/team"));

        assertEquals(Set.of("tenant/data", longScope), policy.dataScopes());
        assertEquals(Set.of("runtime:delegate/team"), policy.authorityScopes());
    }

    @Test
    void nullScopeSetsBecomeEmptyAndAnExplicitEmptyAuthoritySetRemainsEmpty() {
        var nullScopes = policy("runtime-a", "policy-v1", "rate-v1", null, null);
        var emptyAuthority = policy("runtime-a", "policy-v1", "rate-v1", Set.of(), Set.of());

        assertEquals(Set.of(), nullScopes.dataScopes());
        assertEquals(Set.of(), nullScopes.authorityScopes());
        assertEquals(Set.of(), emptyAuthority.authorityScopes());
    }

    @Test
    void scopeSetsAreDefensivelySnapshotted() {
        var data = new HashSet<>(Set.of("tenant/data"));
        var authority = new HashSet<>(Set.of("runtime:delegate"));
        var policy = policy("runtime-a", "policy-v1", "rate-v1", data, authority);

        data.add("tenant/other");
        authority.clear();

        assertEquals(Set.of("tenant/data"), policy.dataScopes());
        assertEquals(Set.of("runtime:delegate"), policy.authorityScopes());
    }

    @ParameterizedTest
    @MethodSource("invalidIdentities")
    void directIdentityAndVersionTokensRejectSlashAndOversizedValues(
            String expectedField, String runtime, String policyVersion, String rateCardVersion) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> policy(runtime, policyVersion, rateCardVersion, Set.of(), Set.of()));

        assertEquals(expectedField + " must be an identity token of 1..128 characters", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void invalidOversizedAndExcessiveScopesHaveFieldNamedCauseFreeDiagnostics() {
        assertInvalidScope("dataScopes", Set.of("bad scope"), Set.of());
        assertInvalidScope("dataScopes", Set.of("a".repeat(257)), Set.of());
        Set<String> maximum = IntStream.range(0, 256)
                .mapToObj(index -> "scope/" + index)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(256, policy("runtime-a", "policy-v1", "rate-v1", maximum, Set.of())
                .dataScopes().size());
        Set<String> tooMany = IntStream.range(0, 257)
                .mapToObj(index -> "scope/" + index)
                .collect(Collectors.toUnmodifiableSet());
        IllegalArgumentException countFailure = assertThrows(IllegalArgumentException.class,
                () -> policy("runtime-a", "policy-v1", "rate-v1", tooMany, Set.of()));
        assertEquals("dataScopes must contain at most 256 unique scope tokens", countFailure.getMessage());
        assertNull(countFailure.getCause());
    }

    @Test
    void nullScopeElementsAreRejectedBeforeImmutableCopying() {
        var data = new HashSet<String>();
        data.add(null);
        IllegalArgumentException dataFailure = assertThrows(IllegalArgumentException.class,
                () -> policy("runtime-a", "policy-v1", "rate-v1", data, Set.of()));
        assertEquals("dataScopes contains an invalid scope token", dataFailure.getMessage());
        assertNull(dataFailure.getCause());

        var authority = new HashSet<String>();
        authority.add(null);
        IllegalArgumentException authorityFailure = assertThrows(IllegalArgumentException.class,
                () -> policy("runtime-a", "policy-v1", "rate-v1", Set.of(), authority));
        assertEquals("authorityScopes contains an invalid scope token", authorityFailure.getMessage());
        assertNull(authorityFailure.getCause());
    }

    @Test
    void existingNumericAndCurrencyConstructorRefusalsRemainInForce() {
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", -1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1), MAXIMA, 10, 10, 1, 1, Set.of(), Set.of()));
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ZERO, MAXIMA, 10, 10, 1, 1, Set.of(), Set.of()));
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1), null, 10, 10, 1, 1, Set.of(), Set.of()));
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1), MAXIMA, 0, 10, 1, 1, Set.of(), Set.of()));
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1), MAXIMA, 10, 0, 1, 1, Set.of(), Set.of()));
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1), MAXIMA, 10, 10, -1, 1, Set.of(), Set.of()));
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "USD",
                Duration.ofMinutes(1), MAXIMA, 10, 10, 1, -1, Set.of(), Set.of()));
        assertInvalidPolicy(() -> new AgentAuthorityBudgetPolicy("runtime-a", 1, "policy-v1", "rate-v1", "usd",
                Duration.ofMinutes(1), MAXIMA, 10, 10, 1, 1, Set.of(), Set.of()));
    }

    private static void assertInvalidScope(String field, Set<String> dataScopes, Set<String> authorityScopes) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> policy("runtime-a", "policy-v1", "rate-v1", dataScopes, authorityScopes));
        assertEquals(field + " contains an invalid scope token", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void assertInvalidPolicy(Runnable construction) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, construction::run);
        assertEquals("agent authority budget policy is invalid", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static AgentAuthorityBudgetPolicy policy(String runtime, String policyVersion, String rateCardVersion,
                                                     Set<String> dataScopes, Set<String> authorityScopes) {
        return new AgentAuthorityBudgetPolicy(runtime, 1, policyVersion, rateCardVersion, "USD",
                Duration.ofMinutes(1), MAXIMA, 10, 10, 1, 1, dataScopes, authorityScopes);
    }

    private static Stream<Arguments> invalidIdentities() {
        return Stream.of(
                Arguments.of("runtimeInstanceId", "runtime/a", "policy-v1", "rate-v1"),
                Arguments.of("policyVersion", "runtime-a", "policy/v1", "rate-v1"),
                Arguments.of("rateCardVersion", "runtime-a", "policy-v1", "rate/v1"),
                Arguments.of("runtimeInstanceId", "a".repeat(129), "policy-v1", "rate-v1"),
                Arguments.of("policyVersion", "runtime-a", "a".repeat(129), "rate-v1"),
                Arguments.of("rateCardVersion", "runtime-a", "policy-v1", "a".repeat(129)));
    }
}
