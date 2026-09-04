package ai.ravenroot.core.humantask;

import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableHumanTask;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerTransition;
import ai.ravenroot.api.persistence.HumanTaskPage;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskRegistration;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.HumanTaskTransition;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.ExecutionRecorder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Reference monitor and transport-neutral inbox for first-class durable human tasks. */
public final class HumanTaskService {
    public static final String HANDLER_NAME = "human-task";
    private static final String EVENT_CONTENT_TYPE = "application/vnd.ravenroot.human-task-event+json";
    private static final int MAX_WRITE_ATTEMPTS = 3;

    private final ExecutionStore store;
    private final Clock clock;
    private final Map<ExecutionKey, ExecutionRecorder> liveRecorders = new ConcurrentHashMap<>();
    private volatile Set<String> recoverableTenants;

    public HumanTaskService(ExecutionStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!store.supports(StoreCapability.DURABLE)
                || !store.supports(StoreCapability.HUMAN_TASKS)
                || !store.supports(StoreCapability.DURABLE_HANDLERS)
                || !store.supports(StoreCapability.EVENT_JOURNAL)) {
            throw new IllegalArgumentException(
                    "human-task requires durable human tasks, handlers, timers, and journal support");
        }
    }

    public AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(recorder, "recorder");
        if (!key.tenantId().equals(recorder.tenantId())
                || !key.processInstanceId().equals(recorder.processInstanceId())) {
            throw new IllegalArgumentException("recorder belongs to a different execution");
        }
        if (liveRecorders.putIfAbsent(key, recorder) != null) {
            throw new IllegalStateException("a live recorder is already bound for this execution");
        }
        return () -> liveRecorders.remove(key, recorder);
    }

    public void restrictRecoveryTenants(Set<String> tenantIds) {
        Set<String> snapshot = Set.copyOf(Objects.requireNonNull(tenantIds, "tenantIds"));
        if (snapshot.isEmpty() || snapshot.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("recovery tenant ids must be non-empty safe values");
        }
        recoverableTenants = snapshot;
    }

    public HumanTaskResult suspend(NodeMessage message, HumanTaskDefinition definition) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(definition, "definition");
        ExecutionKey key = new ExecutionKey(message.security().tenantId(), message.processInstanceId());
        Set<String> configured = recoverableTenants;
        if (configured != null && !configured.contains(key.tenantId())) {
            return new HumanTaskResult(HumanTaskResult.Code.UNAVAILABLE, null, null);
        }
        ExecutionRecorder recorder = liveRecorders.get(key);
        if (recorder == null) return new HumanTaskResult(HumanTaskResult.Code.UNAVAILABLE, null, null);
        UUID taskId = taskId(message);
        Instant now = clock.instant();
        var registration = new HumanTaskRegistration(taskId, message.traversalId(),
                message.invocationId(), message.attemptId(), message.nodeId(), taskId.toString(),
                "human-task:" + message.attemptId(), definition.metadata(), definition.responseSchema(),
                definition.responderRequirements(), message.security(), recorder.graphVersionPin(),
                definition.escalationDelay().map(now::plus), now.plus(definition.expiryDelay()),
                definition.reentryMapping());
        DurableHumanTask existing = await(store.loadHumanTask(key.tenantId(), taskId)).orElse(null);
        if (existing != null) {
            return new HumanTaskResult(existing.request().sameRequest(registration)
                    ? HumanTaskResult.Code.ALREADY_APPLIED : HumanTaskResult.Code.ALREADY_SETTLED,
                    existing, resumeTraversalOf(existing));
        }
        var timers = new ArrayList<TimerSchedule>();
        OpaquePayload identity = identityPayload(taskId, HumanTaskStatus.WAITING, 1);
        registration.escalateAt().ifPresent(when -> timers.add(new TimerSchedule(escalationTimerId(taskId), when,
                registration.traversalId(), registration.invocationId(), identity)));
        timers.add(new TimerSchedule(expiryTimerId(taskId), registration.expiresAt(),
                registration.traversalId(), registration.invocationId(), identity));
        var handler = new HandlerRegistration(taskId, HANDLER_NAME, registration.traversalId(),
                registration.invocationId(), registration.correlationKey(), registration.deduplicationKey(),
                new HandlerPayloadSchema(registration.responseSchema().contentType(),
                        registration.responseSchema().schema(), registration.responseSchema().maxBytes()),
                registration.responderRequirements());
        StoredProcessInstance stored = load(key);
        recorder.suspendForHumanTask(registration, handler, timers,
                event(key, stored, registration, "HUMAN_TASK_REQUESTED", message.security().requestId(),
                        registration.traversalId(), HumanTaskStatus.WAITING, 1));
        DurableHumanTask created = await(store.loadHumanTask(key.tenantId(), taskId)).orElseThrow();
        return new HumanTaskResult(HumanTaskResult.Code.CREATED, created, null);
    }

    public HumanTaskPage inbox(RequestContext context, HumanTaskQuery query) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");
        if (query.limit() < 1 || query.limit() > store.maxHumanTaskPageSize()) {
            throw new IllegalArgumentException("human-task page limit must be between 1 and "
                    + store.maxHumanTaskPageSize());
        }
        return await(store.listHumanTasks(context.tenantId(), query));
    }

    public HumanTaskResult resolve(RequestContext context, UUID taskId, long expectedGeneration,
                                   OpaquePayload response) {
        return settle(context, taskId, expectedGeneration, HumanTaskStatus.RESOLVED, response);
    }

    public HumanTaskResult deny(RequestContext context, UUID taskId, long expectedGeneration) {
        return settle(context, taskId, expectedGeneration, HumanTaskStatus.DENIED, null);
    }

    public HumanTaskResult cancel(RequestContext context, UUID taskId, long expectedGeneration) {
        return settle(context, taskId, expectedGeneration, HumanTaskStatus.CANCELLED, null);
    }

    public HumanTaskResult escalate(ExecutionKey key, UUID taskId, long expectedGeneration,
                                    String correlationId) {
        return nonTerminal(key, taskId, expectedGeneration, correlationId, null);
    }

    public HumanTaskResult expire(ExecutionKey key, UUID taskId, long expectedGeneration,
                                  String correlationId) {
        DurableHumanTask task = await(store.loadHumanTask(key.tenantId(), taskId))
                .filter(candidate -> candidate.key().equals(key)).orElse(null);
        if (task == null) return new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null);
        return commitTerminal(task, expectedGeneration, HumanTaskStatus.EXPIRED, "", null, correlationId, null);
    }

    public boolean ownsTimer(PendingWork.TimerDue timer) {
        UUID taskId = taskIdFromTimer(timer);
        if (taskId == null) return false;
        DurableHumanTask task = await(store.loadHumanTask(timer.key().tenantId(), taskId)).orElse(null);
        if (task == null || !task.key().equals(timer.key())) return false;
        return (expiryTimerId(task.request().taskId()).equals(timer.workItemId())
                || escalationTimerId(task.request().taskId()).equals(timer.workItemId()))
                && task.request().traversalId().equals(timer.traversalId())
                && task.request().invocationId().equals(timer.invocationId());
    }

    /** Applies exactly the claimed timer, preserving its generation fence. */
    public boolean applyClaimedTimer(PendingWork.TimerDue timer, String correlationId) {
        UUID taskId = taskIdFromTimer(timer);
        if (taskId == null) return false;
        DurableHumanTask task = await(store.loadHumanTask(timer.key().tenantId(), taskId))
                .filter(candidate -> candidate.key().equals(timer.key())).orElse(null);
        if (task == null) return false;
        if (timer.workItemId().equals(escalationTimerId(task.request().taskId()))) {
            if (task.status() == HumanTaskStatus.ESCALATED || task.status().terminal()) return true;
            if (!clock.instant().isBefore(task.request().expiresAt())) {
                HumanTaskResult.Code code = commitTerminal(task, task.generation(), HumanTaskStatus.EXPIRED,
                        "", null, correlationId, timer.fencingToken()).code();
                return code == HumanTaskResult.Code.EXPIRED
                        || code == HumanTaskResult.Code.ALREADY_APPLIED;
            }
            HumanTaskResult.Code code = nonTerminal(task.key(), task.request().taskId(), task.generation(),
                    correlationId, timer.fencingToken()).code();
            return code == HumanTaskResult.Code.ESCALATED || code == HumanTaskResult.Code.ALREADY_APPLIED;
        }
        if (timer.workItemId().equals(expiryTimerId(task.request().taskId()))) {
            if (task.status().terminal()) return true;
            HumanTaskResult.Code code = commitTerminal(task, task.generation(), HumanTaskStatus.EXPIRED,
                    "", null, correlationId, timer.fencingToken()).code();
            return code == HumanTaskResult.Code.EXPIRED || code == HumanTaskResult.Code.ALREADY_APPLIED;
        }
        return false;
    }

    private UUID taskIdFromTimer(PendingWork.TimerDue timer) {
        if (timer.payload() == null || !EVENT_CONTENT_TYPE.equals(timer.payload().contentType())
                || timer.payload().size() > 512) return null;
        String value = new String(timer.payload().bytes(), StandardCharsets.UTF_8);
        var match = java.util.regex.Pattern.compile("\\\"taskId\\\":\\\"([0-9a-fA-F-]{36})\\\"")
                .matcher(value);
        if (!match.find()) return null;
        try {
            return UUID.fromString(match.group(1));
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private HumanTaskResult settle(RequestContext context, UUID taskId, long expectedGeneration,
                                   HumanTaskStatus target, OpaquePayload response) {
        Objects.requireNonNull(context, "context");
        DurableHumanTask task = await(store.loadHumanTask(context.tenantId(), taskId)).orElse(null);
        if (task == null) return new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null);
        String actor = SecurityContext.of(context).qualifiedIdentity();
        Set<String> roles = context.roles().stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
        boolean requesterCancellation = target == HumanTaskStatus.CANCELLED
                && actor.equals(task.request().requester().qualifiedIdentity());
        if (!requesterCancellation
                && !task.request().responderRequirements().satisfiedBy(roles, context.scopes())) {
            auditOnly(task, "HUMAN_TASK_UNAUTHORIZED", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.UNAUTHORIZED, task, null);
        }
        boolean possibleRedelivery = task.status() == target
                && task.generation() == expectedGeneration + 1;
        if (possibleRedelivery && target == HumanTaskStatus.RESOLVED
                && !validResponse(task, response)) {
            auditOnly(task, "HUMAN_TASK_PAYLOAD_REFUSED", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.PAYLOAD_REFUSED, task, null);
        }
        if (possibleRedelivery) {
            return new HumanTaskResult(exactRedelivery(task, target, expectedGeneration, actor, response)
                    ? HumanTaskResult.Code.ALREADY_APPLIED : HumanTaskResult.Code.ALREADY_SETTLED,
                    task, resumeTraversalOf(task));
        }
        if (task.status().terminal()) {
            return new HumanTaskResult(HumanTaskResult.Code.ALREADY_SETTLED, task,
                    resumeTraversalOf(task));
        }
        if (expectedGeneration != task.generation()) {
            auditOnly(task, "HUMAN_TASK_STALE_GENERATION", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.STALE_GENERATION, task,
                    resumeTraversalOf(task));
        }
        if (target == HumanTaskStatus.RESOLVED && !validResponse(task, response)) {
            auditOnly(task, "HUMAN_TASK_PAYLOAD_REFUSED", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.PAYLOAD_REFUSED, task, null);
        }
        return commitTerminal(task, expectedGeneration, target, actor, response, context.requestId(), null);
    }

    private boolean validResponse(DurableHumanTask task, OpaquePayload response) {
        if (response == null) return false;
        var schema = task.request().responseSchema();
        if (!schema.contentType().equals(response.contentType()) || response.size() > schema.maxBytes()) {
            return false;
        }
        try {
            PayloadEnvelope envelope = PayloadJson.readEnvelope(response.bytes(), new PayloadLimits(
                    schema.maxBytes(), 32, 1024, 4096, 16 * 1024, 256));
            return schema.schema().equals(envelope.schema())
                    && schema.schemaVersion().equals(envelope.schemaVersion())
                    && schema.kind() == envelope.kind();
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private HumanTaskResult nonTerminal(ExecutionKey key, UUID taskId, long expectedGeneration,
                                        String correlationId, Long fencingToken) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            DurableHumanTask task = await(store.loadHumanTask(key.tenantId(), taskId))
                    .filter(candidate -> candidate.key().equals(key)).orElse(null);
            if (task == null) return new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null);
            if (task.generation() != expectedGeneration) {
                return new HumanTaskResult(HumanTaskResult.Code.STALE_GENERATION, task, null);
            }
            if (task.status() == HumanTaskStatus.ESCALATED) {
                return new HumanTaskResult(HumanTaskResult.Code.ALREADY_APPLIED, task, null);
            }
            if (task.status().terminal()) {
                return new HumanTaskResult(HumanTaskResult.Code.ALREADY_SETTLED, task, resumeTraversalOf(task));
            }
            StoredProcessInstance stored = load(key);
            var builder = ExecutionBatch.to(key).expecting(RevisionExpectation.exactly(stored.revision()));
            if (fencingToken != null) builder.fencedBy(fencingToken);
            var batch = builder.applyHumanTask(new HumanTaskTransition.Escalated(taskId, expectedGeneration))
                    .applyHandler(new HandlerTransition.Escalated(taskId, "attention-threshold"))
                    .cancelTimer(escalationTimerId(taskId))
                    .publish(event(key, stored, task.request(), "HUMAN_TASK_ESCALATED", correlationId,
                            task.request().traversalId(), HumanTaskStatus.ESCALATED, expectedGeneration + 1))
                    .build();
            try {
                await(store.apply(batch));
                return new HumanTaskResult(HumanTaskResult.Code.ESCALATED,
                        await(store.loadHumanTask(key.tenantId(), taskId)).orElseThrow(), null);
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attempt < MAX_WRITE_ATTEMPTS) continue;
                throw conflict;
            }
        }
        throw new IllegalStateException("human-task escalation retry budget exhausted");
    }

    private HumanTaskResult commitTerminal(DurableHumanTask original, long expectedGeneration,
                                           HumanTaskStatus target, String actor, OpaquePayload response,
                                           String correlationId, Long fencingToken) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            DurableHumanTask task = await(store.loadHumanTask(original.key().tenantId(),
                    original.request().taskId())).orElse(null);
            if (task == null || !task.key().equals(original.key())) {
                return new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null);
            }
            if (task.generation() != expectedGeneration) {
                if (exactRedelivery(task, target, expectedGeneration, actor, response)) {
                    return new HumanTaskResult(HumanTaskResult.Code.ALREADY_APPLIED, task,
                            resumeTraversalOf(task));
                }
                return new HumanTaskResult(task.status().terminal()
                        ? HumanTaskResult.Code.ALREADY_SETTLED : HumanTaskResult.Code.STALE_GENERATION,
                        task, resumeTraversalOf(task));
            }
            if (task.status().terminal()) {
                return new HumanTaskResult(HumanTaskResult.Code.ALREADY_SETTLED, task,
                        resumeTraversalOf(task));
            }
            UUID resumeTraversalId = UUID.nameUUIDFromBytes(("human-task-reentry:"
                    + task.request().taskId() + ":" + expectedGeneration).getBytes(StandardCharsets.UTF_8));
            HumanTaskTransition taskTransition = switch (target) {
                case RESOLVED -> new HumanTaskTransition.Resolved(task.request().taskId(), expectedGeneration, actor);
                case DENIED -> new HumanTaskTransition.Denied(task.request().taskId(), expectedGeneration, actor);
                case CANCELLED -> new HumanTaskTransition.Cancelled(task.request().taskId(), expectedGeneration, actor);
                case EXPIRED -> new HumanTaskTransition.Expired(task.request().taskId(), expectedGeneration);
                default -> throw new IllegalArgumentException("not a terminal human-task status: " + target);
            };
            OpaquePayload outcome = target == HumanTaskStatus.RESOLVED
                    ? Objects.requireNonNull(response, "response")
                    : identityPayload(task.request().taskId(), target, expectedGeneration + 1);
            HandlerTransition handlerTransition = switch (target) {
                case RESOLVED -> new HandlerTransition.Resolved(task.request().taskId(), actor,
                        resumeTraversalId, outcome);
                case DENIED, CANCELLED -> new HandlerTransition.Denied(task.request().taskId(),
                        actor, resumeTraversalId, outcome);
                case EXPIRED -> new HandlerTransition.Expired(task.request().taskId(), resumeTraversalId);
                default -> throw new IllegalArgumentException("not terminal");
            };
            StoredProcessInstance stored = load(task.key());
            var builder = ExecutionBatch.to(task.key())
                    .expecting(RevisionExpectation.exactly(stored.revision()));
            if (fencingToken != null) builder.fencedBy(fencingToken);
            var batch = builder
                    .apply(new ExecutionTransition.AttemptTransitioned(task.request().traversalId(),
                            task.request().invocationId(), task.request().attemptId(), NodeAttemptStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptTransitioned(task.request().traversalId(),
                            task.request().invocationId(), task.request().attemptId(), NodeAttemptStatus.COMPLETED))
                    .apply(new ExecutionTransition.InvocationTransitioned(task.request().traversalId(),
                            task.request().invocationId(), NodeInvocationStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationTransitioned(task.request().traversalId(),
                            task.request().invocationId(), NodeInvocationStatus.COMPLETED))
                    .apply(new ExecutionTransition.TraversalTransitioned(task.request().traversalId(),
                            TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(task.request().traversalId(),
                            TraversalStatus.COMPLETED))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalAdded(new Traversal(resumeTraversalId,
                            task.request().nodeId(), TraversalStatus.ACCEPTED, Map.of())))
                    .applyHumanTask(taskTransition)
                    .applyHandler(handlerTransition)
                    .cancelTimer(escalationTimerId(task.request().taskId()))
                    .cancelTimer(expiryTimerId(task.request().taskId()))
                    .publish(event(task.key(), stored, task.request(), "HUMAN_TASK_" + target.name(),
                            correlationId, resumeTraversalId, target, expectedGeneration + 1))
                    .build();
            try {
                await(store.apply(batch));
                return new HumanTaskResult(HumanTaskResult.Code.valueOf(target.name()),
                        await(store.loadHumanTask(task.key().tenantId(), task.request().taskId())).orElseThrow(),
                        resumeTraversalId);
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attempt < MAX_WRITE_ATTEMPTS) continue;
                if (conflict.failure() instanceof ExecutionStoreFailure.HumanTaskNotResolvable refusal) {
                    if (refusal.requested() == HumanTaskStatus.EXPIRED
                            && target != HumanTaskStatus.EXPIRED) {
                        DurableHumanTask current = await(store.loadHumanTask(task.key().tenantId(),
                                task.request().taskId())).orElse(task);
                        return commitTerminal(current, current.generation(), HumanTaskStatus.EXPIRED,
                                "", null, correlationId, fencingToken);
                    }
                    if (attempt < MAX_WRITE_ATTEMPTS) continue;
                }
                throw conflict;
            }
        }
        throw new IllegalStateException("human-task settlement retry budget exhausted");
    }

    private void auditOnly(DurableHumanTask task, String type, String correlationId) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            StoredProcessInstance stored = load(task.key());
            try {
                await(store.apply(ExecutionBatch.to(task.key())
                        .expecting(RevisionExpectation.exactly(stored.revision()))
                        .publish(event(task.key(), stored, task.request(), type, correlationId,
                                task.request().traversalId(), task.status(), task.generation()))
                        .build()));
                return;
            } catch (ExecutionStoreException conflict) {
                if (!(conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict)
                        || attempt == MAX_WRITE_ATTEMPTS) throw conflict;
            }
        }
    }

    private boolean exactRedelivery(DurableHumanTask task, HumanTaskStatus target,
                                    long expectedGeneration, String actor, OpaquePayload response) {
        if (task.status() != target || task.generation() != expectedGeneration + 1
                || !task.actor().equals(actor)) return false;
        if (target != HumanTaskStatus.RESOLVED) return true;
        DurableHandler handler = await(store.loadHandler(task.key(), task.request().taskId())).orElse(null);
        return handler != null && response != null && response.equals(handler.outcomePayload());
    }

    private EventEnvelope event(ExecutionKey key, StoredProcessInstance stored,
                                HumanTaskRegistration request, String eventType, String correlationId,
                                UUID traversalId, HumanTaskStatus status, long generation) {
        return EventEnvelope.of(UUID.randomUUID(), key.tenantId(), eventType, key.processInstanceId(),
                traversalId, request.invocationId(), request.attemptId(), null, correlationId,
                stored.graphVersionPin().reference(), clock.instant(),
                identityPayload(request.taskId(), status, generation));
    }

    private UUID resumeTraversalOf(DurableHumanTask task) {
        DurableHandler handler = await(store.loadHandler(task.key(), task.request().taskId())).orElse(null);
        return handler == null ? null : handler.resumeTraversalId();
    }

    private StoredProcessInstance load(ExecutionKey key) {
        return await(store.load(key));
    }

    private static OpaquePayload identityPayload(UUID taskId, HumanTaskStatus status, long generation) {
        String json = "{\"generation\":" + generation + ",\"status\":\"" + status.name()
                + "\",\"taskId\":\"" + taskId + "\"}";
        return OpaquePayload.of(json.getBytes(StandardCharsets.UTF_8), EVENT_CONTENT_TYPE);
    }

    public static UUID taskId(NodeMessage message) {
        String scope = "human-task:" + message.security().tenantId() + ":" + message.processInstanceId()
                + ":" + message.traversalId() + ":" + message.invocationId() + ":" + message.attemptId();
        return UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8));
    }

    public static UUID escalationTimerId(UUID taskId) {
        return UUID.nameUUIDFromBytes(("human-task-escalation:" + taskId).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID expiryTimerId(UUID taskId) {
        return UUID.nameUUIDFromBytes(("human-task-expiry:" + taskId).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof ExecutionStoreException storeFailure) throw storeFailure;
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            throw wrapped;
        }
    }
}
