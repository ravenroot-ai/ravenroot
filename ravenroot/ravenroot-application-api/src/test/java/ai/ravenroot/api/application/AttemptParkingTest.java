package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parked-attempt contract of PERS-04 / ADR 0022, at the aggregate.
 *
 * <p>Every case here guards a decision that is expensive to get wrong and cheap to break by
 * accident, which is why the absent transitions are asserted as loudly as the present ones: a
 * transition table is only a contract for the moves it <em>refuses</em>.</p>
 */
class AttemptParkingTest {

    private static final UUID PROCESS = UUID.randomUUID();
    private static final UUID TRAVERSAL = UUID.randomUUID();
    private static final UUID INVOCATION = UUID.randomUUID();

    @Test
    void runningParksAndParkedResolvesButNeverResumesInPlace() {
        assertTrue(NodeAttemptStatus.RUNNING.canTransitionTo(NodeAttemptStatus.PARKED));
        assertTrue(NodeAttemptStatus.PARKED.canTransitionTo(NodeAttemptStatus.COMPLETED));
        assertTrue(NodeAttemptStatus.PARKED.canTransitionTo(NodeAttemptStatus.FAILED));

        // The whole of documented contract 3 lives on this line: an effect whose outcome is unknown is never
        // resumed in place, because resuming would repeat an effect that may already have happened.
        assertFalse(NodeAttemptStatus.PARKED.canTransitionTo(NodeAttemptStatus.RUNNING));

        assertFalse(NodeAttemptStatus.PARKED.canTransitionTo(NodeAttemptStatus.WAITING));
        assertFalse(NodeAttemptStatus.PARKED.canTransitionTo(NodeAttemptStatus.SCHEDULED));
        assertFalse(NodeAttemptStatus.PARKED.canTransitionTo(NodeAttemptStatus.PARKED));

        // A SCHEDULED attempt is provably effect-free, so it has no ambiguity to record; a WAITING
        // attempt's dispatch outcome is known. Neither may park.
        assertFalse(NodeAttemptStatus.SCHEDULED.canTransitionTo(NodeAttemptStatus.PARKED));
        assertFalse(NodeAttemptStatus.WAITING.canTransitionTo(NodeAttemptStatus.PARKED));
        assertFalse(NodeAttemptStatus.COMPLETED.canTransitionTo(NodeAttemptStatus.PARKED));
        assertFalse(NodeAttemptStatus.FAILED.canTransitionTo(NodeAttemptStatus.PARKED));

        // Parking is not an ending. A parked attempt still owes someone a decision.
        assertFalse(NodeAttemptStatus.PARKED.terminal());
    }

    @Test
    void aParkCauseExistsExactlyWhenTheAttemptIsParked() {
        NodeAttempt parked = running().park("smtp conversation never acknowledged");
        assertEquals(NodeAttemptStatus.PARKED, parked.status());
        assertEquals("smtp conversation never acknowledged", parked.parkCause());
        assertNull(parked.completion());

        assertThrows(IllegalArgumentException.class, () -> new NodeAttempt(UUID.randomUUID(), 1,
                NodeAttemptStatus.PARKED, null, null), "a parked attempt with no cause is unresolvable");
        assertThrows(IllegalArgumentException.class, () -> new NodeAttempt(UUID.randomUUID(), 1,
                NodeAttemptStatus.PARKED, null, "   "), "a blank cause is no cause");
        assertThrows(IllegalArgumentException.class, () -> new NodeAttempt(UUID.randomUUID(), 1,
                NodeAttemptStatus.RUNNING, null, "why"), "only a parked attempt may explain itself");
    }

    @Test
    void theCauseIsBoundedAndStrippedOfControlCharacters() {
        NodeAttempt injected = running().park("line one\nERROR forged log line\r\tand a tab");
        assertFalse(injected.parkCause().contains("\n"));
        assertFalse(injected.parkCause().contains("\r"));
        assertFalse(injected.parkCause().contains("\t"));

        NodeAttempt long1 = running().park("x".repeat(NodeAttempt.MAX_PARK_CAUSE_LENGTH * 3));
        assertEquals(NodeAttempt.MAX_PARK_CAUSE_LENGTH, long1.parkCause().length());
        // The cut is marked, so a cause at exactly the bound is not read as a complete one.
        // The bound's full contract lives in NodeAttemptParkCauseBoundTest.
        assertTrue(long1.parkCause().endsWith(NodeAttempt.PARK_CAUSE_TRUNCATION_MARKER));
    }

    @Test
    void resolvingAParkNeverForgesAnObservedSuccess() {
        NodeAttempt verified = running().park("unknown").resolveVerified();
        assertEquals(NodeAttemptStatus.COMPLETED, verified.status());
        assertEquals(NodeAttemptCompletion.OPERATOR_VERIFIED, verified.completion());
        assertNull(verified.parkCause(), "a resolved attempt is no longer parked");

        // The generic transition would hardcode SUCCEEDED, which asserts an observation nobody made.
        NodeAttempt parked = running().park("unknown");
        assertThrows(IllegalArgumentException.class,
                () -> parked.transitionTo(NodeAttemptStatus.COMPLETED));

        // And parking itself cannot be reached without a cause.
        assertThrows(IllegalArgumentException.class,
                () -> running().transitionTo(NodeAttemptStatus.PARKED));

        assertTrue(NodeAttemptCompletion.OPERATOR_VERIFIED.successful());
        assertTrue(NodeAttemptCompletion.SUCCEEDED.successful());
        assertFalse(NodeAttemptCompletion.WAIT.successful(), "WAIT defers the work, it does not do it");
    }

