package ai.ravenroot.core.runtime;

import ai.ravenroot.api.node.service.SagaCommandTransport;
import ai.ravenroot.api.node.service.SagaCommandAuthority;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.SagaOutboxRecord;
import ai.ravenroot.api.persistence.SagaOutboxSettlement;
import ai.ravenroot.api.persistence.SagaDisposition;
import ai.ravenroot.api.persistence.SagaRecoveryEnvelope;
import ai.ravenroot.api.persistence.SagaStepSnapshot;
import ai.ravenroot.api.persistence.SagaStepStatus;
import ai.ravenroot.api.persistence.SagaWrite;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.TraversalStatus;

import java.time.Duration;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.CompletionException;

/** Bounded, leased publisher for saga application commands. */
public final class SagaOutboxPublisher {
    private final ExecutionStore store;
    private final SagaCommandTransport transport;
    private final SagaCommandAuthority authority;
    private final String workerId;
    private final int batchSize;
    private final Duration claimTtl;
    private final Duration retryDelay;
    private final Clock clock;

    /**
     * Creates a publisher with explicit queue and lease bounds.
     *
     * @param store execution store containing atomic saga intents
     * @param transport participant delivery and completion-receipt adapter
     * @param authority current worker authority; frozen command claims are never authority
     * @param workerId stable publisher worker identity
     * @param batchSize maximum commands processed by one sweep
     * @param claimTtl ownership lease for one delivery attempt
     * @param retryDelay bounded delay after transport or lookup failure
     */
    public SagaOutboxPublisher(ExecutionStore store, SagaCommandTransport transport,
                               SagaCommandAuthority authority, String workerId,
                               int batchSize, Duration claimTtl, Duration retryDelay) {
        this(store, transport, authority, workerId, batchSize, claimTtl, retryDelay, Clock.systemUTC());
    }

    SagaOutboxPublisher(ExecutionStore store, SagaCommandTransport transport,
                        SagaCommandAuthority authority, String workerId,
                        int batchSize, Duration claimTtl, Duration retryDelay, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.authority = Objects.requireNonNull(authority, "authority");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId cannot be blank");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize out of range");
        this.workerId = workerId; this.batchSize = batchSize;
        this.claimTtl = positive(claimTtl, "claimTtl"); this.retryDelay = positive(retryDelay, "retryDelay");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Compatibility constructor for embeddings compiled before worker authority was explicit.
     *
     * <p>It is intentionally fail closed: old composition has no current manifest/grant decision to
     * present, so frozen command fields cannot silently become authority.</p>
     *
     * @param store execution store containing atomic saga intents
     * @param transport participant adapter
     * @param workerId stable publisher worker identity
     * @param batchSize maximum commands processed by one sweep
     * @param claimTtl ownership lease for one delivery attempt
     * @param retryDelay bounded delay after transport or lookup failure
     * @deprecated compose an explicit {@link SagaCommandAuthority}
     */
    @Deprecated(forRemoval = false)
    public SagaOutboxPublisher(ExecutionStore store, SagaCommandTransport transport, String workerId,
                               int batchSize, Duration claimTtl, Duration retryDelay) {
        this(store, transport, (key, intent) -> {
            throw new SecurityException("saga recovery worker authority is not configured");
        }, workerId, batchSize, claimTtl, retryDelay);
    }

    /**
     * Claims and processes one bounded page for a tenant.
     *
     * @param tenantId tenant whose physical outbox is processed
     * @return durable records after this sweep's settlement
     */
    public List<SagaOutboxRecord> runOnce(String tenantId) {
        List<SagaOutboxRecord> claimed = store.claimSagaCommands(tenantId, workerId, batchSize, claimTtl)
                .toCompletableFuture().join();
        var results = new ArrayList<SagaOutboxRecord>(claimed.size());
        for (SagaOutboxRecord record : claimed) results.add(process(record));
        recoverParticipantWork(tenantId);
        recoverCompletedExecutions(tenantId);
        return List.copyOf(results);
    }

