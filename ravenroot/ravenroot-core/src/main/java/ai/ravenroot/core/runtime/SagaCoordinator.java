package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RetryClassified;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.SagaDefinition;
import ai.ravenroot.api.persistence.SagaCommandIntent;
import ai.ravenroot.api.persistence.SagaDisposition;
import ai.ravenroot.api.persistence.SagaRecoveryEnvelope;
import ai.ravenroot.api.persistence.SagaSnapshot;
import ai.ravenroot.api.persistence.SagaStepDefinition;
import ai.ravenroot.api.persistence.SagaStepSnapshot;
import ai.ravenroot.api.persistence.SagaStepStatus;
import ai.ravenroot.api.persistence.SagaWrite;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphNode;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/** Runtime-owned saga intent/outcome state around real graph node dispatch. */
final class SagaCoordinator {
    private static final UUID ID_NAMESPACE = UUID.fromString("8138568e-575f-50fd-8e24-91ea232cd7af");

    private final GraphDefinition graph;
    private final Map<String, SagaDefinition> definitions;
    private final Map<String, Duration> deadlines;
    private final Clock clock;
    private final Map<UUID, Set<UUID>> traversalSagas = new ConcurrentHashMap<>();
    private final Map<UUID, Object> sagaLocks = new ConcurrentHashMap<>();