    @Test
    void resolvingWithARetryIsOneAtomicStepThatProducesANewEffectIdentity() {
        UUID firstAttemptId = UUID.randomUUID();
        UUID retryId = UUID.randomUUID();
        ProcessInstance parked = instanceWith(new NodeAttempt(firstAttemptId, 1, NodeAttemptStatus.RUNNING))
                .parkAttempt(TRAVERSAL, INVOCATION, firstAttemptId, "unknown");

        ProcessInstance retried = parked.resolveParkedWithRetry(TRAVERSAL, INVOCATION, firstAttemptId,
                new NodeAttempt(retryId, 2, NodeAttemptStatus.SCHEDULED));

        List<NodeAttempt> attempts = attemptsOf(retried);
        assertEquals(2, attempts.size());
        assertEquals(NodeAttemptStatus.FAILED, attempts.get(0).status());
        assertNull(attempts.get(0).parkCause());
        assertEquals(NodeAttemptStatus.SCHEDULED, attempts.get(1).status());
        // New ordinal and new id: the retry is a new effect by construction, not by policy.
        assertEquals(2, attempts.get(1).ordinal());
        assertFalse(attempts.get(1).attemptId().equals(firstAttemptId));

        // A retry is not a way to smuggle a running attempt back in.
        assertThrows(IllegalArgumentException.class, () -> parked.resolveParkedWithRetry(TRAVERSAL,
                INVOCATION, firstAttemptId, new NodeAttempt(retryId, 2, NodeAttemptStatus.RUNNING)));
    }

    @Test
    void aParkedAttemptCannotBeSilentlySupersededByANewOne() {
        UUID attemptId = UUID.randomUUID();
        ProcessInstance parked = instanceWith(new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING))
                .parkAttempt(TRAVERSAL, INVOCATION, attemptId, "unknown");

        // addAttempt requires the previous attempt to be FAILED, so appending past a park — which is
        // how an unknown effect would quietly be repeated — is refused at the aggregate.
        assertThrows(IllegalStateException.class, () -> parked.addAttempt(TRAVERSAL, INVOCATION,
                new NodeAttempt(UUID.randomUUID(), 2, NodeAttemptStatus.SCHEDULED)));
    }

    @Test
    void anInvocationCompletesOnAnOperatorVerifiedResolutionAndNotOnAFailedOne() {
        UUID attemptId = UUID.randomUUID();
        ProcessInstance verified = instanceWith(new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING))
                .parkAttempt(TRAVERSAL, INVOCATION, attemptId, "unknown")
                .resolveParkedVerified(TRAVERSAL, INVOCATION, attemptId);
        ProcessInstance completed = verified.transitionInvocation(TRAVERSAL, INVOCATION,
                NodeInvocationStatus.COMPLETED);
        assertEquals(NodeInvocationStatus.COMPLETED,
                completed.traversals().get(TRAVERSAL).invocations().get(INVOCATION).status());

        ProcessInstance failed = instanceWith(new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING))
                .parkAttempt(TRAVERSAL, INVOCATION, attemptId, "unknown")
                .resolveParkedFailed(TRAVERSAL, INVOCATION, attemptId);
        assertThrows(IllegalStateException.class, () -> failed.transitionInvocation(TRAVERSAL, INVOCATION,
                NodeInvocationStatus.COMPLETED));
    }

    @Test
    void onlyAParkedAttemptCanBeResolved() {
        UUID attemptId = UUID.randomUUID();
        ProcessInstance running = instanceWith(new NodeAttempt(attemptId, 1, NodeAttemptStatus.RUNNING));
        assertThrows(IllegalStateException.class,
                () -> running.resolveParkedVerified(TRAVERSAL, INVOCATION, attemptId));
        assertThrows(IllegalStateException.class,
                () -> running.resolveParkedFailed(TRAVERSAL, INVOCATION, attemptId));
        // An attempt that is not the latest one cannot be parked either: parking the wrong ordinal
        // would record ambiguity against an attempt whose outcome the aggregate already knows.
        assertThrows(IllegalArgumentException.class,
                () -> running.parkAttempt(TRAVERSAL, INVOCATION, UUID.randomUUID(), "unknown"));
    }

    private static NodeAttempt running() {
        return new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.RUNNING);
    }

    private static List<NodeAttempt> attemptsOf(ProcessInstance instance) {
        return instance.traversals().get(TRAVERSAL).invocations().get(INVOCATION).attempts();
    }

    private static ProcessInstance instanceWith(NodeAttempt attempt) {
        var invocation = new NodeInvocation(INVOCATION, "mail.send", Set.of(),
                NodeInvocationStatus.RUNNING, List.of(attempt));
        var traversal = new Traversal(TRAVERSAL, "start", TraversalStatus.RUNNING,
                Map.of(INVOCATION, invocation));
        return new ProcessInstance(PROCESS, ProcessInstanceStatus.RUNNING, Map.of(TRAVERSAL, traversal));
    }
}
