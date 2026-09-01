package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeCommand;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.recovery.ExecutionRecoveryService;
import ai.ravenroot.core.recovery.RecoveryDispatcher;
import ai.ravenroot.core.recovery.RecoveryOutcome;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the recovery loop does with the mail nodes' declarations (PERS-04, ADR 0022).
 *
 * <h2>Why this drives a real sweep instead of reading the descriptor back</h2>
 * <p>A test asserting "mail.imap.query declares recovery.repeatable" verifies the line that declares
 * it and nothing else — it would pass just as happily if {@code ExecutionRecoveryService} ignored the
 * value entirely. The interesting claim is that a declaration changes an <em>engine decision</em>, so
 * the whole path is exercised: the production node package, registered through {@link NodePackages}
 * into a {@link BehaviorRegistry}, resolved by {@link RepeatabilityDeclarations#fromGraph}, consumed
 * by a sweep over a real {@link InMemoryExecutionStore} holding two ambiguous attempts.</p>
 *
 * <h2>The two nodes carry the same graph value on purpose</h2>
 * <p>Both are authored {@code recovery.repeatable = repeatable}. They must nonetheless reach opposite
 * dispositions, because {@code mail.send}'s descriptor does not declare the property and therefore
 * nothing the graph says about it is a declaration. Holding the graph constant is what makes the
 * difference attributable to the catalog rather than to the document.</p>
 */
class MailRecoveryRepeatabilityTest {

    private static final String TENANT = "acme";
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String QUERY_NODE = "read-the-mailbox";
    private static final String SEND_NODE = "send-the-mail";

    private final MovableClock clock = new MovableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);
    private final BehaviorRegistry catalog =
            NodePackages.register(new BehaviorRegistry(), new MailNodePackage());

    @AfterEach
    void closeStore() {
        store.close();
    }

    /**
     * The engine decision, not the declaration: one ambiguous attempt is sent again and the other is
     * parked, from one graph in which both nodes say the same thing.
     */
    @Test
    void anAmbiguousQueryIsSentAgainWhileAnAmbiguousSendParksOnTheSameAuthoredValue() {
        Fixture query = ambiguousAttemptOn(QUERY_NODE);
        Fixture send = ambiguousAttemptOn(SEND_NODE);
        var dispatcher = new RecordingDispatcher();

        Map<ExecutionKey, RecoveryOutcome> outcomes = byKey(new ExecutionRecoveryService(store,
                List.of(TENANT), "recovery-1", 10, TTL, declarationsFromCatalog(), dispatcher).sweepOnce());

        var reDispatched = assertInstanceOf(RecoveryOutcome.ReDispatched.class, outcomes.get(query.key),
                "the query is read-only and peeked, and its instance declares that; the engine must "
                        + "act on the declaration rather than park work nobody needs to look at");
        assertEquals(query.attemptId, reDispatched.attemptId());
        assertEquals(List.of(query.attemptId.toString()), dispatcher.sent,
                "only the query is repeated, and it is repeated under its own attempt id");

        var parked = assertInstanceOf(RecoveryOutcome.Parked.class, outcomes.get(send.key));
        assertEquals(AttemptRepeatability.UNDECLARED, parked.declaration(),
                "mail.send's descriptor declares nothing, so the identical authored value is inert "
                        + "rather than authoritative — an SMTP resend past DATA is a second message");
        assertEquals(NodeAttemptStatus.PARKED, currentAttempt(send).status());
        assertFalse(parked.cause().isBlank());
    }

    /**
     * The roster, pinned. A property of a <em>type</em> has as many unguarded readers as the type has
     * declarers, so the next mail node added has to make this choice deliberately rather than inherit
     * whichever neighbour it was copied from.
     */
    @Test
    void exactlyTheMailNodesWhoseRepeatIsHarmlessOfferTheContract() {
        assertTrue(RecoveryRepeatabilityProperty.declaredBy(descriptor("mail.imap.query")));
        assertTrue(RecoveryRepeatabilityProperty.declaredBy(descriptor("mail.imap.move")));
        assertTrue(RecoveryRepeatabilityProperty.declaredBy(descriptor("mail.imap.delete")));
        assertFalse(RecoveryRepeatabilityProperty.declaredBy(descriptor("mail.send")),
                "declaring it here would offer an author a value no SMTP send can honestly take");
        assertFalse(RecoveryRepeatabilityProperty.declaredBy(descriptor("mail.imap.consume")),
                "the source is armed once; durable message admission, not node-attempt replay, owns repetition");
        assertEquals(Set.of("mail.imap.query", "mail.imap.consume", "mail.imap.move", "mail.imap.delete", "mail.send"),
                new MailNodePackage().behaviors().stream()
                        .map(behavior -> behavior.descriptor().behavior()).collect(java.util.stream.Collectors.toSet()),
                "a mail node was added or removed; decide its repeatability rather than leaving it out");
    }

    /**
     * The declaring node must not carry a default, in either direction.
     *
     * <p>A default of {@code repeatable} is refused outright at registration, by
     * {@code RecoveryRepeatabilityProperty.validateShape}. A default of {@code not-repeatable} is not
     * refused, and would <em>reach</em> the engine rather than sit inertly in the catalog: the editor
     * seeds a new node's properties from {@code defaultValue} and writes them into the document, so
     * the value arrives at {@code read} by the ordinary route. It is wrong for a different reason —
     * it would attribute a considered refusal to an author who never made one, and only on nodes
     * created after the change, leaving hand-authored and pre-existing ones {@code UNDECLARED}.</p>
     */
    @Test
    void theDeclaringNodeLeavesTheDecisionEmpty() {
        var declared = descriptor("mail.imap.query").properties().stream()
                .filter(property -> RecoveryRepeatabilityProperty.NAME.equals(property.name()))
                .findFirst().orElseThrow();
        assertEquals("", declared.defaultValue());
        assertEquals(RecoveryRepeatabilityProperty.ALLOWED_VALUES, declared.allowedValues());
    }

    // ------------------------------------------------------------------ fixtures

    private NodeTypeDescriptor descriptor(String behavior) {
        return catalog.descriptor(behavior).orElseThrow();
    }

    /** The graph both nodes live in: same authored value, different types. */
    private RepeatabilityDeclarations declarationsFromCatalog() {
        Map<String, Object> declaredRepeatable =
                Map.of(RecoveryRepeatabilityProperty.NAME, RecoveryRepeatabilityProperty.REPEATABLE);
        return RepeatabilityDeclarations.fromGraph(
                List.of(new GraphNode(QUERY_NODE, NodeKind.BEHAVIOR, "mail.imap.query", declaredRepeatable),
                        new GraphNode(SEND_NODE, NodeKind.BEHAVIOR, "mail.send", declaredRepeatable)),
                catalog::descriptor);
    }

    private record Fixture(ExecutionKey key, UUID traversalId, UUID invocationId, UUID attemptId) {
    }

    /** A claimed attempt driven to RUNNING and then abandoned: a crash with the outcome unknown. */
    private Fixture ambiguousAttemptOn(String nodeId) {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        UUID invocationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        var accepted = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                Map.of(traversalId, new Traversal(traversalId, "start", TraversalStatus.ACCEPTED, Map.of())));
        StoredProcessInstance created = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(accepted, new GraphVersionPin("graph-v1")))
                .build()));
        StoredProcessInstance scheduled = await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversalId, TraversalStatus.RUNNING))
                .apply(new ExecutionTransition.InvocationAdded(traversalId,
                        new NodeInvocation(invocationId, nodeId, Set.of(), NodeInvocationStatus.SCHEDULED,
                                List.of(), NodeCommand.PROCESS)))
                .apply(new ExecutionTransition.InvocationTransitioned(traversalId, invocationId,
                        NodeInvocationStatus.RUNNING))
                .apply(new ExecutionTransition.AttemptAdded(traversalId, invocationId,
                        new NodeAttempt(attemptId, 1, NodeAttemptStatus.SCHEDULED)))
                .build()));

        PendingWork claimed = await(store.claimPendingWork(TENANT, "dead-worker", 10, TTL)).stream()
                .filter(item -> item.key().equals(key)).findFirst().orElseThrow();
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(scheduled.revision()))
                .fencedBy(claimed.fencingToken())
                .apply(new ExecutionTransition.AttemptTransitioned(traversalId, invocationId, attemptId,
                        NodeAttemptStatus.RUNNING))
                .build()));
        clock.advance(TTL.plusSeconds(1));
        return new Fixture(key, traversalId, invocationId, attemptId);
    }

    private NodeAttempt currentAttempt(Fixture fixture) {
        return await(store.load(fixture.key)).state().traversals().get(fixture.traversalId)
                .invocations().get(fixture.invocationId).attempts().getLast();
    }

    private static Map<ExecutionKey, RecoveryOutcome> byKey(List<RecoveryOutcome> outcomes) {
        var byKey = new HashMap<ExecutionKey, RecoveryOutcome>();
        for (RecoveryOutcome outcome : outcomes) {
            byKey.put(outcome.key(), outcome);
        }
        assertEquals(outcomes.size(), byKey.size(), "one outcome per instance, in any claim order");
        return byKey;
    }

    private <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static final class RecordingDispatcher implements RecoveryDispatcher {
        private final List<String> sent = new ArrayList<>();

        @Override
        public boolean canDispatch(PendingWork item) {
            return true;
        }

        @Override
        public void dispatch(PendingWork item, String idempotencyKey) {
            sent.add(idempotencyKey);
        }
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
