package ai.ravenroot.api.catalog;

import ai.ravenroot.api.execution.RetryBackoff;
import ai.ravenroot.api.execution.RetryDecision;
import ai.ravenroot.api.execution.RetryPolicy;
import ai.ravenroot.api.persistence.Retryability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a node instance's declared retry policy, and refusing a package that tries to own the keys.
 *
 * <h2>The two directions this file is really about</h2>
 * <p>Downward: {@link #aNodeThatDeclaresNothingGetsExactlyTheNoRetryPolicy()} and
 * {@link #declaringOnlyABudgetStillRetriesNothing()} pin that the feature is invisible until an
 * author asks for it twice — once for the budget, once for the classification. A mutant that defaults
 * the classifier open passes every other test in this file and is red on the second.</p>
 * <p>Upward: {@link #aMalformedValueIsRefusedRatherThanDegradedToTheDefault()} pins that a typo is
 * loud. It is the opposite treatment from {@code recovery.repeatable}, deliberately, and the reason
 * is on the class: degrading there lands on the safe answer, whereas degrading here presents a policy
 * that is not running as one that is.</p>
 */
class NodeRetryPropertyTest {

    @Test
    @DisplayName("a node that declares nothing gets the no-retry policy, byte for byte the old behaviour")
    void aNodeThatDeclaresNothingGetsExactlyTheNoRetryPolicy() {
        assertSame(RetryPolicy.NONE, NodeRetryProperty.read(null));
        assertSame(RetryPolicy.NONE, NodeRetryProperty.read(Map.of()));
        assertSame(RetryPolicy.NONE, NodeRetryProperty.read(Map.of("unrelated", "value")));
        assertFalse(NodeRetryProperty.declaredBy(Map.of("unrelated", "value")));
    }

    @Test
    @DisplayName("a budget with no classification retries nothing: the bound is not itself permission")
    void declaringOnlyABudgetStillRetriesNothing() {
        RetryPolicy policy = NodeRetryProperty.read(Map.of(NodeRetryProperty.MAX_ATTEMPTS, "5"));

        assertTrue(policy.enabled(), "the budget is read");
        assertInstanceOf(RetryDecision.Stop.class,
                policy.decide(1, new java.io.IOException("a plausible transient failure")),
                "a bound with no classification would otherwise mean 'repeat any failure', which is "
                        + "the fail-open reading of a property whose name says nothing about safety");
    }

    @Test
    void aDeclaredAllowlistIsWhatMakesTheBudgetUsable() {
        RetryPolicy policy = NodeRetryProperty.read(Map.of(
                NodeRetryProperty.MAX_ATTEMPTS, "4",
                NodeRetryProperty.RETRY_ON, "java.io.IOException, IllegalStateException"));

        var retry = assertInstanceOf(RetryDecision.Retry.class,
                policy.decide(1, new java.io.IOException("declared")));
        assertEquals(2, retry.nextOrdinal());
        assertEquals(Retryability.RETRYABLE_NO_EFFECT, retry.classification());
        assertInstanceOf(RetryDecision.Retry.class, policy.decide(1, new IllegalStateException("also declared")));
        assertInstanceOf(RetryDecision.Stop.class, policy.decide(1, new ArithmeticException("not declared")));
    }

    // ------------------------------------------------------------------ backoff parsing

    @Test
    @DisplayName("a wait may be written as ISO-8601 or as a bare count of milliseconds")
    void bothDurationSpellingsAreAccepted() {
        assertEquals(Duration.ofMillis(500),
                NodeRetryProperty.parseDuration(NodeRetryProperty.INITIAL_BACKOFF, "PT0.5S"));
        assertEquals(Duration.ofMillis(500),
                NodeRetryProperty.parseDuration(NodeRetryProperty.INITIAL_BACKOFF, "500"));
        assertEquals(Duration.ofMillis(500),
                NodeRetryProperty.parseDuration(NodeRetryProperty.INITIAL_BACKOFF, 500));
    }

    @Test
    void backoffDefaultsFillInAroundWhateverTheAuthorDeclared() {
        RetryPolicy policy = NodeRetryProperty.read(Map.of(
                NodeRetryProperty.MAX_ATTEMPTS, "3",
                NodeRetryProperty.INITIAL_BACKOFF, "50"));

        assertEquals(Duration.ofMillis(50), policy.backoff().initialDelay());
        assertEquals(RetryBackoff.DEFAULT_MULTIPLIER, policy.backoff().multiplier());
        assertEquals(RetryBackoff.DEFAULT_MAX_DELAY, policy.backoff().maxDelay());
    }

    @Test
    @DisplayName("an initial wait above the default ceiling raises the ceiling rather than being refused")
    void aLongInitialWaitWithNoDeclaredCeilingIsCoherentRatherThanRejected() {
        RetryPolicy policy = NodeRetryProperty.read(Map.of(
                NodeRetryProperty.MAX_ATTEMPTS, "2",
                NodeRetryProperty.INITIAL_BACKOFF, "PT5M"));

        assertEquals(Duration.ofMinutes(5), policy.backoff().initialDelay());
        assertEquals(Duration.ofMinutes(5), policy.backoff().maxDelay(),
                "an author who declared one property expressed one intent; refusing them for a "
                        + "ceiling they never mentioned would be the platform arguing with itself");
    }

    @Test
    void anExplicitCeilingBelowTheInitialWaitIsStillIncoherentAndIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> NodeRetryProperty.read(Map.of(
                NodeRetryProperty.MAX_ATTEMPTS, "2",
                NodeRetryProperty.INITIAL_BACKOFF, "PT5M",
                NodeRetryProperty.MAX_BACKOFF, "PT1S")));
    }

    // ------------------------------------------------------------------ malformed input

    @Test
    @DisplayName("a malformed value throws at graph admission rather than degrading to one attempt")
    void aMalformedValueIsRefusedRatherThanDegradedToTheDefault() {
        assertThrows(IllegalArgumentException.class,
                () -> NodeRetryProperty.read(Map.of(NodeRetryProperty.MAX_ATTEMPTS, "tree")));
        assertThrows(IllegalArgumentException.class,
                () -> NodeRetryProperty.read(Map.of(NodeRetryProperty.MAX_ATTEMPTS, "-1")));
        assertThrows(IllegalArgumentException.class,
                () -> NodeRetryProperty.read(Map.of(NodeRetryProperty.INITIAL_BACKOFF, "soon")));
        assertThrows(IllegalArgumentException.class,
                () -> NodeRetryProperty.read(Map.of(NodeRetryProperty.BACKOFF_MULTIPLIER, "twice")));
    }

    @Test
    void theBudgetHasACeilingSoAMistypedValueCannotMultiplyEffectsUnboundedly() {
        assertThrows(IllegalArgumentException.class, () -> NodeRetryProperty.read(Map.of(
                NodeRetryProperty.MAX_ATTEMPTS,
                String.valueOf(NodeRetryProperty.MAX_DECLARABLE_ATTEMPTS + 1))));
        assertEquals(NodeRetryProperty.MAX_DECLARABLE_ATTEMPTS,
                NodeRetryProperty.read(Map.of(NodeRetryProperty.MAX_ATTEMPTS,
                        String.valueOf(NodeRetryProperty.MAX_DECLARABLE_ATTEMPTS))).maxAttempts());
    }

    @Test
    @DisplayName("an unrecognisable type name is never matched, and is never an error")
    void anUnknownTypeNameIsToleratedBecauseItMayNameAnAbsentPlugin() {
        RetryPolicy policy = NodeRetryProperty.read(Map.of(
                NodeRetryProperty.MAX_ATTEMPTS, "3",
                NodeRetryProperty.RETRY_ON, "com.example.NotInstalledException, ,IllegalStateException"));

        assertInstanceOf(RetryDecision.Retry.class, policy.decide(1, new IllegalStateException("declared")));
        assertInstanceOf(RetryDecision.Stop.class, policy.decide(1, new ArithmeticException("typo'd away")));
    }

    // ------------------------------------------------------------------ platform ownership

    @Test
    @DisplayName("a behavior package cannot declare any retry key: it would own the effect count")
    void aDescriptorDeclaringARetryKeyFailsCatalogRegistration() {
        for (String name : NodeRetryProperty.NAMES) {
            NodeTypeDescriptor descriptor = descriptorDeclaring(name);
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> NodeTypeDescriptorValidator.validate(descriptor),
                    "expected '" + name + "' to be refused at catalog load");
            assertTrue(refused.getMessage().contains(name));
            assertTrue(refused.getMessage().contains("platform-owned"));
        }
    }

    @Test
    void aDescriptorDeclaringNoneOfThemPassesUnaffected() {
        NodeTypeDescriptor descriptor = descriptorDeclaring("ordinary.property");
        NodeTypeDescriptorValidator.validate(descriptor);
        NodeRetryProperty.validateShape(null);
    }

    private static NodeTypeDescriptor descriptorDeclaring(String propertyName) {
        return new NodeTypeDescriptor("sample.behavior", "Sample", "General", "d", "actor", false,
                List.of(new NodePropertyDescriptor(propertyName, "Label", NodePropertyType.STRING, false,
                        "d", "", List.of(), false, null, null)),
                java.util.Set.of());
    }
}
