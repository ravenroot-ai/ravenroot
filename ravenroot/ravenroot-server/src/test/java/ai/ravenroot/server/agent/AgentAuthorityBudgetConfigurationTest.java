package ai.ravenroot.server.agent;

import ai.ravenroot.core.security.nodepackage.AgentAuthorityBudgetPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAuthorityBudgetConfigurationTest {
    @Test
    void shippedDefaultsAreFinitePinnedAndUseDistinctBootEpochs() {
        var first = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of());

        assertEquals("ravenroot-server", first.runtimeInstanceId());
        assertEquals("builtin-conservative-v1", first.rateCardVersion());
        assertEquals("USD", first.currency());
        assertEquals(10, first.inputTokenRateMicros());
        assertEquals(30, first.outputTokenRateMicros());
        assertTrue(first.rootMaxima().turns() > 0);
        assertEquals(Set.of("runtime:delegate"), first.authorityScopes());
        assertTrue(first.bootEpoch() >= 0);
    }

    @Test
    void absentAndBlankNumericValuesUseTheSameDefaults() {
        AgentAuthorityBudgetPolicy absent = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of());
        Map<String, String> blanks = numericNames()
                .collect(Collectors.toUnmodifiableMap(Function.identity(), ignored -> " \t "));
        AgentAuthorityBudgetPolicy blank = AgentAuthorityBudgetConfiguration.fromEnvironment(blanks);

        assertSameConfiguredValues(absent, blank);
    }

    @Test
    void numericValuesRetainAsciiAndUnicodeWhitespaceStripping() {
        var policy = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AGENT_MAX_TURNS", " \u200317 \u2003"));

        assertEquals(17, policy.rootMaxima().turns());
    }

    @ParameterizedTest
    @MethodSource("numericNames")
    void malformedAndOverflowingNumbersHaveCauseFreeSettingOnlyDiagnostics(String name) {
        assertInvalidInteger(name, "operator-secret-not-a-number");
        assertInvalidInteger(name, "9223372036854775808");
    }

    @ParameterizedTest
    @MethodSource("positiveNumericNames")
    void positiveBudgetsRejectZeroAndNegativeValues(String name) {
        assertNull(assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, "0"))).getCause());
        assertNull(assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, "-1"))).getCause());
    }

    @ParameterizedTest
    @MethodSource("rateNames")
    void ratesAcceptZeroAndRejectNegativeValues(String name) {
        AgentAuthorityBudgetPolicy policy = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, "0"));
        assertEquals(0, name.contains("INPUT")
                ? policy.inputTokenRateMicros() : policy.outputTokenRateMicros());
        assertNull(assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, "-1"))).getCause());
    }

    @Test
    void identityAndVersionTokensAreTrimmedButNeverPermitSlash() {
        var policy = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AGENT_RUNTIME_INSTANCE", " runtime_1:blue ",
                "RAVENROOT_AGENT_POLICY_VERSION", " policy.v2 ",
                "RAVENROOT_AGENT_RATE_CARD_VERSION", " rate-v3 "));

        assertEquals("runtime_1:blue", policy.runtimeInstanceId());
        assertEquals("policy.v2", policy.policyVersion());
        assertEquals("rate-v3", policy.rateCardVersion());
    }

    @ParameterizedTest
    @MethodSource("identityNames")
    void slashAndOversizedIdentityTokensAreRejectedWithNamedDiagnostics(String name) {
        assertInvalidIdentity(name, "tenant/runtime");
        assertInvalidIdentity(name, "a".repeat(129));
    }

    @Test
    @ResourceLock("java.util.Locale")
    void currencyNormalizationUsesTheRootLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("MIX", AgentAuthorityBudgetConfiguration.fromEnvironment(
                    Map.of("RAVENROOT_AGENT_COST_CURRENCY", "mix")).currency());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void invalidCurrencyHasASettingOnlyDiagnostic() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(
                        Map.of("RAVENROOT_AGENT_COST_CURRENCY", "operator-secret")));
        assertEquals("RAVENROOT_AGENT_COST_CURRENCY must be a three-letter currency code",
                failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(!failure.getMessage().contains("operator-secret"));
    }

    @Test
    void authorityScopeAbsenceDefaultsToDelegationWhileExplicitBlankDisablesIt() {
        assertEquals(Set.of("runtime:delegate"), AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of())
                .authorityScopes());
        assertEquals(Set.of(), AgentAuthorityBudgetConfiguration.fromEnvironment(
                Map.of("RAVENROOT_AGENT_AUTHORITY_SCOPES", " \t ")).authorityScopes());
        assertEquals(Set.of(), AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of()).dataScopes());
        assertEquals(Set.of(), AgentAuthorityBudgetConfiguration.fromEnvironment(
                Map.of("RAVENROOT_AGENT_DATA_SCOPES", " \t ")).dataScopes());
    }

    @Test
    void scopesPermitSlashStripElementsAndCollapseDuplicates() {
        var policy = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AGENT_DATA_SCOPES", " tenant/data ,tenant/data,project:a-b ",
                "RAVENROOT_AGENT_AUTHORITY_SCOPES", " runtime:delegate/team "));

        assertEquals(Set.of("tenant/data", "project:a-b"), policy.dataScopes());
        assertEquals(Set.of("runtime:delegate/team"), policy.authorityScopes());
    }

    @Test
    void environmentScopesRemainBoundedAndHaveSettingOnlyDiagnostics() {
        String name = "RAVENROOT_AGENT_DATA_SCOPES";
        assertEquals(Set.of("a".repeat(128)), AgentAuthorityBudgetConfiguration.fromEnvironment(
                Map.of(name, "a".repeat(128))).dataScopes());
        assertInvalidScopes(name, "a".repeat(129));
        assertInvalidScopes(name, "valid,bad scope");
        assertInvalidScopes(name, "valid,,also-valid");

        String maximum = IntStream.range(0, 256)
                .mapToObj(index -> "scope/" + index)
                .collect(Collectors.joining(","));
        assertEquals(256, AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, maximum))
                .dataScopes().size());

        String tooMany = maximum + ",scope/256";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, tooMany)));
        assertEquals(name + " must contain at most 256 unique scope tokens", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void environmentAuthorityScopeLimitIncludesTheInternalRootScope() {
        String name = "RAVENROOT_AGENT_AUTHORITY_SCOPES";
        String externalMaximum = IntStream.range(0, 255)
                .mapToObj(index -> "authority/" + index)
                .collect(Collectors.joining(","));
        assertEquals(255, AgentAuthorityBudgetConfiguration.fromEnvironment(
                Map.of(name, externalMaximum)).authorityScopes().size());

        String missingRoot = externalMaximum + ",authority/255";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, missingRoot)));
        assertEquals(name + " must contain at most 256 effective root scope tokens",
                failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(!failure.getMessage().contains("authority/255"));

        String includingRoot = externalMaximum + ",runtime:root";
        assertEquals(256, AgentAuthorityBudgetConfiguration.fromEnvironment(
                Map.of(name, includingRoot)).authorityScopes().size());
    }

    @ParameterizedTest
    @MethodSource("numericNames")
    void signedAndUnicodeDigitsKeepTheEstablishedLongGrammar(String name) {
        var parsed = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, "\u2003+١٧\u2003"),
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
        var decimal = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, "17"),
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
        assertSameConfiguredValues(decimal, parsed);
        assertEquals(decimal.policyFingerprint(), parsed.policyFingerprint());
        assertEquals(decimal.rateCardFingerprint(), parsed.rateCardFingerprint());
        assertInvalidInteger(name, "\u00a0"); // NBSP is not Java blank/strip whitespace.
    }

    @Test
    void ratesKeepSignedZeroAndOtherNumericFieldsKeepTheirFullLongRange() {
        var clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
        var free = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS", "-0",
                "RAVENROOT_AGENT_OUTPUT_TOKEN_RATE_MICROS", "+0"), clock);
        assertEquals(0, free.inputTokenRateMicros()); assertEquals(0, free.outputTokenRateMicros());
        var maximums = numericNames().filter(name -> !name.equals("RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS"))
                .collect(Collectors.toUnmodifiableMap(Function.identity(), ignored -> Long.toString(Long.MAX_VALUE)));
        var maximum = AgentAuthorityBudgetConfiguration.fromEnvironment(maximums, clock);
        assertEquals(Long.MAX_VALUE, maximum.rootMaxima().elapsedMillis());
        assertEquals(Long.MAX_VALUE, maximum.maximumInputTokensPerTurn());
        assertEquals(Long.MAX_VALUE, maximum.outputTokenRateMicros());
    }

    @Test
    void finiteDeadlineBoundaryUsesTheInjectedClockWithoutAnArbitraryLifetimeCap() {
        var instant = java.time.Instant.EPOCH.plusNanos(999_999_999);
        var clock = java.time.Clock.fixed(instant, java.time.ZoneOffset.UTC);
        long seconds = java.time.Instant.MAX.getEpochSecond();
        var policy = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS", Long.toString(seconds)), clock);
        assertEquals(java.time.Instant.MAX, policy.rootDeadlineAt(instant));
        assertDeadlineRefused(Map.of("RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS", Long.toString(seconds + 1)), clock);
        assertDeadlineRefused(Map.of("RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS", Long.toString(Long.MAX_VALUE)), clock);
        assertDeadlineRefused(Map.of(), java.time.Clock.fixed(java.time.Instant.MAX, java.time.ZoneOffset.UTC));
        var nearEdge = java.time.Clock.fixed(java.time.Instant.MAX.minusSeconds(1), java.time.ZoneOffset.UTC);
        assertEquals(java.time.Instant.MAX, AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS", "1"), nearEdge).rootDeadlineAt(nearEdge.instant()));
    }

    @Test
    void oldFactoryRemainsAvailableAndConfiguredFingerprintsIgnoreFreshBootDiagnostics() {
        var old = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of());
        var explicit = AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(),
                java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC));
        assertSameConfiguredValues(old, explicit);
        assertEquals(old.policyFingerprint(), explicit.policyFingerprint());
        assertEquals(old.rateCardFingerprint(), explicit.rateCardFingerprint());
    }

    private static void assertDeadlineRefused(Map<String, String> environment, java.time.Clock clock) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(environment, clock));
        assertEquals("RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS must form a finite deadline at startup", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void assertSameConfiguredValues(AgentAuthorityBudgetPolicy expected,
                                                   AgentAuthorityBudgetPolicy actual) {
        assertEquals(expected.runtimeInstanceId(), actual.runtimeInstanceId());
        assertEquals(expected.policyVersion(), actual.policyVersion());
        assertEquals(expected.rateCardVersion(), actual.rateCardVersion());
        assertEquals(expected.currency(), actual.currency());
        assertEquals(expected.rootLifetime(), actual.rootLifetime());
        assertEquals(expected.rootMaxima(), actual.rootMaxima());
        assertEquals(expected.maximumInputTokensPerTurn(), actual.maximumInputTokensPerTurn());
        assertEquals(expected.maximumOutputTokensPerTurn(), actual.maximumOutputTokensPerTurn());
        assertEquals(expected.inputTokenRateMicros(), actual.inputTokenRateMicros());
        assertEquals(expected.outputTokenRateMicros(), actual.outputTokenRateMicros());
        assertEquals(expected.dataScopes(), actual.dataScopes());
        assertEquals(expected.authorityScopes(), actual.authorityScopes());
    }

    private static void assertInvalidInteger(String name, String raw) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, raw)));
        assertEquals(name + " must be an integer", failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(!failure.getMessage().contains(raw));
    }

    private static void assertInvalidIdentity(String name, String raw) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, raw)));
        assertEquals(name + " must be an identity token of 1..128 characters", failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(!failure.getMessage().contains(raw));
    }

    private static void assertInvalidScopes(String name, String raw) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> AgentAuthorityBudgetConfiguration.fromEnvironment(Map.of(name, raw)));
        assertEquals(name + " must contain comma-separated scope tokens of 1..128 characters",
                failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(!failure.getMessage().contains(raw));
    }

    private static Stream<String> numericNames() {
        return Stream.concat(positiveNumericNames(), rateNames());
    }

    private static Stream<String> positiveNumericNames() {
        return Stream.of(
                "RAVENROOT_AGENT_ROOT_LIFETIME_SECONDS",
                "RAVENROOT_AGENT_MAX_TURNS",
                "RAVENROOT_AGENT_MAX_INPUT_TOKENS",
                "RAVENROOT_AGENT_MAX_OUTPUT_TOKENS",
                "RAVENROOT_AGENT_MAX_ELAPSED_MILLIS",
                "RAVENROOT_AGENT_MAX_COST_MICROS",
                "RAVENROOT_AGENT_MAX_TOOL_CALLS",
                "RAVENROOT_AGENT_MAX_DELEGATION_DEPTH",
                "RAVENROOT_AGENT_MAX_TEAM_CUMULATIVE",
                "RAVENROOT_AGENT_MAX_TEAM_ACTIVE",
                "RAVENROOT_AGENT_MAX_INPUT_TOKENS_PER_TURN",
                "RAVENROOT_AGENT_MAX_OUTPUT_TOKENS_PER_TURN");
    }

    private static Stream<String> rateNames() {
        return Stream.of(
                "RAVENROOT_AGENT_INPUT_TOKEN_RATE_MICROS",
                "RAVENROOT_AGENT_OUTPUT_TOKEN_RATE_MICROS");
    }

    private static Stream<String> identityNames() {
        return Stream.of(
                "RAVENROOT_AGENT_RUNTIME_INSTANCE",
                "RAVENROOT_AGENT_POLICY_VERSION",
                "RAVENROOT_AGENT_RATE_CARD_VERSION");
    }
}