    private void recoverParticipantWork(String tenantId) {
        for (var candidate : store.listSagaRecoveryCandidates(tenantId, batchSize)
                .toCompletableFuture().join()) {
            ai.ravenroot.api.persistence.LeaseHandle lease = null;
            try {
                lease = store.claim(candidate.key(), workerId + "-saga", claimTtl).toCompletableFuture().join();
                var stored = store.load(candidate.key()).toCompletableFuture().join();
                var process = stored.state();
                var traversal = process.traversals().get(candidate.traversalId());
                var current = store.loadSaga(candidate.key(), candidate.sagaId())
                        .toCompletableFuture().join().orElse(null);
                if (current == null || current.disposition() == SagaDisposition.COMPENSATED
                        || current.disposition() == SagaDisposition.SUCCEEDED && current.graphCompleted()) continue;
                java.time.Instant now = clock.instant();
                boolean deadlineExpired = current.deadline() != null && !now.isBefore(current.deadline());
                if (traversal == null || (process.status() == ProcessInstanceStatus.WAITING
                        && !candidate.graphCompleted() && !deadlineExpired)) continue;

                boolean abortInterruptedGraph = !current.graphCompleted()
                        && process.status() != ProcessInstanceStatus.WAITING;
                var occurrences = new LinkedHashMap<>(current.occurrences());
                var commands = new ArrayList<ai.ravenroot.api.persistence.SagaCommandIntent>();
                var existing = store.listSagaCommands(candidate.key()).toCompletableFuture().join().stream()
                        .map(record -> record.intent().operationId())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                boolean changed = false;
                if (abortInterruptedGraph && !current.cancellationRequested()) changed = true;
                for (SagaStepSnapshot step : current.occurrences().values()) {
                    SagaStepSnapshot next = step;
                    if (step.status() == SagaStepStatus.DISPATCHED) {
                        next = copy(step, SagaStepStatus.OUTCOME_UNKNOWN,
                                "runner lease ended with participant outcome unknown", now);
                        changed = true;
                    }
                    if (next.status() == SagaStepStatus.OUTCOME_UNKNOWN
                            && !existing.contains(next.forwardOperationId())) {
                        SagaRecoveryEnvelope envelope = SagaRecoveryEnvelope.decode(next.receipt());
                        authority.authorize(candidate.key(), envelope.forward());
                        commands.add(envelope.forward());
                        existing.add(next.forwardOperationId());
                    }
                    if (next.status() == SagaStepStatus.COMPENSATION_UNKNOWN
                            && !existing.contains(next.compensationOperationId())) {
                        SagaRecoveryEnvelope envelope = SagaRecoveryEnvelope.decode(next.receipt());
                        if (envelope.compensation() == null) continue;
                        authority.authorize(candidate.key(), envelope.compensation());
                        commands.add(envelope.compensation());
                        existing.add(next.compensationOperationId());
                    }
                    occurrences.put(next.occurrenceId(), next);
                }

                boolean cancellation = current.cancellationRequested() || abortInterruptedGraph || deadlineExpired;
                if (deadlineExpired && !current.cancellationRequested()) changed = true;
                if (cancellation) {
                    for (SagaStepSnapshot step : List.copyOf(occurrences.values())) {
                        if ((step.status() != SagaStepStatus.CONFIRMED_SUCCESS
                                && step.status() != SagaStepStatus.COMPENSATION_PENDING)
                                || existing.contains(step.compensationOperationId())) continue;
                        boolean dependentOutstanding = current.definition().steps().values().stream()
                                .filter(definition -> definition.dependencies().contains(step.stepId()))
                                .anyMatch(definition -> occurrences.values().stream()
                                        .filter(value -> value.stepId().equals(definition.stepId()))
                                        .anyMatch(value -> value.status() != SagaStepStatus.COMPENSATED
                                                && value.status() != SagaStepStatus.CONFIRMED_NO_EFFECT));
                        if (dependentOutstanding) continue;
                        SagaRecoveryEnvelope envelope = SagaRecoveryEnvelope.decode(step.receipt());
                        if (envelope.compensation() == null) continue;
                        authority.authorize(candidate.key(), envelope.compensation());
                        commands.add(envelope.compensation());
                        existing.add(step.compensationOperationId());
                        occurrences.put(step.occurrenceId(), copy(step, SagaStepStatus.COMPENSATING,
                                "durable recovery scheduled compensation", now));
                        changed = true;
                    }
                }

                SagaDisposition disposition = recoveryDisposition(current, occurrences, cancellation);
                String reason = disposition == SagaDisposition.UNRESOLVED
                        ? deadlineExpired ? "saga deadline elapsed; participant outcome requires durable recovery"
                        : "participant outcome requires durable recovery"
                        : disposition == SagaDisposition.COMPENSATION_PENDING
                        ? deadlineExpired ? "saga deadline elapsed; durable compensations are pending"
                        : "durable compensations are pending" : "";
                if (disposition != current.disposition() || !reason.equals(current.actionableReason())) {
                    changed = true;
                }
                if (!changed && commands.isEmpty()) continue;
                var next = new ai.ravenroot.api.persistence.SagaSnapshot(current.key(), current.sagaId(),
                        current.traversalId(), current.definition(), current.revision() + 1, disposition,
                        cancellation, occurrences, current.deadline(), current.createdAt(), now, reason,
                        current.graphCompleted());
                var batch = ExecutionBatch.to(candidate.key())
                        .expecting(RevisionExpectation.exactly(stored.revision())).fencedBy(lease)
                        .writeSaga(new SagaWrite(java.util.UUID.randomUUID(), current.revision(), next));
                commands.forEach(batch::enqueueSagaCommand);
                store.apply(batch.build()).toCompletableFuture().join();
            } catch (RuntimeException unavailable) {
                Throwable cause = unavailable instanceof CompletionException && unavailable.getCause() != null
                        ? unavailable.getCause() : unavailable;
                if (!(cause instanceof ExecutionStoreException refused
                        && (refused.failure() instanceof ExecutionStoreFailure.LeaseHeldByAnother
                        || refused.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        || refused.failure() instanceof ExecutionStoreFailure.LeaseLost
                        || refused.failure() instanceof ExecutionStoreFailure.FencedOut))) {
                    throw unavailable;
                }
            } finally {
                releaseBestEffort(lease);
            }
        }
    }

