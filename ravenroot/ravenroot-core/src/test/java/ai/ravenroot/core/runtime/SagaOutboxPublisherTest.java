package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.node.service.SagaCommandTransport;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.SagaCommandIntent;
import ai.ravenroot.api.persistence.SagaDefinition;
import ai.ravenroot.api.persistence.SagaDisposition;
import ai.ravenroot.api.persistence.SagaOutboxStatus;
import ai.ravenroot.api.persistence.SagaRecoveryEnvelope;
import ai.ravenroot.api.persistence.SagaSnapshot;
import ai.ravenroot.api.persistence.SagaStepDefinition;
import ai.ravenroot.api.persistence.SagaStepSnapshot;
import ai.ravenroot.api.persistence.SagaStepStatus;
import ai.ravenroot.api.persistence.SagaWrite;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SagaOutboxPublisherTest {
    @Test
    void stalePendingCommandCannotPublishAfterSagaIsTerminal() throws Exception {
        MutableClock clock = new MutableClock();
        var store = new InMemoryExecutionStore(clock);
        ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversal = UUID.randomUUID(), sagaId = UUID.randomUUID(), occurrence = UUID.randomUUID();
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "start",
                        TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1"))).build())
                .toCompletableFuture().join();
        OpaquePayload payload = frozenPayload(key, traversal);
        String digest = sha256(payload);
        String operation = "forward:" + sagaId + ":publish:" + occurrence;
        var intent = new SagaCommandIntent(UUID.randomUUID(), sagaId, operation,
                "participant:amqp-inbox-v1:amqp.publish", "saga.forward.amqp-inbox-v1.v1",
                1, payload, digest, null, clock.instant(), 5);
        var definition = new SagaDefinition(1, "order", "a".repeat(64), "b".repeat(64), Map.of(
                "publish", new SagaStepDefinition("publish", "publish", "amqp-inbox-v1",
                        "cancel", List.of(), false, true)));
        var step = new SagaStepSnapshot(occurrence, "publish", UUID.randomUUID(), operation,
                "compensate:" + sagaId + ":publish:" + occurrence, digest,
                SagaStepStatus.COMPENSATED, new SagaRecoveryEnvelope(intent, null).encode(),
                "terminal tombstone", clock.instant());
        var snapshot = new SagaSnapshot(key, sagaId, traversal, definition, 1,
                SagaDisposition.COMPENSATED, true, Map.of(occurrence, step), null,
                clock.instant(), clock.instant(), "", true);
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(created.revision()))
                .writeSaga(new SagaWrite(UUID.randomUUID(), 0, snapshot)).enqueueSagaCommand(intent).build())
                .toCompletableFuture().join();

        var effects = new AtomicInteger();
        var authorityCalls = new AtomicInteger();
        var publisher = new SagaOutboxPublisher(store, new SagaCommandTransport() {
            @Override public CompletableFuture<BrokerResult> publish(SagaCommandIntent ignored) {
                effects.incrementAndGet();
                return CompletableFuture.completedFuture(new BrokerResult(true, "unexpected"));
            }
            @Override public CompletableFuture<Boolean> businessCompleted(SagaCommandIntent ignored) {
                effects.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }
        }, (ignoredKey, ignoredIntent) -> authorityCalls.incrementAndGet(), "recovery", 8,
                Duration.ofSeconds(5), Duration.ofSeconds(1), clock);

        assertEquals(SagaOutboxStatus.PENDING, publisher.runOnce("tenant-a").getFirst().status());
        assertEquals(0, authorityCalls.get());
        assertEquals(0, effects.get());
        assertEquals(SagaDisposition.COMPENSATED,
                store.loadSaga(key, sagaId).toCompletableFuture().join().orElseThrow().disposition());
    }

    @Test
    void currentAuthorityDenialPreventsIntactCommandPublishWithoutInventingSuccess() throws Exception {
        MutableClock clock = new MutableClock();
        var store = new InMemoryExecutionStore(clock);
        ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversal = UUID.randomUUID(), sagaId = UUID.randomUUID(), occurrence = UUID.randomUUID();
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "start",
                        TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1"))).build())
                .toCompletableFuture().join();
        var running = store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING))
                .build()).toCompletableFuture().join();
        OpaquePayload payload = frozenPayload(key, traversal);
        String digest = sha256(payload);
        String operation = "forward:" + sagaId + ":participant:" + occurrence;
        var intent = new SagaCommandIntent(UUID.randomUUID(), sagaId, operation,
                "participant:http-idempotency-v1:http-request", "saga.forward.http-idempotency-v1.v1",
                1, payload, digest, null, clock.instant(), 5);
        var definition = new SagaDefinition(1, "order", "a".repeat(64), "b".repeat(64), Map.of(
                "participant", new SagaStepDefinition("participant", "participant", "http-idempotency-v1",
                        null, List.of(), true, true)));
        var step = new SagaStepSnapshot(occurrence, "participant", UUID.randomUUID(), operation,
                "compensate:" + sagaId + ":participant:" + occurrence, digest,
                SagaStepStatus.OUTCOME_UNKNOWN, new SagaRecoveryEnvelope(intent, null).encode(),
                "response lost", clock.instant());
        var snapshot = new SagaSnapshot(key, sagaId, traversal, definition, 1, SagaDisposition.UNRESOLVED,
                false, Map.of(occurrence, step), null, clock.instant(), clock.instant(), "unknown", true);
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(running.revision()))
                .writeSaga(new SagaWrite(UUID.randomUUID(), 0, snapshot)).enqueueSagaCommand(intent).build())
                .toCompletableFuture().join();

        var participantEffects = new AtomicInteger();
        var authorityCalls = new AtomicInteger();
        SagaCommandTransport transport = new SagaCommandTransport() {
            @Override public CompletableFuture<BrokerResult> publish(SagaCommandIntent ignored) {
                participantEffects.incrementAndGet();
                return CompletableFuture.completedFuture(new BrokerResult(true, "accepted"));
            }
            @Override public CompletableFuture<Boolean> businessCompleted(SagaCommandIntent ignored) {
                participantEffects.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }
        };
        var publisher = new SagaOutboxPublisher(store, transport, (ignoredKey, ignoredIntent) -> {
            authorityCalls.incrementAndGet();
            throw new SecurityException("current grant revoked");
        }, "recovery", 8, Duration.ofSeconds(5), Duration.ofSeconds(1), clock);

        assertEquals(SagaOutboxStatus.PENDING, publisher.runOnce("tenant-a").getFirst().status());
        assertEquals(1, authorityCalls.get());
        assertEquals(0, participantEffects.get());
        assertEquals(SagaDisposition.UNRESOLVED,
                store.loadSaga(key, sagaId).toCompletableFuture().join().orElseThrow().disposition());
        assertEquals(ProcessInstanceStatus.RUNNING, store.load(key).toCompletableFuture().join().state().status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"security", "operation", "destination", "payload"})
    void alteredFrozenIdentityIsRefusedBeforeAuthorityOrParticipantEffect(String alteration) throws Exception {
        MutableClock clock = new MutableClock();
        var store = new InMemoryExecutionStore(clock);
        ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversal = UUID.randomUUID(), sagaId = UUID.randomUUID(), occurrence = UUID.randomUUID();
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "start",
                        TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1"))).build())
                .toCompletableFuture().join();
        var running = store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING))
                .build()).toCompletableFuture().join();
        OpaquePayload originalPayload = frozenPayload(key, traversal);
        String originalDigest = sha256(originalPayload);
        String operation = "forward:" + sagaId + ":participant:" + occurrence;
        var expected = new SagaCommandIntent(UUID.randomUUID(), sagaId, operation,
                "participant:http-idempotency-v1:http-request", "saga.forward.http-idempotency-v1.v1",
                1, originalPayload, originalDigest, null, clock.instant(), 5);
        OpaquePayload actualPayload = alteration.equals("security")
                ? frozenPayload(new ExecutionKey("tenant-b", key.processInstanceId()), traversal)
                : alteration.equals("payload")
                ? OpaquePayload.of("{\"changed\":true}".getBytes(StandardCharsets.UTF_8), "application/json")
                : originalPayload;
        var actual = new SagaCommandIntent(expected.messageId(), sagaId,
                alteration.equals("operation") ? operation + ":changed" : operation,
                alteration.equals("destination") ? "participant:changed:http-request" : expected.destination(),
                expected.commandType(), expected.schemaVersion(), actualPayload, sha256(actualPayload), null,
                clock.instant(), 5);
        var definition = new SagaDefinition(1, "order", "a".repeat(64), "b".repeat(64), Map.of(
                "participant", new SagaStepDefinition("participant", "participant", "http-idempotency-v1",
                        null, List.of(), true, true)));
        var envelopeIntent = alteration.equals("security") ? actual : expected;
        var step = new SagaStepSnapshot(occurrence, "participant", UUID.randomUUID(), operation,
                "compensate:" + sagaId + ":participant:" + occurrence, originalDigest,
                SagaStepStatus.OUTCOME_UNKNOWN, new SagaRecoveryEnvelope(envelopeIntent, null).encode(),
                "fixture", clock.instant());
        var snapshot = new SagaSnapshot(key, sagaId, traversal, definition, 1, SagaDisposition.UNRESOLVED,
                false, Map.of(occurrence, step), null, clock.instant(), clock.instant(), "unknown", true);
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(running.revision()))
                .writeSaga(new SagaWrite(UUID.randomUUID(), 0, snapshot)).enqueueSagaCommand(actual).build())
                .toCompletableFuture().join();

        var authorityCalls = new AtomicInteger();
        var effects = new AtomicInteger();
        SagaCommandTransport transport = new SagaCommandTransport() {
            @Override public CompletableFuture<BrokerResult> publish(SagaCommandIntent ignored) {
                effects.incrementAndGet();
                return CompletableFuture.completedFuture(new BrokerResult(true, "unexpected"));
            }
            @Override public CompletableFuture<Boolean> businessCompleted(SagaCommandIntent ignored) {
                effects.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }
        };
        var publisher = new SagaOutboxPublisher(store, transport,
                (ignoredKey, ignoredIntent) -> authorityCalls.incrementAndGet(), "recovery", 8,
                Duration.ofSeconds(5), Duration.ofMillis(10));

        if (alteration.equals("operation")) {
            org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                    () -> publisher.runOnce("tenant-a"));
        } else {
            assertEquals(SagaOutboxStatus.PENDING, publisher.runOnce("tenant-a").getFirst().status());
        }
        assertEquals(alteration.equals("operation") ? 1 : 0, authorityCalls.get(),
                "only the intact frozen recovery command may reach current authority");
        assertEquals(0, effects.get());
    }

    @Test
    void brokerAcceptanceIsDurableAndLaterSweepsPollBusinessCompletionWithoutRepublishing() throws Exception {
        MutableClock clock = new MutableClock();
        var store = new InMemoryExecutionStore(clock);
        ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversal = UUID.randomUUID(), sagaId = UUID.randomUUID(), messageId = UUID.randomUUID();
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "start",
                        TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1"))).build())
                .toCompletableFuture().join();
        var running = store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING))
                .build()).toCompletableFuture().join();
        var lease = store.claim(key, "runner", Duration.ofSeconds(30)).toCompletableFuture().join();
        OpaquePayload payload = frozenPayload(key, traversal);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.bytes()));
        var intent = new SagaCommandIntent(messageId, sagaId, "publish-order", "orders.created",
                "order.created", 1, payload, digest, null, clock.instant(), 5);
        var definition = new SagaDefinition(1, "order", "a".repeat(64), "b".repeat(64), Map.of(
                "publish", new SagaStepDefinition("publish", "publish", "amqp-inbox-v1",
                        "cancel", List.of(), false, true)));
        UUID occurrence = UUID.randomUUID();
        var step = new SagaStepSnapshot(occurrence, "publish", UUID.randomUUID(), "publish-order",
                "cancel-order", digest, SagaStepStatus.DISPATCHED,
                new SagaRecoveryEnvelope(intent, null).encode(), "intent persisted", clock.instant());
        var snapshot = new SagaSnapshot(key, sagaId, traversal, definition, 1, SagaDisposition.RUNNING,
                false, Map.of(occurrence, step), null, clock.instant(), clock.instant(), "", true);
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(running.revision()))
                .fencedBy(lease).writeSaga(new SagaWrite(UUID.randomUUID(), 0, snapshot))
                .enqueueSagaCommand(intent).build()).toCompletableFuture().join();

        AtomicInteger publishes = new AtomicInteger();
        AtomicInteger lookups = new AtomicInteger();
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean allowed = new AtomicBoolean(true);
        SagaCommandTransport transport = new SagaCommandTransport() {
            @Override public CompletableFuture<BrokerResult> publish(SagaCommandIntent ignored) {
                publishes.incrementAndGet();
                return CompletableFuture.completedFuture(new BrokerResult(true, "confirmed"));
            }
            @Override public CompletableFuture<Boolean> businessCompleted(SagaCommandIntent ignored) {
                lookups.incrementAndGet();
                return CompletableFuture.completedFuture(completed.get());
            }
        };
        var publisher = new SagaOutboxPublisher(store, transport, (ignoredKey, ignoredIntent) -> {
            if (!allowed.get()) throw new SecurityException("grant revoked");
        },
                "publisher", 8,
                Duration.ofSeconds(5), Duration.ofSeconds(2));

        assertEquals(SagaOutboxStatus.BROKER_ACCEPTED, publisher.runOnce("tenant-a").getFirst().status());
        clock.advance(Duration.ofSeconds(1));
        allowed.set(false);
        assertEquals(SagaOutboxStatus.BROKER_ACCEPTED, publisher.runOnce("tenant-a").getFirst().status());
        assertEquals(0, lookups.get(), "revoked authority prevents participant receipt lookup");
        clock.advance(Duration.ofSeconds(2));
        allowed.set(true);
        completed.set(true);
        assertEquals(SagaOutboxStatus.BUSINESS_COMPLETED, publisher.runOnce("tenant-a").getFirst().status());
        assertEquals(1, publishes.get(), "a stored broker confirmation changes later work into receipt lookup");
        assertEquals(SagaDisposition.SUCCEEDED,
                store.loadSaga(key, sagaId).toCompletableFuture().join().orElseThrow().disposition());
        assertEquals(ProcessInstanceStatus.RUNNING, store.load(key).toCompletableFuture().join().state().status(),
                "a live runner lease keeps the outbox worker from racing the terminal write");
        clock.advance(Duration.ofSeconds(31));
        publisher.runOnce("tenant-a");
        assertEquals(ProcessInstanceStatus.COMPLETED,
                store.load(key).toCompletableFuture().join().state().status(),
                "a restarted worker reconstructs graph-completion authority from persisted saga state");
    }

    @Test
    void crashedParticipantIntentPastItsDeadlineIsRecoveredThenCompensatedFromTheFrozenEnvelope() throws Exception {
        MutableClock clock = new MutableClock();
        var store = new InMemoryExecutionStore(clock);
        ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversal = UUID.randomUUID(), sagaId = UUID.randomUUID(), occurrence = UUID.randomUUID();
        var created = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "start",
                        TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1"))).build())
                .toCompletableFuture().join();
        var running = store.apply(ExecutionBatch.to(key)
                .expecting(RevisionExpectation.exactly(created.revision()))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.RUNNING))
                .build()).toCompletableFuture().join();
        OpaquePayload body = frozenPayload(key, traversal);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.bytes()));
        String forwardOperation = "forward:" + sagaId + ":rest:" + occurrence;
        String compensationOperation = "compensate:" + sagaId + ":rest:" + occurrence;
        var forward = new SagaCommandIntent(UUID.randomUUID(), sagaId, forwardOperation,
                "participant:http-idempotency-v1:http-request", "saga.forward.http-idempotency-v1.v1",
                1, body, digest, null, clock.instant(), 5);
        var compensation = new SagaCommandIntent(UUID.randomUUID(), sagaId, compensationOperation,
                "participant:http-idempotency-v1:http-request", "saga.compensation.http-idempotency-v1.v1",
                1, body, digest, forward.messageId(), clock.instant(), 5);
        var definition = new SagaDefinition(1, "order", "a".repeat(64), "b".repeat(64), Map.of(
                "rest", new SagaStepDefinition("rest", "rest", "http-idempotency-v1",
                        "undo-rest", List.of(), false, false)));
        var step = new SagaStepSnapshot(occurrence, "rest", UUID.randomUUID(), forwardOperation,
                compensationOperation, digest, SagaStepStatus.OUTCOME_UNKNOWN,
                new SagaRecoveryEnvelope(forward, compensation).encode(), "response lost", clock.instant());
        Instant createdAt = clock.instant().minusSeconds(10);
        var snapshot = new SagaSnapshot(key, sagaId, traversal, definition, 1, SagaDisposition.UNRESOLVED,
                false, Map.of(occurrence, step), clock.instant().minusSeconds(1), createdAt, clock.instant(),
                "participant outcome requires reconciliation", true);
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(running.revision()))
                .writeSaga(new SagaWrite(UUID.randomUUID(), 0, snapshot)).build()).toCompletableFuture().join();

        var published = new ArrayList<String>();
        var completed = new java.util.HashSet<String>();
        var allowed = new AtomicBoolean(false);
        SagaCommandTransport transport = new SagaCommandTransport() {
            @Override public CompletableFuture<BrokerResult> publish(SagaCommandIntent intent) {
                published.add(intent.operationId());
                completed.add(intent.operationId());
                return CompletableFuture.completedFuture(new BrokerResult(true, "participant receipt stored"));
            }
            @Override public CompletableFuture<Boolean> businessCompleted(SagaCommandIntent intent) {
                return CompletableFuture.completedFuture(completed.contains(intent.operationId()));
            }
        };
        var publisher = new SagaOutboxPublisher(store, transport, (ignoredKey, ignoredIntent) -> {
            if (!allowed.get()) throw new SecurityException("grant revoked");
        },
                "recovery", 8,
                Duration.ofSeconds(5), Duration.ofMillis(10), clock);

        org.junit.jupiter.api.Assertions.assertThrows(SecurityException.class,
                () -> publisher.runOnce("tenant-a"));
        assertEquals(List.of(), store.listSagaCommands(key).toCompletableFuture().join(),
                "revoked authority prevents recovery enqueue");
        assertEquals(List.of(), published, "revoked authority prevents participant delivery");
        allowed.set(true);
        assertEquals(List.of(), publisher.runOnce("tenant-a"), "first sweep only persists recovered intent");
        assertEquals(SagaOutboxStatus.BROKER_ACCEPTED, publisher.runOnce("tenant-a").getFirst().status());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(SagaOutboxStatus.BUSINESS_COMPLETED, publisher.runOnce("tenant-a").getFirst().status());
        assertEquals(SagaOutboxStatus.BROKER_ACCEPTED, publisher.runOnce("tenant-a").getFirst().status());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(SagaOutboxStatus.BUSINESS_COMPLETED, publisher.runOnce("tenant-a").getFirst().status());

        assertEquals(List.of(forwardOperation, compensationOperation), published);
        assertEquals(SagaDisposition.COMPENSATED,
                store.loadSaga(key, sagaId).toCompletableFuture().join().orElseThrow().disposition());
        assertEquals(true, store.loadSaga(key, sagaId).toCompletableFuture().join()
                .orElseThrow().cancellationRequested());
        assertEquals(ProcessInstanceStatus.FAILED, store.load(key).toCompletableFuture().join().state().status(),
                "a compensated business transaction remains distinguishable from normal success");
    }

    @Test
    void restartCompensatesBusinessSuccessWhoseParallelGraphNeverReachedItsBoundary() throws Exception {
        MutableClock clock = new MutableClock();
        var store = new InMemoryExecutionStore(clock);
        ExecutionKey key = new ExecutionKey("tenant-a", UUID.randomUUID());
        UUID traversal = UUID.randomUUID(), sagaId = UUID.randomUUID(), occurrence = UUID.randomUUID();
        var accepted = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(),
                        ProcessInstanceStatus.ACCEPTED, Map.of(traversal, new Traversal(traversal, "start",
                        TraversalStatus.ACCEPTED, Map.of()))), new GraphVersionPin("graph-v1"))).build())
                .toCompletableFuture().join();
        var failed = store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(accepted.revision()))
                .apply(new ExecutionTransition.TraversalTransitioned(traversal, TraversalStatus.FAILED))
                .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED))
                .build()).toCompletableFuture().join();
        OpaquePayload body = frozenPayload(key, traversal);
        String digest = sha256(body);
        String forwardOperation = "forward:" + sagaId + ":rest:" + occurrence;
        String compensationOperation = "compensate:" + sagaId + ":rest:" + occurrence;
        var forward = new SagaCommandIntent(UUID.randomUUID(), sagaId, forwardOperation,
                "participant:http-idempotency-v1:http-request", "saga.forward.http-idempotency-v1.v1",
                1, body, digest, null, clock.instant(), 5);
        var compensation = new SagaCommandIntent(UUID.randomUUID(), sagaId, compensationOperation,
                "participant:http-idempotency-v1:http-request", "saga.compensation.http-idempotency-v1.v1",
                1, body, digest, forward.messageId(), clock.instant(), 5);
        var definition = new SagaDefinition(1, "order", "a".repeat(64), "b".repeat(64), Map.of(
                "rest", new SagaStepDefinition("rest", "rest", "http-idempotency-v1",
                        "undo-rest", List.of(), false, false)));
        var step = new SagaStepSnapshot(occurrence, "rest", UUID.randomUUID(), forwardOperation,
                compensationOperation, digest, SagaStepStatus.CONFIRMED_SUCCESS,
                new SagaRecoveryEnvelope(forward, compensation).encode(), "late success", clock.instant());
        var interrupted = new SagaSnapshot(key, sagaId, traversal, definition, 1,
                SagaDisposition.SUCCEEDED, false, Map.of(occurrence, step), null,
                clock.instant(), clock.instant(), "", false);
        store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(failed.revision()))
                .writeSaga(new SagaWrite(UUID.randomUUID(), 0, interrupted)).build())
                .toCompletableFuture().join();

        var publisher = new SagaOutboxPublisher(store, new SagaCommandTransport() {
            @Override public CompletableFuture<BrokerResult> publish(SagaCommandIntent ignored) {
                return CompletableFuture.completedFuture(new BrokerResult(true, "accepted"));
            }
            @Override public CompletableFuture<Boolean> businessCompleted(SagaCommandIntent ignored) {
                return CompletableFuture.completedFuture(false);
            }
        }, (ignoredKey, ignoredIntent) -> { }, "recovery", 8,
                Duration.ofSeconds(5), Duration.ofMillis(10), clock);

        assertEquals(List.of(), publisher.runOnce("tenant-a"));
        var recovered = store.loadSaga(key, sagaId).toCompletableFuture().join().orElseThrow();
        assertEquals(true, recovered.cancellationRequested());
        assertEquals(SagaDisposition.COMPENSATION_PENDING, recovered.disposition());
        assertEquals(SagaStepStatus.COMPENSATING,
                recovered.occurrences().values().iterator().next().status());
        assertEquals(compensationOperation, store.listSagaCommands(key).toCompletableFuture().join()
                .getFirst().intent().operationId());
    }

    private static OpaquePayload frozenPayload(ExecutionKey key, UUID traversal) {
        var value = ai.ravenroot.api.payload.PayloadValue.fromJava(Map.of(
                "behavior", "fixture", "nodeId", "participant", "properties", Map.of(),
                "payload", Map.of("order", "A-1"), "attributes", Map.of(),
                "security", Map.of("tenantId", key.tenantId()),
                "processInstanceId", key.processInstanceId().toString(),
                "traversalId", traversal.toString(),
                "invocationId", UUID.randomUUID().toString(),
                "attemptId", UUID.randomUUID().toString()),
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS);
        return OpaquePayload.of(ai.ravenroot.api.payload.PayloadJson.write(value)
                .getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static String sha256(OpaquePayload payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.bytes()));
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-09-27T08:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
        void advance(Duration duration) { current = current.plus(duration); }
    }
}
