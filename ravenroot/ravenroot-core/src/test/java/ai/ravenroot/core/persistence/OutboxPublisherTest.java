package ai.ravenroot.core.persistence;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies PERS-07's retry-safe at-least-once publisher behavior. */
class OutboxPublisherTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";
    private static final String DESTINATION = "sse";

    private final MutableClock clock = new MutableClock(EPOCH);
    private final InMemoryExecutionStore store = new InMemoryExecutionStore(clock);
    private UUID traversalId;

    @Test
    void deliversEveryJournalledEventOnceAndThenReportsCaughtUp() {
        ExecutionKey key = seedInstance();
        publish(key, "a", "b", "c");

        var delivered = new ArrayList<UUID>();
        var publisher = new OutboxPublisher(store, TENANT, DESTINATION,
                record -> delivered.add(record.envelope().eventId()), 10);

        assertEquals(3, publisher.publishOnce());
        assertEquals(3, delivered.size());
        assertEquals(0, publisher.publishOnce(), "a caught-up destination delivers nothing");
        assertEquals(3, delivered.size());
    }

    @Test
    void deliveryHonoursTheBatchSizeAndResumesExactlyWhereItStopped() {
        ExecutionKey key = seedInstance();
        publish(key, "a", "b", "c", "d", "e");

        var delivered = new ArrayList<UUID>();
        var publisher = new OutboxPublisher(store, TENANT, DESTINATION,
                record -> delivered.add(record.envelope().eventId()), 2);

        assertEquals(2, publisher.publishOnce());
        assertEquals(2, publisher.publishOnce());
        assertEquals(1, publisher.publishOnce());
        assertEquals(0, publisher.publishOnce());
        assertEquals(5, delivered.size());
        assertEquals(delivered.size(), delivered.stream().distinct().count(),
                "resuming from the cursor must not re-deliver what the previous batch covered");
    }

    @Test
    void aFailedSinkLeavesTheCursorWhereItWasSoTheBatchIsRetriedRatherThanLost() {
        ExecutionKey key = seedInstance();
        publish(key, "a", "b");

        var delivered = new ArrayList<UUID>();
        var exploding = new OutboxPublisher(store, TENANT, DESTINATION, record -> {
            delivered.add(record.envelope().eventId());
            throw new IllegalStateException("the transport is down");
        }, 10);
        assertThrows(IllegalStateException.class, exploding::publishOnce);

        assertEquals(0L, await(store.outboxCursor(TENANT, DESTINATION)).deliveredThrough(),
                "marking records delivered because the attempt was made is how events are lost: the "
                        + "cursor must record what landed, not what was tried");

        var recovering = new ArrayList<UUID>();
        var healthy = new OutboxPublisher(store, TENANT, DESTINATION,
                record -> recovering.add(record.envelope().eventId()), 10);
        assertEquals(2, healthy.publishOnce(), "both events are still there to be delivered");
        assertEquals(2, recovering.size());
    }

    @Test
    void redeliveryIsAbsorbedByAConsumerInboxRatherThanByThePublisher() {
        ExecutionKey key = seedInstance();
        publish(key, "a");

        var effects = new ArrayList<UUID>();
        OutboxPublisher.EventSink guarded = record -> {
            if (await(store.recordInboxDelivery(TENANT, "sse-projection", record.envelope().eventId(),
                    Duration.ofHours(1)))) {
                effects.add(record.envelope().eventId());
            }
        };

        // Two publishers on the same destination race; the loser's advance conflicts, so it re-reads.
        var first = new OutboxPublisher(store, TENANT, DESTINATION, guarded, 10);
        var second = new OutboxPublisher(store, TENANT, DESTINATION, guarded, 10);
        assertEquals(1, first.publishOnce());
        assertEquals(0, second.publishOnce(), "the second finds the cursor already advanced");
        assertEquals(1, effects.size(), "and the effect happened exactly once");
    }

    @Test
    void drainStopsWhenCaughtUpRatherThanSpinningToItsBound() {
        ExecutionKey key = seedInstance();
        publish(key, "a", "b", "c");
        var passes = new ArrayList<Integer>();
        var publisher = new OutboxPublisher(store, TENANT, DESTINATION,
                record -> passes.add(passes.size()), 1);
        assertEquals(3, publisher.drain(100));
        assertEquals(3, passes.size());
        assertFalse(passes.isEmpty());
    }

    private ExecutionKey seedInstance() {
        var key = new ExecutionKey(TENANT, UUID.randomUUID());
        UUID traversalId = UUID.randomUUID();
        await(store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(
                        new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.ACCEPTED,
                                Map.of(traversalId, new Traversal(traversalId, "start",
                                        TraversalStatus.ACCEPTED, Map.of()))),
                        new GraphVersionPin("graph-v1")))
                .build()));
        this.traversalId = traversalId;
        return key;
    }

    private void publish(ExecutionKey key, String... eventTypes) {
        StoredProcessInstance current = await(store.load(key));
        ExecutionBatch.Builder builder = ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(current.revision()));
        for (String eventType : eventTypes) {
            builder.publish(EventEnvelope.of(UUID.randomUUID(), TENANT, eventType, key.processInstanceId(),
                    traversalId, null, null, null, "request-1", "graph-v1", clock.instant(),
                    OpaquePayload.of(eventType.getBytes(StandardCharsets.UTF_8), "application/json")));
        }
        await(store.apply(builder.build()));
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
