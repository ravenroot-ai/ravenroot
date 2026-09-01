package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionTransitionTest {

    private final UUID processInstanceId = UUID.randomUUID();
    private final UUID traversalId = UUID.randomUUID();
    private final UUID invocationId = UUID.randomUUID();
    private final GraphVersionPin pin = new GraphVersionPin("graph-v1");

    @Test
    void foldingTheFullLifecycleProducesTheSameAggregateAsCallingTheMutatorsDirectly() {
        var attemptId = UUID.randomUUID();
        var transitions = List.<ExecutionTransition>of(
                new ExecutionTransition.ProcessCreated(accepted(), pin),
                new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING),
                new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING),
                new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, "work", null, NodeInvocationStatus.SCHEDULED)),
                new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING),
                new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)),
                new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING));

        ProcessInstance folded = null;
        for (ExecutionTransition transition : transitions) {
            folded = transition.applyTo(folded);
        }

        ProcessInstance direct = accepted()
                .transitionTo(ProcessInstanceStatus.RUNNING)
                .transitionTraversal(traversalId, TraversalStatus.RUNNING)
                .addInvocation(traversalId,
                        new NodeInvocation(invocationId, "work", null, NodeInvocationStatus.SCHEDULED))
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.RUNNING)
                .addAttempt(traversalId, invocationId, new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED))
                .transitionAttempt(traversalId, invocationId, attemptId, NodeAttemptStatus.RUNNING);

        assertEquals(direct, folded);
    }

    @Test
    void illegalTransitionsAreRejectedByTheAggregateSoNoStoreCanPersistThem() {
        ProcessInstance running = accepted().transitionTo(ProcessInstanceStatus.RUNNING);

        // This is the whole point of transitions-as-the-write-unit: a field patch could have written
        // COMPLETED over a process with an incomplete traversal; a transition cannot.
        assertThrows(IllegalStateException.class,
                () -> new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.COMPLETED).applyTo(running));

        ProcessInstance failed = running.transitionTo(ProcessInstanceStatus.FAILED);
        assertThrows(IllegalStateException.class,
                () -> new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING).applyTo(failed));
    }

    @Test
    void processCreatedIsLegalOnlyAgainstAnAbsentInstance() {
        ProcessInstance initial = accepted();
        var created = new ExecutionTransition.ProcessCreated(initial, pin);

        assertSame(initial, created.applyTo(null));
        assertThrows(IllegalStateException.class, () -> created.applyTo(initial));
    }

    @Test
    void everyOtherTransitionRequiresAnExistingInstance() {
        assertThrows(IllegalStateException.class,
                () -> new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING).applyTo(null));
        assertThrows(IllegalStateException.class,
                () -> new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING)
                        .applyTo(null));
    }

    @Test
    void resumeTransitionsMirrorBothResumeShapes() {
        var attemptId = UUID.randomUUID();
        ProcessInstance waiting = accepted()
                .transitionTo(ProcessInstanceStatus.RUNNING)
                .transitionTraversal(traversalId, TraversalStatus.RUNNING)
                .addInvocation(traversalId,
                        new NodeInvocation(invocationId, "work", null, NodeInvocationStatus.SCHEDULED))
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.RUNNING)
                .addAttempt(traversalId, invocationId, new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED))
                .transitionAttempt(traversalId, invocationId, attemptId, NodeAttemptStatus.RUNNING)
                .transitionAttempt(traversalId, invocationId, attemptId, NodeAttemptStatus.WAITING)
                .transitionInvocation(traversalId, invocationId, NodeInvocationStatus.WAITING);

        ProcessInstance resumedInPlace =
                new ExecutionTransition.AttemptResumed(traversalId, invocationId, attemptId).applyTo(waiting);
        assertEquals(NodeInvocationStatus.RUNNING,
                resumedInPlace.traversals().get(traversalId).invocations().get(invocationId).status());

        var nextAttempt = new NodeAttempt(UUID.randomUUID(), 2, NodeAttemptStatus.SCHEDULED);
        ProcessInstance resumedExternally =
                new ExecutionTransition.ResumedWithNewAttempt(traversalId, invocationId, nextAttempt)
                        .applyTo(waiting);
        assertEquals(2,
                resumedExternally.traversals().get(traversalId).invocations().get(invocationId).attempts().size());
    }

    @Test
    void rejectsNullComponentsAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionTransition.ProcessCreated(null, pin));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionTransition.ProcessCreated(accepted(), null));
    }

    private ProcessInstance accepted() {
        return new ProcessInstance(processInstanceId, ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
    }
}