    SagaCoordinator(GraphDefinition graph, BehaviorRegistry behaviors, Clock clock) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.definitions = SagaGraphContract.validate(graph, Objects.requireNonNull(behaviors, "behaviors"));
        var parsedDeadlines = new LinkedHashMap<String, Duration>();
        definitions.keySet().forEach(scope -> {
            Duration deadline = SagaGraphContract.deadline(graph, scope);
            if (deadline != null) parsedDeadlines.put(scope, deadline);
        });
        this.deadlines = Map.copyOf(parsedDeadlines);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Before before(GraphNode node, NodeMessage original, ExecutionRecorder recorder) {
        if (!SagaGraphContract.forward(node) && !SagaGraphContract.compensation(node)) {
            return new Before(original, null, null);
        }
        if (recorder == null) throw new IllegalStateException("a saga graph requires a durable execution store");
        if (!recorder.supportsDurableSagas()) {
            throw new IllegalStateException("the execution store does not support durable sagas");
        }
        String scope = SagaGraphContract.scope(node);
        SagaDefinition definition = definitions.get(scope);
        UUID sagaId = id("saga", original.security().tenantId(), original.processInstanceId().toString(),
                original.traversalId().toString(), scope);
        traversalSagas.computeIfAbsent(original.traversalId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(sagaId);
        synchronized (sagaLocks.computeIfAbsent(sagaId, ignored -> new Object())) {
        SagaSnapshot current = recorder.saga(sagaId).orElse(null);
        Instant now = clock.instant();
        if (current == null) current = new SagaSnapshot(
                new ExecutionKey(original.security().tenantId(), original.processInstanceId()), sagaId,
                original.traversalId(), definition, 1, SagaDisposition.RUNNING, false, Map.of(),
                deadline(now, deadlines.get(scope)),
                now, now, "", false);
        if (!current.traversalId().equals(original.traversalId())) {
            throw new IllegalStateException("saga recovery refused for a different traversal");
        }
        if (!current.definition().equals(definition)) {
            throw new IllegalStateException("saga recovery refused because its frozen graph or participant contract changed");
        }
        if (SagaGraphContract.compensation(node)) return beforeCompensation(node, original, recorder, current);
        if (current.deadline() != null && !now.isBefore(current.deadline())
                && !current.cancellationRequested()) {
            SagaDisposition disposition = expiredDisposition(current);
            var expired = new SagaSnapshot(current.key(), current.sagaId(), current.traversalId(),
                    current.definition(), current.revision() + 1, disposition, true,
                    current.occurrences(), current.deadline(), current.createdAt(), now,
                    disposition == SagaDisposition.COMPENSATED ? ""
                            : "saga deadline elapsed before participant dispatch", current.graphCompleted());
            persist(recorder, current, expired);
            throw new IllegalStateException("saga deadline elapsed before participant dispatch");
        }

        String stepId = SagaGraphContract.step(node);
        SagaStepDefinition stepDefinition = definition.steps().get(stepId);
        // Repeated saga visits are rejected at admission, so the scope+step pair is the logical
        // occurrence. Invocation and attempt identifiers remain evidence about transport execution;
        // they must not create a new business identity after restart.
        UUID occurrenceId = id("occurrence", sagaId.toString(), stepId);
        String fingerprint = fingerprint(original.payload());
        SagaStepSnapshot previous = current.occurrences().get(occurrenceId);
        if (previous != null && !previous.payloadFingerprint().equals(fingerprint)) {
            throw new IllegalStateException("saga operation identity was reused with a different payload");
        }
        String forwardOperation = operation("forward", sagaId, stepId, occurrenceId);
        String compensationOperation = operation("compensate", sagaId, stepId, occurrenceId);
        NodeMessage enriched = enrich(node, original, sagaId, occurrenceId, forwardOperation,
                forwardOperation,
                compensationOperation, fingerprint);
        if (previous != null && previous.status() == SagaStepStatus.CONFIRMED_SUCCESS) {
            return new Before(enriched, new NodeResult("continue", enriched.payload(), enriched.attributes()),
                    new Invocation(sagaId, occurrenceId, stepId, false,
                            stepDefinition.participantContract(), stepDefinition.businessCompletionRequired()));
        }
        if (previous != null && (previous.status() == SagaStepStatus.COMPENSATION_UNKNOWN
                || previous.status() == SagaStepStatus.COMPENSATING
                || previous.status() == SagaStepStatus.COMPENSATED)) {
            throw new IllegalStateException("saga operation cannot be dispatched while compensation is active");
        }
        SagaCommandIntent forwardIntent = participantIntent(node, enriched, sagaId, forwardOperation,
                null, current.createdAt(), stepDefinition.participantContract(), "forward");
        SagaCommandIntent compensationIntent = null;
        if (stepDefinition.compensationNodeId() != null && forwardIntent != null) {
            GraphNode compensationNode = graph.node(stepDefinition.compensationNodeId());
            NodeMessage compensationMessage = new NodeMessage(original.security(), original.processInstanceId(),
                    original.traversalId(), original.invocationId(), original.attemptId(),
                    original.parentInvocationIds(), compensationNode.id(), original.payload(), original.attributes(),
                    original.command());
            compensationMessage = enrich(compensationNode, compensationMessage, sagaId, occurrenceId,
                    compensationOperation, forwardOperation, compensationOperation, fingerprint);
            compensationIntent = participantIntent(compensationNode, compensationMessage, sagaId,
                    compensationOperation, id("message", forwardOperation), current.createdAt(),
                    stepDefinition.participantContract(), "compensation");
        }
        OpaquePayload recovery = forwardIntent == null
                ? OpaquePayload.empty("application/vnd.ravenroot.saga-receipt")
                : new SagaRecoveryEnvelope(forwardIntent, compensationIntent).encode();
        SagaStepSnapshot dispatched = new SagaStepSnapshot(occurrenceId, stepId, original.invocationId(),
                operation("forward", sagaId, stepId, occurrenceId),
                operation("compensate", sagaId, stepId, occurrenceId), fingerprint,
                SagaStepStatus.DISPATCHED, recovery,
                previous == null ? "intent persisted before dispatch" : "stable operation redelivery",
                now);
        persist(recorder, current, replace(current, dispatched, current.disposition(), "", now),
                "amqp-inbox-v1".equals(stepDefinition.participantContract()) ? forwardIntent : null);
        return new Before(enriched, null, new Invocation(sagaId, occurrenceId, stepId, false,
                stepDefinition.participantContract(), stepDefinition.businessCompletionRequired()));
        }
    }

    void succeeded(Before before, NodeResult result, ExecutionRecorder recorder) {
        if (before.invocation() == null || before.replay() != null) return;
        synchronized (sagaLocks.computeIfAbsent(before.invocation().sagaId(), ignored -> new Object())) {
        SagaSnapshot current = require(recorder, before.invocation().sagaId());
        SagaStepSnapshot old = requireOccurrence(current, before.invocation().occurrenceId());
        SagaStepStatus status = observedSuccess(before.invocation(), result);
        String detail = bounded("outcome=" + result.outcome());
        SagaStepSnapshot updated = copy(old, status, detail, clock.instant());
        if (status == SagaStepStatus.COMPENSATION_UNKNOWN) {
            persist(recorder, current, replace(current, updated, SagaDisposition.UNRESOLVED,
                    "compensation outcome requires reconciliation", clock.instant()));
            return;
        }
        if (current.cancellationRequested() && !before.invocation().compensation()) {
            SagaDisposition disposition = status == SagaStepStatus.CONFIRMED_NO_EFFECT
                    ? dispositionAfter(current, updated) : status == SagaStepStatus.CONFIRMED_SUCCESS
                    ? SagaDisposition.COMPENSATION_PENDING : SagaDisposition.UNRESOLVED;
            String reason = disposition == SagaDisposition.COMPENSATION_PENDING
                    ? "participant completed after cancellation; durable compensation required"
                    : disposition == SagaDisposition.UNRESOLVED
                    ? "late participant outcome requires reconciliation" : "";
            persist(recorder, current, replace(current, updated, disposition, reason, clock.instant()));
            return;
        }
        SagaDisposition disposition = dispositionAfter(current, updated);
        persist(recorder, current, replace(current, updated, disposition, "", clock.instant()));
        }
    }

    void failed(Before before, Throwable failure, ExecutionRecorder recorder) {
        if (before.invocation() == null || before.replay() != null) return;
        synchronized (sagaLocks.computeIfAbsent(before.invocation().sagaId(), ignored -> new Object())) {
        SagaSnapshot current = require(recorder, before.invocation().sagaId());
        SagaStepSnapshot old = requireOccurrence(current, before.invocation().occurrenceId());
        boolean noEffect = confirmedNoEffect(before.invocation(), failure);
        SagaStepStatus status = before.invocation().compensation()
                ? noEffect ? SagaStepStatus.COMPENSATION_PENDING : SagaStepStatus.COMPENSATION_UNKNOWN
                : noEffect ? SagaStepStatus.CONFIRMED_NO_EFFECT : SagaStepStatus.OUTCOME_UNKNOWN;
        String detail = bounded(failure == null ? "participant outcome unknown" : failure.getClass().getSimpleName());
        SagaStepSnapshot updated = copy(old, status, detail, clock.instant());
        SagaDisposition disposition = status == SagaStepStatus.CONFIRMED_NO_EFFECT
                ? dispositionAfter(current, updated)
                : status == SagaStepStatus.COMPENSATION_PENDING
                ? SagaDisposition.COMPENSATION_PENDING : SagaDisposition.UNRESOLVED;
        String reason = status == SagaStepStatus.CONFIRMED_NO_EFFECT ? ""
                : status == SagaStepStatus.COMPENSATION_PENDING
                ? "compensation confirmed no effect and remains retryable"
                : before.invocation().compensation() ? "compensation outcome requires reconciliation"
                : "participant outcome requires reconciliation";
        persist(recorder, current, replace(current, updated, disposition, reason, clock.instant()));
        }
    }

    void prepareCompletion(UUID traversalId, ExecutionRecorder recorder) {
        if (recorder == null || definitions.isEmpty()) return;
        Set<UUID> sagaIds = traversalSagas.getOrDefault(traversalId, Set.of());
        if (sagaIds.size() != definitions.size()) {
            throw new IllegalStateException("saga traversal ended before every declared scope was entered");
        }
        for (UUID sagaId : sagaIds) {
            SagaSnapshot snapshot = require(recorder, sagaId);
            if (!snapshot.graphCompleted()) {
                Instant now = clock.instant();
                SagaDisposition disposition = allStepsSucceeded(snapshot)
                        ? SagaDisposition.SUCCEEDED : snapshot.disposition();
                var completed = new SagaSnapshot(snapshot.key(), snapshot.sagaId(), snapshot.traversalId(),
                        snapshot.definition(), snapshot.revision() + 1, disposition,
                        snapshot.cancellationRequested(), snapshot.occurrences(), snapshot.deadline(),
                        snapshot.createdAt(), now, snapshot.actionableReason(), true);
                persist(recorder, snapshot, completed);
            }
        }
    }

    void requireTerminal(UUID traversalId, ExecutionRecorder recorder) {
        if (recorder == null || definitions.isEmpty()) return;
        Set<UUID> sagaIds = traversalSagas.getOrDefault(traversalId, Set.of());
        for (UUID sagaId : sagaIds) {
            SagaSnapshot snapshot = require(recorder, sagaId);
            if (!snapshot.graphCompleted()) {
                throw new IllegalStateException("saga traversal cannot complete before its graph boundary: "
                        + snapshot.sagaId());
            }
            if (snapshot.disposition() == SagaDisposition.COMPENSATED) {
                throw new IllegalStateException("saga business transaction was compensated: "
                        + snapshot.sagaId());
            }
            if (snapshot.disposition() != SagaDisposition.SUCCEEDED) {
                throw new IllegalStateException("saga traversal cannot complete while "
                        + snapshot.disposition() + ": " + snapshot.actionableReason());
            }
        }
        traversalSagas.remove(traversalId);
    }

    boolean awaitTerminal(UUID traversalId, ExecutionRecorder recorder, Duration bound) {
        if (recorder == null || definitions.isEmpty()) return true;
        if (bound == null || bound.isNegative() || bound.isZero() || bound.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("saga terminal wait must be in (0, 10 minutes]");
        }
        long deadline = System.nanoTime() + bound.toNanos();
        while (true) {
            boolean pending = false;
            for (UUID sagaId : traversalSagas.getOrDefault(traversalId, Set.of())) {
                SagaSnapshot snapshot = require(recorder, sagaId);
                boolean allStepsEntered = snapshot.definition().steps().keySet().stream().allMatch(stepId ->
                        snapshot.occurrences().values().stream().anyMatch(step -> step.stepId().equals(stepId)));
                if (!allStepsEntered) {
                    throw new IllegalStateException("saga traversal ended before every declared step was entered");
                }
                if (snapshot.disposition() == SagaDisposition.UNRESOLVED) {
                    throw new IllegalStateException("saga traversal cannot complete while UNRESOLVED: "
                            + snapshot.actionableReason());
                }
                pending |= !snapshot.graphCompleted()
                        || snapshot.disposition() != SagaDisposition.SUCCEEDED
                        && snapshot.disposition() != SagaDisposition.COMPENSATED;
            }
            if (!pending) {
                requireTerminal(traversalId, recorder);
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("saga terminal wait was interrupted");
            }
        }
    }

    void cancellationRequested(UUID traversalId, ExecutionRecorder recorder) {
        if (recorder == null) return;
        for (UUID sagaId : traversalSagas.getOrDefault(traversalId, Set.of())) {
            synchronized (sagaLocks.computeIfAbsent(sagaId, ignored -> new Object())) {
                SagaSnapshot current = require(recorder, sagaId);
                if (current.cancellationRequested()
                        || current.disposition() == SagaDisposition.SUCCEEDED && current.graphCompleted()) continue;
                var occurrences = new LinkedHashMap<UUID, SagaStepSnapshot>();
                boolean unknown = false;
                boolean confirmedEffect = false;
                Instant now = clock.instant();
                for (SagaStepSnapshot step : current.occurrences().values()) {
                    SagaStepSnapshot next = step;
                    SagaStepDefinition stepDefinition = current.definition().steps().get(step.stepId());
                    if (stepDefinition != null && "pure".equals(stepDefinition.participantContract())
                            && step.status() == SagaStepStatus.CONFIRMED_SUCCESS) {
                        next = copy(step, SagaStepStatus.CONFIRMED_NO_EFFECT,
                                "pure step needs no compensation", now);
                    } else if (step.status() == SagaStepStatus.DISPATCHED) {
                        next = copy(step, SagaStepStatus.OUTCOME_UNKNOWN,
                                "cancellation raced participant dispatch; reconcile outcome", now);
                        unknown = true;
                    } else if (step.status() == SagaStepStatus.CONFIRMED_SUCCESS
                            || step.status() == SagaStepStatus.COMPENSATING
                            || step.status() == SagaStepStatus.COMPENSATION_UNKNOWN) {
                        confirmedEffect = true;
                        unknown |= step.status() == SagaStepStatus.COMPENSATION_UNKNOWN;
                    }
                    occurrences.put(next.occurrenceId(), next);
                }
                SagaDisposition disposition = unknown ? SagaDisposition.UNRESOLVED
                        : confirmedEffect ? SagaDisposition.COMPENSATION_PENDING : SagaDisposition.COMPENSATED;
                String reason = unknown ? "cancelled with participant outcome requiring reconciliation"
                        : confirmedEffect ? "cancelled; durable compensation required" : "";
                var next = new SagaSnapshot(current.key(), current.sagaId(), current.traversalId(), current.definition(),
                        current.revision() + 1, disposition, true, occurrences, current.deadline(),
                        current.createdAt(), now, reason, current.graphCompleted());
                persist(recorder, current, next);
            }
        }
    }

    void prepareFailure(UUID traversalId, ExecutionRecorder recorder) {
        if (recorder == null || definitions.isEmpty()) return;
        cancellationRequested(traversalId, recorder);
        for (UUID sagaId : traversalSagas.getOrDefault(traversalId, Set.of())) {
            SagaSnapshot current = require(recorder, sagaId);
            if (current.graphCompleted()) continue;
            Instant now = clock.instant();
            var failedBoundary = new SagaSnapshot(current.key(), current.sagaId(), current.traversalId(),
                    current.definition(), current.revision() + 1, current.disposition(),
                    current.cancellationRequested(), current.occurrences(), current.deadline(),
                    current.createdAt(), now, current.actionableReason(), true);
            persist(recorder, current, failedBoundary);
        }
    }

    private Before beforeCompensation(GraphNode node, NodeMessage original, ExecutionRecorder recorder,
                                      SagaSnapshot current) {
        SagaStepDefinition definition = current.definition().steps().values().stream()
                .filter(step -> node.id().equals(step.compensationNodeId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("compensation node is not bound to a forward step"));
        SagaStepSnapshot forward = current.occurrences().values().stream()
                .filter(value -> value.stepId().equals(definition.stepId()))
                .filter(value -> value.status() == SagaStepStatus.CONFIRMED_SUCCESS
                        || value.status() == SagaStepStatus.COMPENSATION_PENDING
                        || value.status() == SagaStepStatus.COMPENSATION_UNKNOWN)
                .max(Comparator.comparing(SagaStepSnapshot::updatedAt))
                .orElseThrow(() -> new IllegalStateException("compensation refused without a confirmed effect"));
        for (SagaStepDefinition dependent : current.definition().steps().values()) {
            if (!dependent.dependencies().contains(definition.stepId())) continue;
            boolean outstanding = current.occurrences().values().stream()
                    .anyMatch(value -> value.stepId().equals(dependent.stepId())
                            && value.status() != SagaStepStatus.COMPENSATED
                            && value.status() != SagaStepStatus.CONFIRMED_NO_EFFECT);
            if (outstanding) throw new IllegalStateException("compensation order violates saga dependencies");
        }
        SagaRecoveryEnvelope recovery = SagaRecoveryEnvelope.decode(forward.receipt());
        SagaCommandIntent compensationIntent = recovery.compensation();
        if (compensationIntent == null) {
            throw new IllegalStateException("frozen compensation intent is absent");
        }
        // The graph may have transformed its payload after the forward effect.  The live path must
        // execute the exact request that durable recovery would replay, otherwise a compensation can
        // keep operation A's stable id while acting on graph output for B.  Current request security
        // remains the authority; serialized security fields are evidence and are never reinstated.
        NodeMessage frozen = frozenParticipantMessage(node, compensationIntent, original);
        if (forward.status() == SagaStepStatus.COMPENSATED) {
            return new Before(frozen, new NodeResult("continue", frozen.payload(), frozen.attributes()),
                    new Invocation(current.sagaId(), forward.occurrenceId(), forward.stepId(), true,
                            definition.participantContract(), definition.businessCompletionRequired()));
        }
        SagaStepSnapshot compensating = copy(forward, SagaStepStatus.COMPENSATING,
                "compensation intent persisted before dispatch", clock.instant());
        persist(recorder, current, replace(current, compensating, SagaDisposition.COMPENSATION_PENDING,
                "", clock.instant()), compensationIntent);
        return new Before(frozen, null,
                new Invocation(current.sagaId(), forward.occurrenceId(), forward.stepId(), true,
                        definition.participantContract(), definition.businessCompletionRequired()));
    }

    private static NodeMessage frozenParticipantMessage(GraphNode node, SagaCommandIntent intent,
                                                        NodeMessage current) {
        Object decoded = PayloadJson.read(intent.payload().bytes(), PayloadLimits.DEFAULTS).toJava();
        Map<String, Object> envelope = stringMap(decoded, "frozen participant envelope");
        if (!node.behavior().equals(envelope.get("behavior")) || !node.id().equals(envelope.get("nodeId"))
                || !fingerprint(node.properties()).equals(fingerprint(envelope.get("properties")))) {
            throw new IllegalStateException("frozen compensation adapter binding changed");
        }
        Map<String, Object> evidence = stringMap(envelope.get("security"), "frozen security evidence");
        if (!current.security().tenantId().equals(evidence.get("tenantId"))) {
            throw new IllegalStateException("frozen compensation tenant changed");
        }
        UUID process = uuid(envelope.get("processInstanceId"), "processInstanceId");
        UUID traversal = uuid(envelope.get("traversalId"), "traversalId");
        if (!current.processInstanceId().equals(process) || !current.traversalId().equals(traversal)) {
            throw new IllegalStateException("frozen compensation execution identity changed");
        }
        Map<String, Object> attributes = stringMap(envelope.get("attributes"), "frozen attributes");
        if (!intent.operationId().equals(attributes.get("sagaOperationId"))) {
            throw new IllegalStateException("frozen compensation operation identity changed");
        }
        return new NodeMessage(current.security(), process, traversal,
                uuid(envelope.get("invocationId"), "invocationId"),
                uuid(envelope.get("attemptId"), "attemptId"), current.parentInvocationIds(), node.id(),
                envelope.get("payload"), attributes, current.command());
    }

    private static Map<String, Object> stringMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalStateException(name + " is malformed");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Map.copyOf(result);
    }

    private static UUID uuid(Object value, String name) {
        try { return UUID.fromString(String.valueOf(value)); }
        catch (RuntimeException malformed) { throw new IllegalStateException("frozen " + name + " is malformed"); }
    }

    private static SagaStepStatus observedSuccess(Invocation invocation, NodeResult result) {
        if ("http-idempotency-v1".equals(invocation.participantContract())) {
            Object status = result.attributes().get("http.status");
            if (status instanceof Number number && number.intValue() >= 200 && number.intValue() < 300) {
                return invocation.compensation() ? SagaStepStatus.COMPENSATED
                        : SagaStepStatus.CONFIRMED_SUCCESS;
            }
            return invocation.compensation() ? SagaStepStatus.COMPENSATION_UNKNOWN
                    : SagaStepStatus.CONFIRMED_NO_EFFECT;
        }
        if ("amqp-inbox-v1".equals(invocation.participantContract())) {
            Object wireStatus = result.payload() instanceof Map<?, ?> payload ? payload.get("status") : null;
            if (!"CONFIRMED".equals(wireStatus)) {
                return invocation.compensation() ? SagaStepStatus.COMPENSATION_UNKNOWN
                        : SagaStepStatus.CONFIRMED_NO_EFFECT;
            }
            if (invocation.compensation()) {
                return invocation.businessCompletionRequired()
                        ? SagaStepStatus.COMPENSATION_UNKNOWN : SagaStepStatus.COMPENSATED;
            }
            return invocation.businessCompletionRequired()
                    ? SagaStepStatus.DISPATCHED : SagaStepStatus.CONFIRMED_SUCCESS;
        }
        return invocation.compensation() ? SagaStepStatus.COMPENSATED : SagaStepStatus.CONFIRMED_SUCCESS;
    }

    private static SagaDisposition dispositionAfter(SagaSnapshot current, SagaStepSnapshot changed) {
        var occurrences = new ArrayList<>(current.occurrences().values());
        occurrences.removeIf(value -> value.occurrenceId().equals(changed.occurrenceId()));
        occurrences.add(changed);
        if (changed.status() == SagaStepStatus.COMPENSATED
                && occurrences.stream().filter(value -> value.status() != SagaStepStatus.CONFIRMED_NO_EFFECT)
                .allMatch(value -> value.status() == SagaStepStatus.COMPENSATED)) return SagaDisposition.COMPENSATED;
        if (current.disposition() == SagaDisposition.COMPENSATION_PENDING
                && changed.status() == SagaStepStatus.CONFIRMED_SUCCESS) return SagaDisposition.COMPENSATION_PENDING;
        if (changed.status() == SagaStepStatus.CONFIRMED_NO_EFFECT) {
            boolean anyEffect = occurrences.stream().anyMatch(value -> value.status() == SagaStepStatus.CONFIRMED_SUCCESS
                    || value.status() == SagaStepStatus.COMPENSATION_PENDING
                    || value.status() == SagaStepStatus.COMPENSATING
                    || value.status() == SagaStepStatus.COMPENSATION_UNKNOWN);
            return anyEffect ? SagaDisposition.COMPENSATION_PENDING : SagaDisposition.COMPENSATED;
        }
        boolean eachStepSucceeded = current.definition().steps().keySet().stream().allMatch(step -> occurrences.stream()
                .anyMatch(value -> value.stepId().equals(step) && value.status() == SagaStepStatus.CONFIRMED_SUCCESS));
        return eachStepSucceeded && current.graphCompleted()
                ? SagaDisposition.SUCCEEDED : SagaDisposition.RUNNING;
    }

    private static boolean allStepsSucceeded(SagaSnapshot snapshot) {
        return snapshot.definition().steps().keySet().stream().allMatch(step -> snapshot.occurrences().values()
                .stream().anyMatch(value -> value.stepId().equals(step)
                        && value.status() == SagaStepStatus.CONFIRMED_SUCCESS));
    }

    private static SagaSnapshot replace(SagaSnapshot current, SagaStepSnapshot step,
                                        SagaDisposition disposition, String reason, Instant now) {
        var occurrences = new LinkedHashMap<>(current.occurrences());
        occurrences.put(step.occurrenceId(), step);
        return new SagaSnapshot(current.key(), current.sagaId(), current.traversalId(), current.definition(),
                current.revision() + 1,
                disposition, current.cancellationRequested(), occurrences, current.deadline(),
                current.createdAt(), now, reason, current.graphCompleted());
    }

    private static SagaStepSnapshot copy(SagaStepSnapshot old, SagaStepStatus status, String detail, Instant now) {
        return new SagaStepSnapshot(old.occurrenceId(), old.stepId(), old.invocationId(),
                old.forwardOperationId(), old.compensationOperationId(), old.payloadFingerprint(), status,
                old.receipt(), detail, now);
    }

    private static void persist(ExecutionRecorder recorder, SagaSnapshot previous, SagaSnapshot next) {
        persist(recorder, previous, next, null);
    }

    private static void persist(ExecutionRecorder recorder, SagaSnapshot previous, SagaSnapshot next,
                                SagaCommandIntent command) {
        boolean creating = recorder.saga(previous.sagaId()).isEmpty();
        long expected = creating ? 0 : previous.revision();
        if (creating) {
            next = new SagaSnapshot(next.key(), next.sagaId(), next.traversalId(), next.definition(),
                    1, next.disposition(),
                    next.cancellationRequested(), next.occurrences(), next.deadline(), next.createdAt(),
                    next.updatedAt(), next.actionableReason(), next.graphCompleted());
        }
        recorder.recordSaga(List.of(new SagaWrite(id("mutation", next.sagaId().toString(),
                Long.toString(next.revision())), expected, next)), command == null ? List.of() : List.of(command));
    }

    private static SagaCommandIntent participantIntent(GraphNode node, NodeMessage message, UUID sagaId,
                                                       String operationId, UUID causalMessageId,
                                                       Instant notBefore, String participantContract,
                                                       String direction) {
        if ("pure".equals(participantContract)) return null;
        var security = new LinkedHashMap<String, Object>();
        security.put("requestId", message.security().requestId());
        security.put("tenantId", message.security().tenantId());
        security.put("subject", message.security().subject());
        security.put("principalType", message.security().principalType().name());
        security.put("issuer", message.security().issuer());
        var frozen = new LinkedHashMap<String, Object>();
        frozen.put("behavior", node.behavior());
        frozen.put("nodeId", node.id());
        frozen.put("properties", node.properties());
        frozen.put("payload", message.payload());
        frozen.put("attributes", message.attributes());
        frozen.put("security", security);
        frozen.put("processInstanceId", message.processInstanceId().toString());
        frozen.put("traversalId", message.traversalId().toString());
        frozen.put("invocationId", message.invocationId().toString());
        frozen.put("attemptId", message.attemptId().toString());
        byte[] payload = ai.ravenroot.api.payload.PayloadJson.write(
                ai.ravenroot.api.payload.PayloadValue.fromJava(frozen,
                        ai.ravenroot.api.payload.PayloadLimits.DEFAULTS)).getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(sha256().digest(payload));
        String destination = "participant:" + participantContract + ":" + node.behavior();
        String commandType = "saga." + direction + "." + participantContract + ".v1";
        return new SagaCommandIntent(id("message", operationId), sagaId, operationId,
                destination, commandType, 1,
                OpaquePayload.of(payload, "application/json"), digest, causalMessageId, notBefore, 20);
    }

    private static SagaSnapshot require(ExecutionRecorder recorder, UUID sagaId) {
        return recorder.saga(sagaId).orElseThrow(() -> new IllegalStateException("persisted saga disappeared"));
    }

    private static SagaStepSnapshot requireOccurrence(SagaSnapshot snapshot, UUID occurrenceId) {
        SagaStepSnapshot occurrence = snapshot.occurrences().get(occurrenceId);
        if (occurrence == null) throw new IllegalStateException("persisted saga occurrence disappeared");
        return occurrence;
    }

    private static NodeMessage enrich(GraphNode node, NodeMessage original, UUID sagaId, UUID occurrenceId,
                                      String operationId, String forwardOperationId,
                                      String compensationId, String fingerprint) {
        var attributes = new LinkedHashMap<>(original.attributes());
        putIdentity(attributes, sagaId, occurrenceId, operationId, forwardOperationId,
                compensationId, fingerprint);
        Object payload = original.payload();
        if (payload instanceof Map<?, ?> map) {
            var enriched = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> enriched.put(String.valueOf(key), value));
            if ("amqp.publish".equals(node.behavior())) {
                // The immediate adapter path and the durable command-outbox path must publish the
                // same business message identity.  A broker confirm may be lost after the first
                // publish; using the outbox record id on both paths lets the participant inbox
                // discard that redelivery atomically with its effect.
                // This identity belongs to the current frozen operation.  The incoming payload may
                // have crossed an earlier saga step and therefore carry that step's message id; an
                // authored payload may also try to supply one.  Neither is authority for the new
                // operation, especially when this is the compensation paired with a forward intent.
                enriched.put("messageId", id("message", operationId).toString());
                if (enriched.get("bodyJson") instanceof Map<?, ?> rawBody) {
                    var body = new LinkedHashMap<String, Object>();
                    rawBody.forEach((key, value) -> body.put(String.valueOf(key), value));
                    body.put("operationId", operationId);
                    body.put("commandType", String.valueOf(node.properties().getOrDefault(
                            "saga.commandType", node.id())));
                    body.put("payloadFingerprint", fingerprint);
                    if (!operationId.equals(forwardOperationId)) {
                        body.put("causalMessageId", forwardOperationId);
                    }
                    enriched.put("bodyJson", java.util.Collections.unmodifiableMap(body));
                }
            } else if ("jdbc.insert".equals(node.behavior()) && enriched.get("parameters") instanceof Map<?, ?> raw) {
                var parameters = new LinkedHashMap<String, Object>();
                raw.forEach((key, value) -> parameters.put(String.valueOf(key), value));
                // Runtime-owned identity must replace values left by a forward delivery and values
                // supplied by a graph payload.  Preserving either would make the compensation use
                // the forward receipt key, or let authored data choose the participant identity.
                parameters.put("sagaOperationId", operationId);
                parameters.put("sagaPayloadFingerprint", fingerprint);
                enriched.put("parameters", java.util.Collections.unmodifiableMap(parameters));
            } else {
                putIdentity(enriched, sagaId, occurrenceId, operationId, forwardOperationId,
                        compensationId, fingerprint);
            }
            payload = java.util.Collections.unmodifiableMap(enriched);
        }
        return new NodeMessage(original.security(), original.processInstanceId(), original.traversalId(),
                original.invocationId(), original.attemptId(), original.parentInvocationIds(), original.nodeId(),
                payload, attributes, original.command());
    }