    private static SagaDisposition recoveryDisposition(
            ai.ravenroot.api.persistence.SagaSnapshot current,
            java.util.Map<java.util.UUID, SagaStepSnapshot> occurrences, boolean cancellation) {
        boolean unknown = occurrences.values().stream().anyMatch(step -> step.status() == SagaStepStatus.DISPATCHED
                || step.status() == SagaStepStatus.OUTCOME_UNKNOWN
                || step.status() == SagaStepStatus.COMPENSATION_UNKNOWN);
        if (unknown) return SagaDisposition.UNRESOLVED;
        if (cancellation) {
            boolean outstanding = occurrences.values().stream().anyMatch(step ->
                    step.status() != SagaStepStatus.COMPENSATED
                            && step.status() != SagaStepStatus.CONFIRMED_NO_EFFECT);
            return outstanding ? SagaDisposition.COMPENSATION_PENDING : SagaDisposition.COMPENSATED;
        }
        return current.disposition();
    }

    private static SagaStepSnapshot copy(SagaStepSnapshot old, SagaStepStatus status,
                                         String detail, java.time.Instant now) {
        return new SagaStepSnapshot(old.occurrenceId(), old.stepId(), old.invocationId(),
                old.forwardOperationId(), old.compensationOperationId(), old.payloadFingerprint(), status,
                old.receipt(), detail, now);
    }

