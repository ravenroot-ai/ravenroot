package ai.ravenroot.api.execution;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.Retryability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounded retry contract: how many, how long, and which failures.
 *
 * <h2>The two cells that matter most, and why</h2>
 * <p>{@link #anIndeterminateFailureStopsEvenWithBudgetRemaining()} is the safety cell. It is the one
 * assertion standing between this policy and automatically repeating an effect that may already have
 * landed, which is what ADR 0022 exists to refuse — and it is a case an implementer would plausibly
 * "fix" by treating INDETERMINATE as transient. {@link #theDefaultClassifierRefusesWhatItCannotName()}
 * is the same argument at the classifier: fail-closed is the property, and a mutant that returns
 * RETRYABLE_NO_EFFECT for an unrecognised throwable is red here and nowhere else.</p>
 */
class RetryPolicyTest {

    private static final RetryBackoff FLAT = new RetryBackoff(Duration.ofMillis(10), 1.0,
            Duration.ofMillis(10));

    // ------------------------------------------------------------------ the budget

    @Test
    @DisplayName("maxAttempts counts attempts, so three allows two retries and stops at ordinal three")
    void theBudgetCountsAttemptsRatherThanRetries() {
        var policy = new RetryPolicy(3, FLAT, alwaysRetryable());

        assertEquals(2, ((RetryDecision.Retry) policy.decide(1, new RuntimeException())).nextOrdinal());
        assertEquals(3, ((RetryDecision.Retry) policy.decide(2, new RuntimeException())).nextOrdinal());

        var stop = assertInstanceOf(RetryDecision.Stop.class, policy.decide(3, new RuntimeException()));
        assertEquals(RetryPolicy.REASON_EXHAUSTED, stop.reason());
    }

    @Test
    @DisplayName("the default policy retries nothing, so an existing graph cannot observe the machinery")
    void theNoneePolicyIsIndistinguishableFromHavingNoRetries() {
        assertFalse(RetryPolicy.NONE.enabled());
        var stop = assertInstanceOf(RetryDecision.Stop.class,
                RetryPolicy.NONE.decide(1, new RuntimeException("anything")));
        assertEquals(RetryPolicy.REASON_NOT_RETRYABLE, stop.reason());
    }

    @Test
    void aBudgetBelowOneIsRefusedRatherThanClampedToNoRetries() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, FLAT, alwaysRetryable()));
    }

    // ------------------------------------------------------------------ the fixed disposition

    @Test
    @DisplayName("INDETERMINATE stops even with budget left: the effect may already have landed")
    void anIndeterminateFailureStopsEvenWithBudgetRemaining() {
        var policy = new RetryPolicy(10, FLAT, failure -> Retryability.INDETERMINATE);

        var stop = assertInstanceOf(RetryDecision.Stop.class, policy.decide(1, new RuntimeException()));
        assertEquals(RetryPolicy.REASON_NOT_RETRYABLE, stop.reason());
        assertEquals(Retryability.INDETERMINATE, stop.classification(),
                "the classification travels on the refusal, so an operator can tell 'this was never "
                        + "going to be retried' from 'we ran out of attempts'");
    }

    @Test
    void bothRetryableClassificationsRetryAndBothRefusalsStop() {
        for (Retryability retryable : Set.of(Retryability.RETRYABLE_NO_EFFECT,
                Retryability.RETRY_AFTER_REREAD)) {
            assertInstanceOf(RetryDecision.Retry.class,
                    new RetryPolicy(2, FLAT, failure -> retryable).decide(1, new RuntimeException()),
                    "expected " + retryable + " to authorise a retry");
        }
        for (Retryability refusing : Set.of(Retryability.DETERMINISTIC_REJECT, Retryability.INDETERMINATE)) {
            assertInstanceOf(RetryDecision.Stop.class,
                    new RetryPolicy(2, FLAT, failure -> refusing).decide(1, new RuntimeException()),
                    "expected " + refusing + " to stop");
        }
    }

    @Test
    @DisplayName("an exhausted retryable failure reports exhaustion, not a false 'not retryable'")
    void exhaustionAndNonRetryabilityAreDistinguishableOnTheDecision() {
        var exhausted = assertInstanceOf(RetryDecision.Stop.class,
                new RetryPolicy(1, FLAT, alwaysRetryable()).decide(1, new RuntimeException()));
        assertEquals(RetryPolicy.REASON_EXHAUSTED, exhausted.reason());
        assertEquals(Retryability.RETRYABLE_NO_EFFECT, exhausted.classification());
    }

    // ------------------------------------------------------------------ a hostile classifier

    @Test
    @DisplayName("a classifier that throws or returns null does not abort the failure handling")
    void aBrokenClassifierFailsClosedInsteadOfEscaping() {
        var throwing = new RetryPolicy(5, FLAT, failure -> {
            throw new IllegalStateException("classifier is broken");
        });
        assertInstanceOf(RetryDecision.Stop.class, throwing.decide(1, new RuntimeException()),
                "letting it escape would leave the attempt neither retried nor recorded as failed");

        var nullReturning = new RetryPolicy(5, FLAT, failure -> null);
        assertInstanceOf(RetryDecision.Stop.class, nullReturning.decide(1, new RuntimeException()));
    }

    // ------------------------------------------------------------------ the default classifier

    @Test
    @DisplayName("the fail-closed classifier refuses everything it cannot positively name")
    void theDefaultClassifierRefusesWhatItCannotName() {
        RetryClassifier classifier = RetryClassifier.failClosed();

        assertEquals(Retryability.DETERMINISTIC_REJECT,
                classifier.classify(new java.io.IOException("a network blip nobody declared")),
                "the safe direction: an unrecognised failure is never repeated automatically");
    }

    @Test
    @DisplayName("a failure that states its own classification is believed, through a stage wrapper")
    void aSelfClassifyingFailureIsHonouredEvenInsideACompletionException() {
        RetryClassifier classifier = RetryClassifier.failClosed();
        Throwable stated = new StatedFailure(Retryability.RETRY_AFTER_REREAD);

        assertEquals(Retryability.RETRY_AFTER_REREAD, classifier.classify(stated));
        assertEquals(Retryability.RETRY_AFTER_REREAD, classifier.classify(new CompletionException(stated)),
                "every node failure arrives wrapped, so a classifier that only reads the outer type "
                        + "would classify nothing on the one path that is actually used");
    }

    @Test
    void aStoreFailureIsClassifiedByItsOwnRetryabilityRatherThanBeingReDecided() {
        var conflict = new ExecutionStoreException(new ExecutionStoreFailure.ConcurrencyConflict(
                new ExecutionKey("t", UUID.randomUUID()), RevisionExpectation.exactly(1), 2));

        assertEquals(conflict.failure().retryability(),
                RetryClassifier.failClosed().classify(conflict),
                "the port already answers this question; a second opinion here would be a place for "
                        + "the two to disagree");
    }

    @Test
    @DisplayName("an author's allowlist matches supertypes and both name forms, and nothing else")
    void theDeclaredAllowlistMatchesBySimpleAndQualifiedNameAcrossTheHierarchy() {
        RetryClassifier byQualified = RetryClassifier.declaredRetryable(Set.of("java.io.IOException"));
        assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                byQualified.classify(new java.net.SocketTimeoutException("subclass of IOException")),
                "an author declaring a family must not have to enumerate its members");

        RetryClassifier bySimple = RetryClassifier.declaredRetryable(Set.of("SocketTimeoutException"));
        assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                bySimple.classify(new java.net.SocketTimeoutException("timed out")));

        assertEquals(Retryability.DETERMINISTIC_REJECT,
                bySimple.classify(new IllegalStateException("unrelated")),
                "the allowlist widens nothing it did not name");
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                RetryClassifier.declaredRetryable(Set.of("socketTimeoutException"))
                        .classify(new java.net.SocketTimeoutException("timed out")),
                "matching is case-sensitive: an approximate match authorises repeating an effect "
                        + "nobody named");
    }

    // ------------------------------------------------------ the allowlist may only widen silence

    /**
     * The regression cell. An author's family-level declaration must not override a connector's own
     * {@link Retryability#INDETERMINATE}.
     *
     * <p>Asserted through {@link RetryPolicy#decide(int, Throwable)} and not only through the
     * classifier, because the classifier's answer is an intermediate value and the decision is what
     * actually repeats the effect. Before the fix this produced
     * {@code Retry[nextOrdinal=2, classification=RETRYABLE_NO_EFFECT]} for a failure that had said,
     * in as many words, that its effect may already have landed.</p>
     *
     * <p>Both declaration shapes are exercised — the concrete supertype and {@code Exception}, which
     * names practically everything — because the defect was in the <em>ordering</em> of the two
     * consultations, so any name that matches at all is enough to reproduce it.</p>
     */
    @Test
    @DisplayName("an allowlist naming a supertype leaves a stated INDETERMINATE non-retryable")
    void aDeclaredSupertypeCannotOverrideAStatedIndeterminate() {
        for (String declaredName : List.of("RuntimeException", "java.lang.RuntimeException", "Exception")) {
            var policy = new RetryPolicy(5, FLAT, RetryClassifier.declaredRetryable(Set.of(declaredName)));
            Throwable stated = new StatedFailure(Retryability.INDETERMINATE);

            var stop = assertInstanceOf(RetryDecision.Stop.class, policy.decide(1, stated),
                    "declaring '" + declaredName + "' must not repeat an effect that may have landed");
            assertEquals(RetryPolicy.REASON_NOT_RETRYABLE, stop.reason());
            assertEquals(Retryability.INDETERMINATE, stop.classification(),
                    "the connector's own statement must reach the decision unchanged, not be "
                            + "replaced by the author's guess about the same failure");
            assertEquals(Retryability.INDETERMINATE,
                    RetryClassifier.declaredRetryable(Set.of(declaredName)).classify(stated));
        }
    }

    @Test
    @DisplayName("an allowlist naming a supertype leaves a CancellationException non-retryable")
    void aDeclaredSupertypeCannotTurnACancellationIntoARetry() {
        var policy = new RetryPolicy(5, FLAT,
                RetryClassifier.declaredRetryable(Set.of("RuntimeException")));

        var stop = assertInstanceOf(RetryDecision.Stop.class,
                policy.decide(1, new java.util.concurrent.CancellationException("stopped")),
                "a cancelled attempt is not a transient condition, whatever the author declared: "
                        + "retrying it would make cancel a loop rather than a stop");
        assertEquals(Retryability.DETERMINISTIC_REJECT, stop.classification());
    }

    @Test
    @DisplayName("a store failure's own classification survives an allowlist that names its type")
    void aDeclaredSupertypeCannotOverrideAStoreFailuresOwnClassification() {
        var conflict = new ExecutionStoreException(new ExecutionStoreFailure.ConcurrencyConflict(
                new ExecutionKey("t", UUID.randomUUID()), RevisionExpectation.exactly(1), 2));

        assertEquals(conflict.failure().retryability(),
                RetryClassifier.declaredRetryable(Set.of("RuntimeException")).classify(conflict),
                "the port already answered this; an author naming the exception's supertype is not a "
                        + "second opinion that outranks it");
    }

    @Test
    @DisplayName("the allowlist still widens a failure that states nothing, which is what it is for")
    void anUnclassifiedFailureIsStillWidenedByTheDeclaration() {
        var policy = new RetryPolicy(3, FLAT,
                RetryClassifier.declaredRetryable(Set.of("java.io.IOException")));

        var retry = assertInstanceOf(RetryDecision.Retry.class,
                policy.decide(1, new java.net.SocketTimeoutException("says nothing about itself")),
                "the fix must not disable the author's channel, only stop it overruling a statement");
        assertEquals(Retryability.RETRYABLE_NO_EFFECT, retry.classification());
    }

    @Test
    @DisplayName("a RetryClassified that returns null has stated nothing, so the declaration binds")
    void aClassifierReturningNullIsSilenceRatherThanARefusal() {
        var policy = new RetryPolicy(3, FLAT,
                RetryClassifier.declaredRetryable(Set.of("StatedFailure")));

        assertInstanceOf(RetryDecision.Retry.class, policy.decide(1, new StatedFailure(null)),
                "an implementation that declined to answer has not refused; its own contract says to "
                        + "return INDETERMINATE when it cannot decide, and this is what happens when "
                        + "it does not");
    }

    @Test
    @DisplayName("an interface name matches, because 'every supertype' includes the ones it implements")
    void theSupertypeWalkReachesInterfacesAndNotOnlySuperclasses() {
        assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                RetryClassifier.declaredRetryable(Set.of("TransientMarker"))
                        .classify(new MarkedFailure()),
                "a superclass-only walk could never match an interface, so a declaration naming one "
                        + "silently retried nothing");
        assertEquals(Retryability.RETRYABLE_NO_EFFECT,
                RetryClassifier.declaredRetryable(Set.of(TransientMarker.class.getName()))
                        .classify(new MarkedFailure()));
    }

    /** An interface an author might name to cover a family of connector failures. */
    private interface TransientMarker {
    }

    /** States nothing about itself, so the declaration is the only statement there is. */
    private static final class MarkedFailure extends RuntimeException implements TransientMarker {
        private MarkedFailure() {
            super("marked");
        }
    }

    @Test
    void anEmptyAllowlistIsExactlyTheFailClosedClassifier() {
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                RetryClassifier.declaredRetryable(Set.of()).classify(new java.io.IOException("x")));
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                RetryClassifier.declaredRetryable(null).classify(new java.io.IOException("x")));
    }

    @Test
    @DisplayName("unwrapping terminates on a causation cycle instead of spinning forever")
    void unwrappingIsBoundedAgainstACausationCycle() {
        // Two wrappers, each the other's cause. Constructible by anyone, and the shape a loop bounded
        // by a depth counter would survive while a loop bounded by nothing would hang on -- so this is
        // the case that decides between "terminates" and "the classifier is a denial of service".
        var first = new CyclicWrapper();
        var second = new CyclicWrapper();
        first.initCause(second);
        second.initCause(first);

        Throwable unwrapped = RetryClassifier.unwrap(first);
        assertTrue(unwrapped == first || unwrapped == second,
                "it must stop at something real rather than returning null or looping");
        assertEquals(Retryability.DETERMINISTIC_REJECT, RetryClassifier.failClosed().classify(first));
    }

    /** A {@link CompletionException} whose cause is settable, so a cycle can be built. */
    private static final class CyclicWrapper extends CompletionException {
        private CyclicWrapper() {
            super();
        }
    }

    @Test
    void aCancellationIsNeverTreatedAsTransient() {
        assertEquals(Retryability.DETERMINISTIC_REJECT,
                RetryClassifier.failClosed().classify(new java.util.concurrent.CancellationException()));
    }

    // ------------------------------------------------------------------ decision shapes

    @Test
    void aRetryDecisionCannotNametsOwnOrdinalOrANegativeDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryDecision.Retry(1, Duration.ZERO, Retryability.RETRYABLE_NO_EFFECT));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryDecision.Retry(2, Duration.ofMillis(-1), Retryability.RETRYABLE_NO_EFFECT));
        assertTrue(new RetryDecision.Retry(2, Duration.ZERO, Retryability.RETRYABLE_NO_EFFECT).retrying());
        assertFalse(new RetryDecision.Stop("why", Retryability.INDETERMINATE).retrying());
    }

    private static RetryClassifier alwaysRetryable() {
        return failure -> Retryability.RETRYABLE_NO_EFFECT;
    }

    private static final class StatedFailure extends RuntimeException implements RetryClassified {
        private final Retryability retryability;

        private StatedFailure(Retryability retryability) {
            super("stated");
            this.retryability = retryability;
        }

        @Override
        public Retryability retryability() {
            return retryability;
        }
    }
}