    private static void putIdentity(Map<String, Object> target, UUID sagaId, UUID occurrenceId,
                                    String operationId, String forwardOperationId,
                                    String compensationId, String fingerprint) {
        target.put("sagaId", sagaId.toString());
        target.put("sagaStepOccurrenceId", occurrenceId.toString());
        target.put("sagaOperationId", operationId);
        target.put("sagaForwardOperationId", forwardOperationId);
        target.put("sagaCompensationOperationId", compensationId);
        target.put("sagaPayloadFingerprint", fingerprint);
    }

    private static String operation(String kind, UUID sagaId, String stepId, UUID occurrenceId) {
        return kind + ":" + sagaId + ":" + stepId + ":" + occurrenceId;
    }

    private static UUID id(String... parts) {
        MessageDigest digest = sha256();
        digest.update(longBytes(ID_NAMESPACE.getMostSignificantBits()));
        digest.update(longBytes(ID_NAMESPACE.getLeastSignificantBits()));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(longBytes(bytes.length));
            digest.update(bytes);
        }
        byte[] hash = digest.digest();
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(hash);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private static byte[] longBytes(long value) {
        return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static String fingerprint(Object payload) {
        MessageDigest digest = sha256();
        canonical(payload, digest);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void canonical(Object value, MessageDigest digest) {
        if (value == null) { digest.update((byte) 0); return; }
        if (value instanceof Boolean flag) { digest.update((byte) 1); digest.update((byte) (flag ? 1 : 0)); return; }
        if (value instanceof Number number) {
            digest.update((byte) 2);
            digest.update(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString()
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (value instanceof CharSequence text) {
            digest.update((byte) 3); digest.update(text.toString().getBytes(StandardCharsets.UTF_8)); return;
        }
        if (value instanceof Map<?, ?> map) {
            digest.update((byte) 4);
            map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> { canonical(String.valueOf(entry.getKey()), digest); canonical(entry.getValue(), digest); });
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            digest.update((byte) 5); iterable.forEach(item -> canonical(item, digest)); return;
        }
        if (value.getClass().isArray()) {
            digest.update((byte) 6);
            for (int index = 0; index < Array.getLength(value); index++) canonical(Array.get(value, index), digest);
            return;
        }
        throw new IllegalArgumentException("saga payload is outside the bounded portable payload model");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String bounded(String value) { return value.length() <= 512 ? value : value.substring(0, 512); }

    private static Instant deadline(Instant createdAt, Duration duration) {
        return duration == null ? null : createdAt.plus(duration);
    }

    private static boolean confirmedNoEffect(Invocation invocation, Throwable failure) {
        if ("pure".equals(invocation.participantContract())) return true;
        Throwable current = failure;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if (current instanceof RetryClassified classified) {
                Retryability retryability = classified.retryability();
                return retryability != null && retryability != Retryability.INDETERMINATE;
            }
            if (!(current instanceof java.util.concurrent.CompletionException
                    || current instanceof java.util.concurrent.ExecutionException)) break;
            current = current.getCause();
        }
        return false;
    }

    private static SagaDisposition expiredDisposition(SagaSnapshot current) {
        boolean unknown = current.occurrences().values().stream().anyMatch(step ->
                step.status() == SagaStepStatus.DISPATCHED
                        || step.status() == SagaStepStatus.OUTCOME_UNKNOWN
                        || step.status() == SagaStepStatus.COMPENSATION_UNKNOWN);
        if (unknown) return SagaDisposition.UNRESOLVED;
        boolean effect = current.occurrences().values().stream().anyMatch(step ->
                step.status() == SagaStepStatus.CONFIRMED_SUCCESS
                        || step.status() == SagaStepStatus.COMPENSATING);
        return effect ? SagaDisposition.COMPENSATION_PENDING : SagaDisposition.COMPENSATED;
    }

    record Before(NodeMessage message, NodeResult replay, Invocation invocation) { }
    record Invocation(UUID sagaId, UUID occurrenceId, String stepId, boolean compensation,
                      String participantContract, boolean businessCompletionRequired) { }
}
