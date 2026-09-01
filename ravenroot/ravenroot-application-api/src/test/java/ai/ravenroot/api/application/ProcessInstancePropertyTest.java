package ai.ravenroot.api.application;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessInstancePropertyTest {
    @Test
    void rejectsImpossibleCompletedSnapshotsAtEveryPublicAggregateLevel() {
        UUID invocationId = id(30);
        NodeInvocation runningInvocation = new NodeInvocation(invocationId, "node", null,
                NodeInvocationStatus.SCHEDULED);
        Traversal runningTraversal = new Traversal(id(31), "entry", TraversalStatus.RUNNING,
                Map.of(invocationId, runningInvocation));

        assertThrows(IllegalArgumentException.class, () -> new NodeInvocation(id(32), "node", null,
                NodeInvocationStatus.COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> new Traversal(id(33), "entry",
                TraversalStatus.COMPLETED, Map.of(invocationId, runningInvocation)));
        assertThrows(IllegalArgumentException.class, () -> new ProcessInstance(id(34),
                ProcessInstanceStatus.COMPLETED, Map.of(runningTraversal.traversalId(), runningTraversal)));
    }

    /**
     * The CORE-03 relaxation, pinned at the level that decides it.
     *
     * <p>The invariant moved from "every invocation COMPLETED" to "every invocation terminal, and at
     * least one COMPLETED". The test above establishes only that a non-terminal invocation is still
     * refused: a SCHEDULED invocation is rejected for a reason that has nothing to do with quorums.
     * What has to be pinned is the pair the relaxation
     * actually turns on: a FAILED invocation is now <em>permitted</em> in a completed traversal, and
     * a traversal of nothing but FAILED invocations is still refused.</p>
     *
     * <p>Pinned on the record because {@code Traversal}'s constructor is what classifies a persisted
     * row set as corrupt. The runtime cannot currently produce an all-failed completed traversal, so
     * this is the boundary itself rather than a reachable path — and the boundary is what a store
     * reload is checked against.</p>
     */
    @Test
    void permitsAFailedInvocationInACompletedTraversalButNotOnlyFailedOnes() {
        UUID failedId = id(40);
        UUID succeededId = id(41);
        NodeInvocation failed = new NodeInvocation(failedId, "branch", null, NodeInvocationStatus.FAILED);
        NodeInvocation succeeded = new NodeInvocation(succeededId, "quorum", Set.of(),
                NodeInvocationStatus.COMPLETED,
                List.of(new NodeAttempt(id(42), 1, NodeAttemptStatus.COMPLETED)));

        // A quorum succeeded over a branch that failed: exactly what CORE-03 has to allow.
        var mixed = assertDoesNotThrow(() -> new Traversal(id(43), "entry", TraversalStatus.COMPLETED,
                Map.of(failedId, failed, succeededId, succeeded)));
        assertEquals(TraversalStatus.COMPLETED, mixed.status());

        // Nothing succeeded, so there is no result and no sense in which the traversal completed.
        assertThrows(IllegalArgumentException.class, () -> new Traversal(id(44), "entry",
                TraversalStatus.COMPLETED, Map.of(failedId, failed)));

        // And the same refusal through the transition, not only through the constructor.
        Traversal running = new Traversal(id(45), "entry", TraversalStatus.ACCEPTED, Map.of())
                .transitionTo(TraversalStatus.RUNNING)
                .addInvocation(new NodeInvocation(failedId, "branch", null, NodeInvocationStatus.SCHEDULED))
                .replaceInvocation(failed);
        assertThrows(IllegalStateException.class, () -> running.transitionTo(TraversalStatus.COMPLETED));

        // A traversal that has run nothing at all is untouched by the rule.
        assertDoesNotThrow(() -> new Traversal(id(46), "entry", TraversalStatus.COMPLETED, Map.of()));
    }

    @Property(tries = 200)
    void processTransitionsMatchThePublicStateMachine(
            @ForAll("processStatuses") List<ProcessInstanceStatus> requested) {
        ProcessInstance instance = new ProcessInstance(id(1), ProcessInstanceStatus.ACCEPTED, Map.of());
        ProcessInstanceStatus model = ProcessInstanceStatus.ACCEPTED;

        for (ProcessInstanceStatus next : requested) {
            if (model.canTransitionTo(next)) {
                ProcessInstance snapshot = instance;
                instance = assertDoesNotThrow(() -> snapshot.transitionTo(next));
                model = next;
            } else {
                ProcessInstance snapshot = instance;
                assertThrows(IllegalStateException.class, () -> snapshot.transitionTo(next));
            }
        }
        assertEquals(model, instance.status());
    }

    @Property(tries = 100)
    void ordinaryWaitResumeCyclesKeepTheSameHierarchyAndAttempt(
            @ForAll @IntRange(min = 1, max = 20) int cycles) {
        UUID traversalId = id(2);
        UUID invocationId = id(3);
        UUID attemptId = id(4);
        ProcessInstance instance = runningProcess(traversalId)
                .addInvocation(traversalId, new NodeInvocation(invocationId, "review", null,
                        NodeInvocationStatus.SCHEDULED))
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.RUNNING)
                .addAttempt(traversalId, invocationId, new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED))
                .transitionAttempt(traversalId, invocationId, attemptId, NodeAttemptStatus.RUNNING);

        for (int cycle = 0; cycle < cycles; cycle++) {
            instance = instance.transitionAttempt(traversalId, invocationId, attemptId, NodeAttemptStatus.WAITING)
                    .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.WAITING)
                    .resumeAttempt(traversalId, invocationId, attemptId);
            NodeInvocation invocation = instance.traversals().get(traversalId).invocations().get(invocationId);
            assertEquals(invocationId, invocation.invocationId());
            assertEquals(attemptId, invocation.attempts().getFirst().attemptId());
            assertEquals(1, invocation.attempts().size());
        }
    }

    @Property(tries = 100)
    void failedAttemptsAppendContiguouslyWithDistinctIds(
            @ForAll @IntRange(min = 1, max = 20) int attempts) {
        UUID traversalId = id(20);
        UUID invocationId = id(21);
        ProcessInstance instance = runningProcess(traversalId)
                .addInvocation(traversalId, new NodeInvocation(invocationId, "retry", null,
                        NodeInvocationStatus.SCHEDULED))
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.RUNNING);

        for (int ordinal = 1; ordinal <= attempts; ordinal++) {
            UUID attemptId = id(100L + ordinal);
            instance = instance.addAttempt(traversalId, invocationId,
                            new NodeAttempt(attemptId, ordinal, NodeAttemptStatus.SCHEDULED))
                    .transitionAttempt(traversalId, invocationId, attemptId, NodeAttemptStatus.RUNNING);
            if (ordinal < attempts) {
                instance = instance.transitionAttempt(traversalId, invocationId, attemptId, NodeAttemptStatus.FAILED);
            }
        }
        List<NodeAttempt> actual = instance.traversals().get(traversalId).invocations().get(invocationId).attempts();
        assertEquals(attempts, actual.size());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, attempts).boxed().toList(),
                actual.stream().map(NodeAttempt::ordinal).toList());
        assertEquals(attempts, actual.stream().map(NodeAttempt::attemptId).distinct().count());
    }

    @Property(tries = 100)
    void causalParentSetsAreImmutableAcrossArrivalPermutationsAndReentryIsIngressOnly(
            @ForAll @IntRange(min = 2, max = 8) int parentCount,
            @ForAll boolean reverseArrival) {
        UUID firstTraversalId = id(200);
        ProcessInstance instance = runningProcess(firstTraversalId);
        List<UUID> parentIds = new ArrayList<>();
        for (int index = 0; index < parentCount; index++) {
            UUID parentId = id(201L + index);
            parentIds.add(parentId);
            instance = instance.addInvocation(firstTraversalId, new NodeInvocation(parentId, "source-" + index,
                    Set.of(), NodeInvocationStatus.SCHEDULED, List.of()));
        }
        List<UUID> arrivalOrder = new ArrayList<>(parentIds);
        if (reverseArrival) java.util.Collections.reverse(arrivalOrder);
        Set<UUID> permutedParents = new LinkedHashSet<>(arrivalOrder);
        UUID joinId = id(220);
        instance = instance.addInvocation(firstTraversalId, new NodeInvocation(joinId, "join", permutedParents,
                NodeInvocationStatus.SCHEDULED, List.of()));
        NodeInvocation join = instance.traversals().get(firstTraversalId).invocations().get(joinId);
        assertEquals(Set.copyOf(parentIds), join.parentInvocationIds());
        assertThrows(UnsupportedOperationException.class, () -> join.parentInvocationIds().add(id(299)));

        UUID reentryTraversalId = id(300);
        instance = instance.addTraversal(new Traversal(reentryTraversalId, "reentry", TraversalStatus.ACCEPTED,
                        Map.of()))
                .transitionTraversal(reentryTraversalId, TraversalStatus.RUNNING)
                .addInvocation(reentryTraversalId, new NodeInvocation(id(301), "reentry", Set.of(joinId),
                        NodeInvocationStatus.SCHEDULED, List.of()));
        ProcessInstance reentered = instance;
        assertThrows(IllegalArgumentException.class, () -> reentered.addInvocation(reentryTraversalId,
                new NodeInvocation(id(302), "later", Set.of(joinId), NodeInvocationStatus.SCHEDULED, List.of())));
        assertThrows(IllegalArgumentException.class, () -> reentered.addInvocation(firstTraversalId,
                new NodeInvocation(id(303), "foreign", Set.of(id(999)), NodeInvocationStatus.SCHEDULED, List.of())));
    }

    @Test
    void rejectsGlobalAttemptReuseAndAllMutationBelowATerminalProcess() {
        UUID traversalId = id(400);
        UUID firstInvocation = id(401);
        UUID secondInvocation = id(402);
        UUID reusedAttempt = id(403);
        ProcessInstance running = runningProcess(traversalId)
                .addInvocation(traversalId, new NodeInvocation(firstInvocation, "first", null,
                        NodeInvocationStatus.SCHEDULED))
                .addInvocation(traversalId, new NodeInvocation(secondInvocation, "second", null,
                        NodeInvocationStatus.SCHEDULED))
                .transitionInvocation(traversalId, firstInvocation, NodeInvocationStatus.RUNNING)
                .transitionInvocation(traversalId, secondInvocation, NodeInvocationStatus.RUNNING)
                .addAttempt(traversalId, firstInvocation, new NodeAttempt(reusedAttempt, 1,
                        NodeAttemptStatus.SCHEDULED));
        assertThrows(IllegalArgumentException.class, () -> running.addAttempt(traversalId, secondInvocation,
                new NodeAttempt(reusedAttempt, 1, NodeAttemptStatus.SCHEDULED)));

        ProcessInstance terminal = running.transitionTo(ProcessInstanceStatus.FAILED);
        assertThrows(IllegalStateException.class, () -> terminal.addTraversal(
                new Traversal(id(404), "new", TraversalStatus.ACCEPTED, Map.of())));
        assertThrows(IllegalStateException.class, () -> terminal.transitionInvocation(traversalId, firstInvocation,
                NodeInvocationStatus.FAILED));
        assertThrows(IllegalStateException.class, () -> terminal.transitionAttempt(traversalId, firstInvocation,
                reusedAttempt, NodeAttemptStatus.FAILED));
    }

    @Provide
    Arbitrary<List<ProcessInstanceStatus>> processStatuses() {
        return Arbitraries.of(ProcessInstanceStatus.values()).list().ofMaxSize(50);
    }

    private static ProcessInstance runningProcess(UUID traversalId) {
        return new ProcessInstance(id(1), ProcessInstanceStatus.ACCEPTED, Map.of())
                .addTraversal(new Traversal(traversalId, "entry", TraversalStatus.ACCEPTED, Map.of()))
                .transitionTo(ProcessInstanceStatus.RUNNING)
                .transitionTraversal(traversalId, TraversalStatus.RUNNING);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