    private void recoverCompletedExecutions(String tenantId) {
        for (var candidate : store.listSagaCompletionCandidates(tenantId, batchSize)
                .toCompletableFuture().join()) {
            var key = candidate.key();
            var sagas = store.listSagas(key).toCompletableFuture().join();
            boolean ready = sagas.stream()
                    .filter(saga -> saga.traversalId().equals(candidate.traversalId()))
                    .allMatch(saga -> saga.graphCompleted()
                            && (saga.disposition() == SagaDisposition.SUCCEEDED
                            || saga.disposition() == SagaDisposition.COMPENSATED));
            if (!ready) continue;
            ai.ravenroot.api.persistence.LeaseHandle lease = null;
            try {
                lease = store.claim(key, workerId + "-completion", claimTtl).toCompletableFuture().join();
                var stored = store.load(key).toCompletableFuture().join();
                var process = stored.state();
                if (process.status().terminal()) continue;
                var traversal = process.traversals().get(candidate.traversalId());
                if (traversal == null || traversal.status().terminal()) continue;
                var batch = ExecutionBatch.to(key)
                        .expecting(RevisionExpectation.exactly(stored.revision())).fencedBy(lease);
                if (traversal.status() == TraversalStatus.WAITING) {
                    batch.apply(new ExecutionTransition.TraversalTransitioned(
                            candidate.traversalId(), TraversalStatus.RUNNING));
                }
                boolean compensated = sagas.stream()
                        .filter(saga -> saga.traversalId().equals(candidate.traversalId()))
                        .anyMatch(saga -> saga.disposition() == SagaDisposition.COMPENSATED);
                batch.apply(new ExecutionTransition.TraversalTransitioned(candidate.traversalId(),
                        compensated ? TraversalStatus.FAILED : TraversalStatus.COMPLETED));
                boolean otherOpen = process.traversals().entrySet().stream()
                        .anyMatch(entry -> !entry.getKey().equals(candidate.traversalId())
                                && !entry.getValue().status().terminal());
                if (!otherOpen) {
                    if (process.status() == ProcessInstanceStatus.WAITING) {
                        batch.apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING));
                    }
                    batch.apply(new ExecutionTransition.ProcessTransitioned(compensated
                            ? ProcessInstanceStatus.FAILED : ProcessInstanceStatus.COMPLETED));
                }
                store.apply(batch.build()).toCompletableFuture().join();
            } catch (RuntimeException unavailable) {
                Throwable cause = unavailable instanceof CompletionException && unavailable.getCause() != null
                        ? unavailable.getCause() : unavailable;
                if (!(cause instanceof ExecutionStoreException refused
                        && (refused.failure() instanceof ExecutionStoreFailure.LeaseHeldByAnother
                        || refused.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        || refused.failure() instanceof ExecutionStoreFailure.LeaseLost
                        || refused.failure() instanceof ExecutionStoreFailure.FencedOut))) {
                    throw unavailable;
                }
            } finally {
                releaseBestEffort(lease);
            }
        }
    }

    private void releaseBestEffort(ai.ravenroot.api.persistence.LeaseHandle lease) {
        if (lease == null) return;
        try {
            store.release(lease).toCompletableFuture().join();
        } catch (RuntimeException ignored) {
            // Release is best effort by contract; expiry preserves the same fence rule.
        }
    }

    private SagaOutboxRecord process(SagaOutboxRecord record) {
        try {
            requireFrozenBinding(record);
            authority.authorize(record.key(), record.intent());
            if (record.brokerAcceptedAt() != null) {
                boolean complete = transport.businessCompleted(record.intent()).toCompletableFuture().join();
                return settle(record, complete ? new SagaOutboxSettlement.BusinessCompleted()
                        : new SagaOutboxSettlement.Retry(retryDelay, "business completion pending"));
            }
            SagaCommandTransport.BrokerResult result = transport.publish(record.intent()).toCompletableFuture().join();
            return settle(record, result.accepted() ? new SagaOutboxSettlement.BrokerAccepted()
                    : new SagaOutboxSettlement.Retry(retryDelay,
                    result.safeDetail().isBlank() ? "broker did not accept command" : result.safeDetail()));
        } catch (RuntimeException transportFailure) {
            Throwable cause = transportFailure instanceof CompletionException && transportFailure.getCause() != null
                    ? transportFailure.getCause() : transportFailure;
            return settle(record, new SagaOutboxSettlement.Retry(retryDelay,
                    "transport unavailable: " + cause.getClass().getSimpleName()));
        }
    }

    private void requireFrozenBinding(SagaOutboxRecord record) {
        var saga = store.loadSaga(record.key(), record.intent().sagaId()).toCompletableFuture().join()
                .orElseThrow(() -> new SecurityException("saga command has no owning aggregate"));
        if (!saga.key().equals(record.key())) {
            throw new SecurityException("saga command crosses its owning execution boundary");
        }
        SagaStepSnapshot step = saga.occurrences().values().stream()
                .filter(value -> value.forwardOperationId().equals(record.intent().operationId())
                        || value.compensationOperationId().equals(record.intent().operationId()))
                .findFirst().orElseThrow(() -> new SecurityException(
                        "saga command has no owning step occurrence"));
        SagaRecoveryEnvelope envelope = SagaRecoveryEnvelope.decode(step.receipt());
        boolean compensation = step.compensationOperationId().equals(record.intent().operationId());
        var expected = compensation ? envelope.compensation() : envelope.forward();
        if (expected == null || !expected.equals(record.intent())) {
            throw new SecurityException("saga command differs from its frozen participant envelope");
        }
        Object decoded = ai.ravenroot.api.payload.PayloadJson.read(record.intent().payload().bytes(),
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS).toJava();
        if (!(decoded instanceof java.util.Map<?, ?> frozen)
                || !(frozen.get("security") instanceof java.util.Map<?, ?> security)
                || !record.key().tenantId().equals(security.get("tenantId"))
                || !record.key().processInstanceId().toString().equals(frozen.get("processInstanceId"))
                || !saga.traversalId().toString().equals(frozen.get("traversalId"))) {
            throw new SecurityException("frozen participant evidence does not match persisted execution identity");
        }
    }

    private SagaOutboxRecord settle(SagaOutboxRecord record, SagaOutboxSettlement settlement) {
        return store.settleSagaCommand(record.key().tenantId(), record.intent().messageId(), workerId,
                record.fencingToken(), settlement).toCompletableFuture().join();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(name + " must be in (0, 1 day]");
        }
        return value;
    }
}
