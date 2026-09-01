package ai.ravenroot.api.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessInstanceLifecycleTest {
    @Test
    void exposesTheExactLifecycleMatricesAndAbsorbingTerminalStates() {
        assertTrue(ProcessInstanceStatus.ACCEPTED.canTransitionTo(ProcessInstanceStatus.RUNNING));
        assertTrue(ProcessInstanceStatus.RUNNING.canTransitionTo(ProcessInstanceStatus.WAITING));
        assertTrue(ProcessInstanceStatus.WAITING.canTransitionTo(ProcessInstanceStatus.RUNNING));
        assertTrue(ProcessInstanceStatus.RUNNING.canTransitionTo(ProcessInstanceStatus.COMPLETED));
        assertFalse(ProcessInstanceStatus.COMPLETED.canTransitionTo(ProcessInstanceStatus.RUNNING));
        assertFalse(ProcessInstanceStatus.FAILED.canTransitionTo(ProcessInstanceStatus.RUNNING));

        assertTrue(TraversalStatus.ACCEPTED.canTransitionTo(TraversalStatus.RUNNING));
        assertTrue(TraversalStatus.RUNNING.canTransitionTo(TraversalStatus.WAITING));
        assertTrue(TraversalStatus.WAITING.canTransitionTo(TraversalStatus.RUNNING));
        assertFalse(TraversalStatus.COMPLETED.canTransitionTo(TraversalStatus.RUNNING));
        assertFalse(TraversalStatus.FAILED.canTransitionTo(TraversalStatus.RUNNING));

        assertTrue(NodeInvocationStatus.SCHEDULED.canTransitionTo(NodeInvocationStatus.RUNNING));
        assertTrue(NodeInvocationStatus.RUNNING.canTransitionTo(NodeInvocationStatus.WAITING));
        assertTrue(NodeInvocationStatus.WAITING.canTransitionTo(NodeInvocationStatus.RUNNING));
        assertFalse(NodeInvocationStatus.COMPLETED.canTransitionTo(NodeInvocationStatus.RUNNING));
        assertFalse(NodeInvocationStatus.FAILED.canTransitionTo(NodeInvocationStatus.RUNNING));

        assertTrue(NodeAttemptStatus.SCHEDULED.canTransitionTo(NodeAttemptStatus.RUNNING));
        assertTrue(NodeAttemptStatus.RUNNING.canTransitionTo(NodeAttemptStatus.WAITING));
        assertTrue(NodeAttemptStatus.WAITING.canTransitionTo(NodeAttemptStatus.RUNNING));
        assertFalse(NodeAttemptStatus.COMPLETED.canTransitionTo(NodeAttemptStatus.RUNNING));
        assertFalse(NodeAttemptStatus.FAILED.canTransitionTo(NodeAttemptStatus.RUNNING));
    }

    @Test
    void ordinaryResumeKeepsEveryIdentityAndExternalizedResumeAppendsOneAttempt() {
        UUID traversalId = id(2);
        UUID invocationId = id(3);
        UUID firstAttemptId = id(4);
        ProcessInstance waiting = runningProcess(traversalId)
                .addInvocation(traversalId, scheduledInvocation(invocationId, "review", Set.of()))
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.RUNNING)
                .addAttempt(traversalId, invocationId,
                        new NodeAttempt(firstAttemptId, 1, NodeAttemptStatus.SCHEDULED))
                .transitionAttempt(traversalId, invocationId, firstAttemptId, NodeAttemptStatus.RUNNING)
                .transitionAttempt(traversalId, invocationId, firstAttemptId, NodeAttemptStatus.WAITING)
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.WAITING);

        ProcessInstance ordinary = waiting.resumeAttempt(traversalId, invocationId, firstAttemptId);
        NodeInvocation ordinaryInvocation = ordinary.traversals().get(traversalId).invocations().get(invocationId);
        assertEquals(NodeInvocationStatus.RUNNING, ordinaryInvocation.status());
        assertEquals(firstAttemptId, ordinaryInvocation.attempts().getFirst().attemptId());
        assertEquals(NodeAttemptStatus.RUNNING, ordinaryInvocation.attempts().getFirst().status());

        UUID secondAttemptId = id(5);
        ProcessInstance externalized = waiting.resumeWithNewAttempt(traversalId, invocationId,
                new NodeAttempt(secondAttemptId, 2, NodeAttemptStatus.SCHEDULED));
        NodeInvocation externalizedInvocation =
                externalized.traversals().get(traversalId).invocations().get(invocationId);
        assertEquals(NodeInvocationStatus.RUNNING, externalizedInvocation.status());
        assertEquals(List.of(firstAttemptId, secondAttemptId),
                externalizedInvocation.attempts().stream().map(NodeAttempt::attemptId).toList());
        assertEquals(NodeAttemptCompletion.WAIT, externalizedInvocation.attempts().getFirst().completion());
        assertEquals(2, externalizedInvocation.attempts().getLast().ordinal());
    }

    @Test
    void failedAttemptCanRetryWithoutFailingItsInvocation() {
        UUID traversalId = id(12);
        UUID invocationId = id(13);
        UUID firstAttemptId = id(14);
        UUID retryAttemptId = id(15);
        ProcessInstance afterFailure = runningProcess(traversalId)
                .addInvocation(traversalId, scheduledInvocation(invocationId, "call", Set.of()))
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.RUNNING)
                .addAttempt(traversalId, invocationId,
                        new NodeAttempt(firstAttemptId, 1, NodeAttemptStatus.SCHEDULED))
                .transitionAttempt(traversalId, invocationId, firstAttemptId, NodeAttemptStatus.RUNNING)
                .transitionAttempt(traversalId, invocationId, firstAttemptId, NodeAttemptStatus.FAILED);

        ProcessInstance retried = afterFailure.addAttempt(traversalId, invocationId,
                new NodeAttempt(retryAttemptId, 2, NodeAttemptStatus.SCHEDULED));
        NodeInvocation invocation = retried.traversals().get(traversalId).invocations().get(invocationId);
        assertEquals(NodeInvocationStatus.RUNNING, invocation.status());
        assertEquals(List.of(1, 2), invocation.attempts().stream().map(NodeAttempt::ordinal).toList());
        assertThrows(IllegalArgumentException.class, () -> afterFailure.addAttempt(traversalId, invocationId,
                new NodeAttempt(retryAttemptId, 3, NodeAttemptStatus.SCHEDULED)));
    }

    @Test
    void preservesSameNodeReentryAndMultiParentFanInWithoutConflatingInvocations() {
        UUID firstTraversalId = id(22);
        UUID leftId = id(23);
        UUID rightId = id(24);
        UUID joinId = id(25);
        ProcessInstance firstTraversal = runningProcess(firstTraversalId)
                .addInvocation(firstTraversalId, scheduledInvocation(leftId, "work", Set.of()))
                .addInvocation(firstTraversalId, scheduledInvocation(rightId, "work", Set.of()))
                .addInvocation(firstTraversalId, scheduledInvocation(joinId, "join", Set.of(leftId, rightId)));

        UUID reentryTraversalId = id(26);
        UUID reentryId = id(27);
        ProcessInstance reentered = firstTraversal
                .addTraversal(new Traversal(reentryTraversalId, "work", TraversalStatus.ACCEPTED, Map.of()))
                .transitionTraversal(reentryTraversalId, TraversalStatus.RUNNING)
                .addInvocation(reentryTraversalId,
                        scheduledInvocation(reentryId, "work", Set.of(joinId)));

        assertEquals(2, reentered.traversals().size());
        assertEquals(3, reentered.traversals().get(firstTraversalId).invocations().size());
        assertEquals(Set.of(leftId, rightId),
                reentered.traversals().get(firstTraversalId).invocations().get(joinId).parentInvocationIds());
        assertEquals(Set.of(joinId),
                reentered.traversals().get(reentryTraversalId).invocations().get(reentryId).parentInvocationIds());
        assertFalse(leftId.equals(rightId));
        assertFalse(leftId.equals(reentryId));
    }

    @Test
    void rejectsForeignCyclicAndNonIngressCrossTraversalCausality() {
        UUID traversalId = id(32);
        ProcessInstance running = runningProcess(traversalId);
        assertThrows(IllegalArgumentException.class, () -> running.addInvocation(traversalId,
                scheduledInvocation(id(33), "join", Set.of(id(999)))));

        UUID firstId = id(34);
        UUID secondId = id(35);
        var cyclicInvocations = new LinkedHashMap<UUID, NodeInvocation>();
        cyclicInvocations.put(firstId, scheduledInvocation(firstId, "a", Set.of(secondId)));
        cyclicInvocations.put(secondId, scheduledInvocation(secondId, "b", Set.of(firstId)));
        Traversal cyclic = new Traversal(traversalId, "a", TraversalStatus.RUNNING, cyclicInvocations);
        assertThrows(IllegalArgumentException.class, () ->
                new ProcessInstance(id(31), ProcessInstanceStatus.RUNNING, Map.of(traversalId, cyclic)));

        ProcessInstance withParent = running.addInvocation(traversalId,
                scheduledInvocation(firstId, "root", Set.of()));
        UUID reentryTraversalId = id(36);
        ProcessInstance withReentry = withParent
                .addTraversal(new Traversal(reentryTraversalId, "expected-ingress",
                        TraversalStatus.ACCEPTED, Map.of()))
                .transitionTraversal(reentryTraversalId, TraversalStatus.RUNNING);
        assertThrows(IllegalArgumentException.class, () -> withReentry.addInvocation(reentryTraversalId,
                scheduledInvocation(id(37), "not-the-ingress", Set.of(firstId))));
    }

    @Test
    void terminalParentsRejectChildMutationAndCollectionsAreImmutable() {
        UUID traversalId = id(42);
        NodeInvocation scheduled = scheduledInvocation(id(45), "node", Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> new NodeInvocation(id(46), "node", null, NodeInvocationStatus.COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new Traversal(
                traversalId, "entry", TraversalStatus.COMPLETED, Map.of(scheduled.invocationId(), scheduled)));
        Traversal runningTraversal = new Traversal(
                traversalId, "entry", TraversalStatus.RUNNING, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new ProcessInstance(
                id(41), ProcessInstanceStatus.COMPLETED, Map.of(traversalId, runningTraversal)));

        ProcessInstance failed = runningProcess(traversalId).transitionTo(ProcessInstanceStatus.FAILED);
        assertThrows(IllegalStateException.class, () -> failed.addTraversal(
                new Traversal(id(43), "entry", TraversalStatus.ACCEPTED, Map.of())));
        assertThrows(IllegalStateException.class, () ->
                failed.transitionTraversal(traversalId, TraversalStatus.FAILED));
        assertThrows(UnsupportedOperationException.class,
                () -> failed.traversals().put(id(44),
                        new Traversal(id(44), "entry", TraversalStatus.ACCEPTED, Map.of())));

        NodeInvocation invocation = scheduledInvocation(id(47), "node", Set.of(id(48)));
        assertThrows(UnsupportedOperationException.class, () -> invocation.parentInvocationIds().add(id(47)));
        assertSame(invocation.parentInvocationIds(), invocation.parentInvocationIds());
    }

    private static ProcessInstance runningProcess(UUID traversalId) {
        return new ProcessInstance(id(1), ProcessInstanceStatus.ACCEPTED, Map.of())
                .addTraversal(new Traversal(traversalId, "entry", TraversalStatus.ACCEPTED, Map.of()))
                .transitionTo(ProcessInstanceStatus.RUNNING)
                .transitionTraversal(traversalId, TraversalStatus.RUNNING);
    }

    private static NodeInvocation scheduledInvocation(UUID invocationId, String nodeId, Set<UUID> parents) {
        return new NodeInvocation(invocationId, nodeId, parents, NodeInvocationStatus.SCHEDULED, List.of());
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
