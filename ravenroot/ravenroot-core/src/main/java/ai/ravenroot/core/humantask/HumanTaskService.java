package ai.ravenroot.core.humantask;

import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadValue;
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
import ai.ravenroot.api.persistence.HumanTaskAttentionAuthorization;
import ai.ravenroot.api.persistence.HumanTaskAttentionItem;
import ai.ravenroot.api.persistence.HumanTaskAttentionLocator;
import ai.ravenroot.api.persistence.HumanTaskAttentionPage;
import ai.ravenroot.api.persistence.HumanTaskAttentionQuery;
import ai.ravenroot.api.persistence.HumanTaskConfirmationAction;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.api.persistence.HumanTaskOverride;
import ai.ravenroot.api.persistence.HumanTaskQuery;
import ai.ravenroot.api.persistence.HumanTaskRegistration;
import ai.ravenroot.api.persistence.HumanTaskStatus;
import ai.ravenroot.api.persistence.HumanTaskSettlement;
import ai.ravenroot.api.persistence.HumanTaskTransition;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.ExecutionRecorder;
import ai.ravenroot.core.runtime.GraphExecutionBudgetSnapshot;
import ai.ravenroot.core.runtime.GraphExecutionContinuationCheckpoint;
import ai.ravenroot.core.runtime.GraphRunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Reference monitor and transport-neutral inbox for first-class durable human tasks. */
public final class HumanTaskService {
    public static final String HANDLER_NAME = "human-task";
    public static final String CONFIRMATION_CONTENT_TYPE =
            ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation.RESPONSE_CONTENT_TYPE;
    public static final String CONFIRMATION_SCHEMA =
            ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation.RESPONSE_SCHEMA;
    public static final String CONFIRMATION_SCHEMA_VERSION =
            ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation.RESPONSE_SCHEMA_VERSION;
    private static final String EVENT_CONTENT_TYPE = "application/vnd.ravenroot.human-task-event+json";
    private final ExecutionStore store;
    private final Clock clock;
    private final HumanTaskPolicy policy;
    private final Map<ExecutionKey, LiveBinding> liveRecorders = new ConcurrentHashMap<>();
    private volatile Set<String> recoverableTenants;

    public HumanTaskService(ExecutionStore store, Clock clock) {
        this(store, clock, HumanTaskPolicy.DEFAULTS);
    }

    public HumanTaskService(ExecutionStore store, Clock clock, HumanTaskPolicy policy) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (!store.supports(StoreCapability.DURABLE)
                || !store.supports(StoreCapability.HUMAN_TASKS)
                || !store.supports(StoreCapability.DURABLE_HANDLERS)
                || !store.supports(StoreCapability.EVENT_JOURNAL)) {
            throw new IllegalArgumentException(
                    "human-task requires durable human tasks, handlers, timers, and journal support");
        }
        if (store.maxHumanTaskResponsePayloadBytes() < policy.maxResponseBytes()) {
            // This constructor runs in the composition root before the HTTP listener exists. Keep
            // the diagnostic independent of adapter details and paths: operators need to know which
            // contract is incompatible, while raw persistence diagnostics belong below this layer.
            throw new IllegalArgumentException(
                    "human-task response policy exceeds the durable store capacity");
        }
    }

    public AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder) {
        return bindLive(key, recorder,
                (java.util.function.Function<NodeMessage, GraphExecutionBudgetSnapshot>) null);
    }

    /** Binds the recorder and trusted graph-budget source used by durable production re-entry. */
    public AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder,
                                  java.util.function.Function<NodeMessage,
                                          GraphExecutionBudgetSnapshot> budgetSnapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(recorder, "recorder");
        if (!key.tenantId().equals(recorder.tenantId())
                || !key.processInstanceId().equals(recorder.processInstanceId())) {
            throw new IllegalArgumentException("recorder belongs to a different execution");
        }
        var binding = new LiveBinding(recorder, budgetSnapshot, null);
        if (liveRecorders.putIfAbsent(key, binding) != null) {
            throw new IllegalStateException("a live recorder is already bound for this execution");
        }
        return () -> liveRecorders.remove(key, binding);
    }

    /** Binds the runner that owns both the graph budget and pending join-arrival checkpoint. */
    public AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder, GraphRunner runner) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(runner, "runner");
        if (!key.tenantId().equals(recorder.tenantId())
                || !key.processInstanceId().equals(recorder.processInstanceId())) {
            throw new IllegalArgumentException("recorder belongs to a different execution");
        }
        var binding = new LiveBinding(recorder, runner::continuationBudget, runner);
        if (liveRecorders.putIfAbsent(key, binding) != null) {
            throw new IllegalStateException("a live recorder is already bound for this execution");
        }
        return () -> liveRecorders.remove(key, binding);
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
        LiveBinding binding = liveRecorders.get(key);
        if (binding == null) return new HumanTaskResult(HumanTaskResult.Code.UNAVAILABLE, null, null);
        ExecutionRecorder recorder = binding.recorder();
        UUID taskId = taskId(message);
        DurableHumanTask existing = await(store.loadHumanTask(key.tenantId(), taskId)).orElse(null);
        Instant now = clock.instant();
        int continuationVersion = 1;
        byte[] continuation = new byte[0];
        if (binding.runner() != null) {
            continuation = binding.runner().humanTaskContinuation(message, 1, new byte[0]);
            continuationVersion = GraphExecutionContinuationCheckpoint.VERSION;
        } else if (binding.budgetSnapshot() != null) {
            continuation = GraphExecutionContinuationCheckpoint.write(
                    1, new byte[0], binding.budgetSnapshot().apply(message));
            continuationVersion = GraphExecutionContinuationCheckpoint.VERSION;
        }
        // Do not re-read a retry's mutable source path. The deterministic identity owns the first
        // admitted bytes, including when the later payload is missing, non-text, or oversized.
        var reviewPresentation = existing == null
                ? definition.reviewDefinition().presentation(message.payload())
                : existing.request().reviewPresentation();
        var registration = new HumanTaskRegistration(taskId, message.traversalId(),
                message.invocationId(), message.attemptId(), message.nodeId(), taskId.toString(),
                "human-task:" + message.attemptId(), definition.metadata(), definition.responseSchema(),
                definition.responderRequirements(), message.security(), recorder.graphVersionPin(),
                definition.escalationDelay().map(delay -> deadline(now, delay, "escalation")),
                deadline(now, definition.expiryDelay(), "expiry"),
                definition.reentryMapping(), definition.executionLimits(), continuationVersion, continuation,
                ai.ravenroot.api.persistence.ToolApprovalRegistration.digest(continuation),
                definition.confirmationPresentation(), definition.confirmationPresentation().embedded()
                        ? policy.confirmationLimits()
                        : ai.ravenroot.api.persistence.HumanTaskConfirmationLimits.CLASSIC,
                reviewPresentation, definition.presentation());
        if (existing != null) {
            // A deterministic retry reuses the first committed review bytes even when its
            // upstream payload has since changed. Nothing re-derives or overwrites the review.
            return new HumanTaskResult(existing.request().sameRequest(registration)
                    ? HumanTaskResult.Code.ALREADY_APPLIED : HumanTaskResult.Code.ALREADY_SETTLED,
                    existing, resumeTraversalOf(existing));
        }
        policy.requireNewRegistration(registration, now);
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

    private static Instant deadline(Instant now, Duration delay, String name) {
        try {
            return now.plus(delay);
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException(
                    "human-task registration refused: " + name + " is outside active policy");
        }
    }

    private record LiveBinding(ExecutionRecorder recorder,
                               java.util.function.Function<NodeMessage,
                                       GraphExecutionBudgetSnapshot> budgetSnapshot,
                               GraphRunner runner) { }

    public HumanTaskPage inbox(RequestContext context, HumanTaskQuery query) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");
        if (query.limit() < 1 || query.limit() > policy.inboxMaxPageSize()) {
            throw new IllegalArgumentException("human-task page limit must be between 1 and "
                    + policy.inboxMaxPageSize());
        }
        return await(store.listHumanTasks(context.tenantId(), query));
    }

    /** Administrative consistency classes, ordered from ordinary to unsafe. */
    public enum AdminClassification { ACTIONABLE, TERMINAL, ORPHANED, NON_RESUMABLE }

    /** A payload-free diagnostic row. Timer values describe the task-owned timer contract only. */
    public record AdminItem(UUID taskId, HumanTaskStatus status, long generation,
                            AdminClassification classification, String reason,
                            String tenantId, String graphVersion, Optional<String> deploymentId,
                            UUID processInstanceId, UUID traversalId, String nodeId,
                            String processStatus, String traversalStatus, String handlerStatus,
                            String escalationTimer, String expiryTimer,
                            Instant createdAt, Instant expiresAt) { }

    /** Bounded administrative selector. At least one narrowing selector is required for mutation. */
    public record AdminQuery(Optional<UUID> taskId, Set<HumanTaskStatus> statuses,
                             Optional<String> deploymentId, Optional<String> graphVersion,
                             Optional<UUID> processInstanceId, Optional<UUID> traversalId,
                             Optional<String> nodeId, Set<AdminClassification> classifications,
                             Optional<Instant> createdBefore, Optional<Instant> expiresBefore,
                             Optional<UUID> cursor, int limit) {
        public AdminQuery {
            taskId = taskId == null ? Optional.empty() : taskId;
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
            deploymentId = deploymentId == null ? Optional.empty() : deploymentId;
            graphVersion = graphVersion == null ? Optional.empty() : graphVersion;
            processInstanceId = processInstanceId == null ? Optional.empty() : processInstanceId;
            traversalId = traversalId == null ? Optional.empty() : traversalId;
            nodeId = nodeId == null ? Optional.empty() : nodeId;
            classifications = classifications == null ? Set.of() : Set.copyOf(classifications);
            createdBefore = createdBefore == null ? Optional.empty() : createdBefore;
            expiresBefore = expiresBefore == null ? Optional.empty() : expiresBefore;
            cursor = cursor == null ? Optional.empty() : cursor;
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("admin task limit must be 1..100");
        }

        public boolean bounded() {
            return taskId.isPresent() || !statuses.isEmpty() || deploymentId.isPresent()
                    || graphVersion.isPresent() || processInstanceId.isPresent()
                    || traversalId.isPresent() || nodeId.isPresent() || !classifications.isEmpty()
                    || createdBefore.isPresent() || expiresBefore.isPresent();
        }
    }

    public record AdminPage(List<AdminItem> items, Optional<UUID> nextCursor) {
        public AdminPage { items = List.copyOf(items); nextCursor = nextCursor == null ? Optional.empty() : nextCursor; }
    }

    public enum AdminPurgeMode { CANCEL, FORCE_ABANDON }

    public record AdminPurgeItem(UUID taskId, long generation, String outcome, String plannedTransition) { }

    public record AdminPurgeResult(boolean dryRun, AdminPurgeMode mode, List<AdminPurgeItem> items) {
        public AdminPurgeResult { items = List.copyOf(items); }
    }

    /** Lists a bounded safe projection without response, continuation, credential, or payload bytes. */
    public AdminPage adminInventory(String tenantId, AdminQuery query) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(query, "query");
        var result = new ArrayList<AdminItem>();
        HumanTaskQuery scan = new HumanTaskQuery(Set.of(), true, query.cursor(), policy.inboxMaxPageSize());
        Optional<UUID> next = Optional.empty();
        while (result.size() < query.limit()) {
            HumanTaskPage page = await(store.listHumanTasks(tenantId, scan));
            UUID lastScanned = null;
            for (DurableHumanTask task : page.items()) {
                lastScanned = task.request().taskId();
                AdminItem item = adminItem(task);
                if (adminMatches(item, query)) {
                    result.add(item);
                    if (result.size() == query.limit()) break;
                }
            }
            if (result.size() == query.limit()) {
                next = Optional.ofNullable(lastScanned);
                break;
            }
            if (page.nextCursor().isEmpty()) {
                break;
            }
            scan = scan.after(page.nextCursor().orElseThrow());
        }
        return new AdminPage(result, next);
    }

    /** Dry-runs or atomically reconciles each bounded candidate using ordinary or forced semantics. */
    public AdminPurgeResult adminPurge(RequestContext context, AdminQuery query,
                                       AdminPurgeMode mode, boolean dryRun, String idempotencyKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(mode, "mode");
        if (!query.bounded()) throw new IllegalArgumentException("an administrative purge requires a selector");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("a bounded idempotency key is required");
        }
        AdminPage candidates = adminInventory(context.tenantId(), query);
        var outcomes = new ArrayList<AdminPurgeItem>();
        for (AdminItem item : candidates.items()) {
            String planned = mode == AdminPurgeMode.CANCEL ? "CANCEL_AND_REENTER" : "ABANDON_WITHOUT_REENTRY";
            if (dryRun) {
                outcomes.add(new AdminPurgeItem(item.taskId(), item.generation(), "PLANNED", planned));
                continue;
            }
            DurableHumanTask task = await(store.loadHumanTask(context.tenantId(), item.taskId())).orElse(null);
            if (task == null) {
                outcomes.add(new AdminPurgeItem(item.taskId(), item.generation(), "NOT_FOUND", planned));
            } else if (task.generation() != item.generation()) {
                outcomes.add(new AdminPurgeItem(item.taskId(), task.generation(), "STALE_GENERATION", planned));
            } else if (task.status().terminal()) {
                outcomes.add(new AdminPurgeItem(item.taskId(), task.generation(), "ALREADY_TERMINAL", planned));
            } else if (mode == AdminPurgeMode.CANCEL) {
                HumanTaskResult settled = commitTerminal(task, task.generation(), HumanTaskStatus.CANCELLED,
                        SecurityContext.of(context).qualifiedIdentity(), null, "",
                        idempotencyKey + ":" + task.request().taskId(), null);
                outcomes.add(new AdminPurgeItem(item.taskId(), settled.task().generation(),
                        settled.code().name(), planned));
            } else {
                boolean changed = abandon(task, SecurityContext.of(context).qualifiedIdentity(),
                        idempotencyKey + ":" + task.request().taskId());
                DurableHumanTask current = await(store.loadHumanTask(context.tenantId(), item.taskId())).orElse(task);
                outcomes.add(new AdminPurgeItem(item.taskId(), current.generation(),
                        changed ? "ABANDONED" : "ALREADY_SETTLED", planned));
            }
        }
        return new AdminPurgeResult(dryRun, mode, outcomes);
    }

    private AdminItem adminItem(DurableHumanTask task) {
        var process = await(store.findProcessInstance(task.key())).orElse(null);
        DurableHandler handler = await(store.loadHandler(task.key(), task.request().taskId())).orElse(null);
        var traversal = process == null ? null : load(task.key()).state().traversals().get(task.request().traversalId());
        AdminClassification classification;
        String reason;
        if (task.status().terminal()) {
            classification = AdminClassification.TERMINAL;
            reason = "task lifecycle is terminal";
        } else if (process == null || traversal == null) {
            classification = AdminClassification.ORPHANED;
            reason = process == null ? "owning process is absent" : "owning traversal is absent";
        } else if (process.status().terminal() || traversal.status().terminal()
                || handler == null || handler.status().terminal()) {
            classification = AdminClassification.NON_RESUMABLE;
            reason = process.status().terminal() ? "owning process is terminal"
                    : traversal.status().terminal() ? "owning traversal is terminal"
                    : handler == null ? "durable handler is absent" : "durable handler is terminal";
        } else {
            classification = AdminClassification.ACTIONABLE;
            reason = "process, traversal, and durable handler can resume";
        }
        String timer = task.status().terminal() ? "SETTLED" : "SCHEDULED_OR_CLAIMED";
        return new AdminItem(task.request().taskId(), task.status(), task.generation(), classification,
                reason, task.key().tenantId(), process == null ? task.request().graphVersionPin().reference()
                        : process.graphVersionPin().reference(),
                process == null ? Optional.empty() : process.deploymentId(), task.key().processInstanceId(),
                task.request().traversalId(), task.request().nodeId(),
                process == null ? "ABSENT" : process.status().name(),
                traversal == null ? "ABSENT" : traversal.status().name(),
                handler == null ? "ABSENT" : handler.status().name(),
                task.request().escalateAt().isEmpty() ? "NOT_CONFIGURED" : timer, timer,
                task.createdAt(), task.request().expiresAt());
    }

    private static boolean adminMatches(AdminItem item, AdminQuery query) {
        return query.taskId().map(item.taskId()::equals).orElse(true)
                && (query.statuses().isEmpty() || query.statuses().contains(item.status()))
                && query.deploymentId().map(value -> item.deploymentId().map(value::equals).orElse(false)).orElse(true)
                && query.graphVersion().map(item.graphVersion()::equals).orElse(true)
                && query.processInstanceId().map(item.processInstanceId()::equals).orElse(true)
                && query.traversalId().map(item.traversalId()::equals).orElse(true)
                && query.nodeId().map(item.nodeId()::equals).orElse(true)
                && (query.classifications().isEmpty() || query.classifications().contains(item.classification()))
                && query.createdBefore().map(instant -> item.createdAt().isBefore(instant)).orElse(true)
                && query.expiresBefore().map(instant -> item.expiresAt().isBefore(instant)).orElse(true);
    }

    private boolean abandon(DurableHumanTask original, String actor, String correlationId) {
        int maxAttempts = original.request().executionLimits().writeAttempts();
        for (int attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber++) {
            DurableHumanTask task = await(store.loadHumanTask(original.key().tenantId(),
                    original.request().taskId())).orElse(null);
            if (task == null || !task.key().equals(original.key()) || task.status().terminal()) return false;
            StoredProcessInstance stored = load(task.key());
            var traversal = stored.state().traversals().get(task.request().traversalId());
            var builder = ExecutionBatch.to(task.key()).expecting(RevisionExpectation.exactly(stored.revision()));
            if (traversal != null && !traversal.status().terminal()) {
                var invocation = traversal.invocations().get(task.request().invocationId());
                if (invocation != null && !invocation.status().terminal()) {
                    var targetAttempt = invocation.attempts().stream()
                            .filter(candidate -> candidate.attemptId().equals(task.request().attemptId()))
                            .findFirst().orElse(null);
                    if (targetAttempt != null && !targetAttempt.status().terminal()) {
                        builder.apply(new ExecutionTransition.AttemptTransitioned(traversal.traversalId(),
                                invocation.invocationId(), targetAttempt.attemptId(), NodeAttemptStatus.FAILED));
                    }
                    builder.apply(new ExecutionTransition.InvocationTransitioned(traversal.traversalId(),
                            invocation.invocationId(), NodeInvocationStatus.FAILED));
                }
                builder.apply(new ExecutionTransition.TraversalTransitioned(traversal.traversalId(),
                        TraversalStatus.FAILED, ExecutionTerminationReason.CANCELLED));
            }
            boolean otherLive = stored.state().traversals().values().stream()
                    .anyMatch(candidate -> (traversal == null || !candidate.traversalId().equals(traversal.traversalId()))
                            && !candidate.status().terminal());
            if (!stored.state().status().terminal() && !otherLive) {
                builder.apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.FAILED,
                        ExecutionTerminationReason.CANCELLED));
            }
            try {
                await(store.apply(builder
                        .applyHumanTask(new HumanTaskTransition.Cancelled(task.request().taskId(),
                                task.generation(), actor, ""))
                        .applyHandler(new HandlerTransition.Cancelled(task.request().taskId(), actor))
                        .cancelTimer(escalationTimerId(task.request().taskId()))
                        .cancelTimer(expiryTimerId(task.request().taskId()))
                        .publish(event(task.key(), stored, task.request(), "HUMAN_TASK_ABANDONED",
                                correlationId, task.request().traversalId(), HumanTaskStatus.CANCELLED,
                                task.generation() + 1)).build()));
                return true;
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attemptNumber < maxAttempts) continue;
                throw conflict;
            }
        }
        throw new IllegalStateException("human-task abandonment retry budget exhausted");
    }

    /**
     * Terminally closes every outstanding task owned by one deployment generation.
     *
     * <p>This is an internal lifecycle action, not a human decision: it creates no re-entry
     * traversal and therefore cannot execute graph work after the deployment cancellation barrier.
     * Each task, handler, timer cancellation, traversal termination, and (when this is its final
     * live traversal) process termination is one compare-and-set batch.</p>
     *
     * @param tenantId tenant authority of the deployment
     * @param deploymentId durable deployment identity recorded on process admission
     * @param correlationId lifecycle command idempotency key used in emitted events
     * @return number of tasks this call moved to the terminal cancelled state
     */
    public int cancelDeploymentTasks(String tenantId, DeploymentId deploymentId,
                                     String correlationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(correlationId, "correlationId");
        int cancelled = 0;
        HumanTaskQuery query = HumanTaskQuery.outstanding(policy.inboxMaxPageSize());
        while (true) {
            HumanTaskPage page = await(store.listHumanTasks(tenantId, query));
            for (DurableHumanTask task : page.items()) {
                var process = await(store.findProcessInstance(task.key())).orElse(null);
                if (process == null || process.deploymentId().isEmpty()
                        || !deploymentId.value().equals(process.deploymentId().orElseThrow())) {
                    continue;
                }
                if (cancelForDeployment(task, correlationId)) cancelled++;
            }
            if (page.nextCursor().isEmpty()) return cancelled;
            query = query.after(page.nextCursor().orElseThrow());
        }
    }

    /** Terminally cancels outstanding tasks belonging to one exact process, without re-entry. */
    public int cancelProcessTasks(String tenantId, UUID processInstanceId, String correlationId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(correlationId, "correlationId");
        int cancelled = 0;
        HumanTaskQuery query = HumanTaskQuery.outstanding(policy.inboxMaxPageSize());
        while (true) {
            HumanTaskPage page = await(store.listHumanTasks(tenantId, query));
            for (DurableHumanTask task : page.items()) {
                if (processInstanceId.equals(task.key().processInstanceId())
                        && cancelForDeployment(task, correlationId)) cancelled++;
            }
            if (page.nextCursor().isEmpty()) return cancelled;
            query = query.after(page.nextCursor().orElseThrow());
        }
    }

    private boolean cancelForDeployment(DurableHumanTask original, String correlationId) {
        int maxAttempts = original.request().executionLimits().writeAttempts();
        for (int attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber++) {
            DurableHumanTask task = await(store.loadHumanTask(original.key().tenantId(),
                    original.request().taskId())).orElse(null);
            if (task == null || !task.key().equals(original.key()) || task.status().terminal()) {
                return false;
            }
            StoredProcessInstance stored = load(task.key());
            var traversal = stored.state().traversals().get(task.request().traversalId());
            if (traversal == null) return false;
            var invocation = traversal.invocations().get(task.request().invocationId());
            if (invocation == null) return false;
            var targetAttempt = invocation.attempts().stream()
                    .filter(candidate -> candidate.attemptId().equals(task.request().attemptId()))
                    .findFirst().orElse(null);
            if (targetAttempt == null) return false;

            var builder = ExecutionBatch.to(task.key())
                    .expecting(RevisionExpectation.exactly(stored.revision()));
            if (!targetAttempt.status().terminal()) {
                builder.apply(new ExecutionTransition.AttemptTransitioned(
                        traversal.traversalId(), invocation.invocationId(),
                        targetAttempt.attemptId(), NodeAttemptStatus.FAILED));
            }
            if (!invocation.status().terminal()) {
                builder.apply(new ExecutionTransition.InvocationTransitioned(
                        traversal.traversalId(), invocation.invocationId(),
                        NodeInvocationStatus.FAILED));
            }
            if (!traversal.status().terminal()) builder.apply(new ExecutionTransition.TraversalTransitioned(
                    traversal.traversalId(), TraversalStatus.FAILED,
                    ExecutionTerminationReason.CANCELLED));
            boolean finalLiveTraversal = stored.state().traversals().values().stream()
                    .filter(candidate -> !candidate.status().terminal()).count() == 1;
            if (!stored.state().status().terminal() && finalLiveTraversal) {
                builder.apply(new ExecutionTransition.ProcessTransitioned(
                        ProcessInstanceStatus.FAILED, ExecutionTerminationReason.CANCELLED));
            }
            var batch = builder
                    .applyHumanTask(new HumanTaskTransition.Cancelled(task.request().taskId(),
                            task.generation(), "deployment-lifecycle", ""))
                    .applyHandler(new HandlerTransition.Cancelled(
                            task.request().taskId(), "deployment-lifecycle"))
                    .cancelTimer(escalationTimerId(task.request().taskId()))
                    .cancelTimer(expiryTimerId(task.request().taskId()))
                    .publish(event(task.key(), stored, task.request(), "HUMAN_TASK_CANCELLED",
                            correlationId, traversal.traversalId(), HumanTaskStatus.CANCELLED,
                            task.generation() + 1))
                    .build();
            try {
                await(store.apply(batch));
                return true;
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attemptNumber < maxAttempts) continue;
                if (conflict.failure() instanceof ExecutionStoreFailure.HumanTaskNotResolvable) {
                    return false;
                }
                throw conflict;
            }
        }
        return false;
    }

    /**
     * Reads authorized actionable embedded tasks in one exact durable runtime context.
     *
     * @param context authenticated caller identity and current authority.
     * @param query exact bounded attention query.
     * @return safe attention page with authoritative counts.
     */
    public HumanTaskAttentionPage attention(RequestContext context, HumanTaskAttentionQuery query) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(query, "query");
        if (query.limit() > policy.confirmation().attentionMaxPageSize()) {
            throw new IllegalArgumentException("human-task page limit must be between 1 and "
                    + policy.confirmation().attentionMaxPageSize());
        }
        Set<String> roles = context.roles().stream().map(Role::name)
                .collect(Collectors.toUnmodifiableSet());
        var authorization = new HumanTaskAttentionAuthorization(
                SecurityContext.of(context).qualifiedIdentity(), roles, context.scopes(),
                policy.responderEnforcementEnabled());
        return await(store.listHumanTaskAttention(context.tenantId(), query, authorization));
    }

    /**
     * Recovers one authorized actionable embedded task without browser-held runtime context.
     *
     * @param context authenticated caller identity and current authority.
     * @param locator durable task identity and exact generation.
     * @return safe task projection, or empty for every unavailable state.
     */
    public Optional<HumanTaskAttentionItem> attention(
            RequestContext context, HumanTaskAttentionLocator locator) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");
        Set<String> roles = context.roles().stream().map(Role::name)
                .collect(Collectors.toUnmodifiableSet());
        var authorization = new HumanTaskAttentionAuthorization(
                SecurityContext.of(context).qualifiedIdentity(), roles, context.scopes(),
                policy.responderEnforcementEnabled());
        return await(store.findHumanTaskAttention(context.tenantId(), locator, authorization));
    }

    /**
     * Recovers exact review content and all pinned actions through an explicit administrative override.
     * @param context authenticated administrative caller scoped to the target tenant
     * @param locator exact task and generation locator
     * @param override bounded audited override intent
     * @return exact authorized projection, or empty for an unavailable task
     */
    public Optional<HumanTaskAttentionItem> attentionOverride(
            RequestContext context, HumanTaskAttentionLocator locator, HumanTaskOverride override) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(locator, "locator");
        Objects.requireNonNull(override, "override");
        if (!overrideAuthorized(context)) return Optional.empty();
        Set<String> roles = context.roles().stream().map(Role::name)
                .collect(Collectors.toUnmodifiableSet());
        var authorization = new HumanTaskAttentionAuthorization(
                SecurityContext.of(context).qualifiedIdentity(), roles, context.scopes(),
                policy.responderEnforcementEnabled(), true);
        Optional<HumanTaskAttentionItem> item = await(store.findHumanTaskAttention(
                context.tenantId(), locator, authorization));
        item.ifPresent(ignored -> auditOverrideAccess(context, locator, override));
        return item;
    }

    /** Exact-detail projection for a registered interaction host after ordinary responder authorization. */
    public record InteractionTask(HumanTaskAttentionItem attention,
                                  ai.ravenroot.api.persistence.HumanTaskResponseSchema responseSchema) { }

    public Optional<InteractionTask> interactionTask(RequestContext context,
                                                     HumanTaskAttentionLocator locator) {
        Optional<HumanTaskAttentionItem> authorized = attention(context, locator);
        if (authorized.isEmpty()) return Optional.empty();
        DurableHumanTask task = await(store.loadHumanTask(context.tenantId(), locator.taskId())).orElse(null);
        if (task == null || task.generation() != locator.generation() || task.status().terminal()) {
            return Optional.empty();
        }
        return Optional.of(new InteractionTask(authorized.orElseThrow(), task.request().responseSchema()));
    }

    /** Persists a capability revocation without retaining the capability or responder payload. */
    public void revokeInteractionCapability(String tenantId,
            ai.ravenroot.api.persistence.HumanTaskInteractionRevocation revocation) {
        await(store.revokeHumanTaskInteraction(tenantId, revocation));
    }

    /** Reads the shared durable revocation fence used by every replica. */
    public boolean interactionCapabilityRevoked(String tenantId, UUID capabilityId, Instant now) {
        return await(store.isHumanTaskInteractionRevoked(tenantId, capabilityId, now));
    }

    /**
     * Reports whether the connected store implements the complete embedded confirmation contract.
     * @return {@code true} when persisted confirmation decisions can be queried and settled
     */
    public boolean supportsConfirmations() {
        return store.supports(StoreCapability.HUMAN_TASK_CONFIRMATIONS)
                && store.supports(StoreCapability.PROCESS_INVENTORY);
    }

    /**
     * Reports whether current admission limits can create new embedded confirmations.
     * @return {@code true} when the runtime and active policy can admit new confirmations
     */
    public boolean supportsConfirmationAdmission() {
        return supportsConfirmations()
                && policy.confirmation().maximumJsonBodyBytes() <= policy.decisionBodyMaxBytes()
                && ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation.responseBytes().length
                <= policy.defaultResponseBytes();
    }

    /**
     * Returns the body and comment budgets for one permitted embedded action, only after tenant and
     * current-authority checks. Empty deliberately combines every unavailable case.
     * @param context authenticated caller
     * @param taskId exact durable task identity
     * @param action requested embedded action
     * @return pinned parser limits when the task exists and the caller may attempt the action
     */
    public Optional<ConfirmationAuthority> confirmationAuthority(
            RequestContext context, UUID taskId, HumanTaskConfirmationAction action) {
        return confirmationAuthority(context, taskId, action, false);
    }

    /** Returns task-pinned parser budgets for a normal or explicit override action. */
    public Optional<ConfirmationAuthority> confirmationAuthority(
            RequestContext context, UUID taskId, HumanTaskConfirmationAction action, boolean override) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(action, "action");
        if (!supportsConfirmations()) return Optional.empty();
        DurableHumanTask task = await(store.loadHumanTask(context.tenantId(), taskId)).orElse(null);
        if (task == null || !task.request().confirmationPresentation().embedded()) return Optional.empty();
        String actor = SecurityContext.of(context).qualifiedIdentity();
        Set<String> roles = context.roles().stream().map(Role::name)
                .collect(Collectors.toUnmodifiableSet());
        if (!authorized(task, context, actionStatus(action), override)) {
            return Optional.empty();
        }
        return Optional.of(new ConfirmationAuthority(
                task.request().executionLimits().decisionBodyMaxBytes(),
                task.request().confirmationLimits().maxCommentUtf8Bytes()));
    }

    /**
     * Projects a completed embedded decision without response, comment, actor, schema, or continuation.
     * The decision itself has already authorized the caller; this method rechecks that current
     * authority still permits the same action before returning the terminal row.
     * @param context authenticated caller
     * @param result authoritative settlement result
     * @param action action applied or exactly replayed
     * @return terminal safe projection, or empty if its context or authority cannot be verified
     */
    public Optional<HumanTaskAttentionItem> confirmationProjection(
            RequestContext context, HumanTaskResult result, HumanTaskConfirmationAction action) {
        return confirmationProjection(context, result, action, false);
    }

    /**
     * Projects a completed embedded decision through ordinary or explicit override authority.
     * @param context authenticated caller
     * @param result authoritative settlement result
     * @param action action applied or exactly replayed
     * @param override whether this projection belongs to an explicit override settlement
     * @return terminal safe projection, or empty if its context or current authority cannot be verified
     */
    public Optional<HumanTaskAttentionItem> confirmationProjection(
            RequestContext context, HumanTaskResult result, HumanTaskConfirmationAction action,
            boolean override) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(action, "action");
        if (!supportsConfirmations()) return Optional.empty();
        DurableHumanTask task = result.task();
        if (task == null || !task.status().terminal()
                || !context.tenantId().equals(task.key().tenantId())
                || !authorized(task, context, actionStatus(action), override)) {
            return Optional.empty();
        }
        var process = await(store.findProcessInstance(task.key())).orElse(null);
        if (process == null) return Optional.empty();
        var request = task.request();
        var limits = request.confirmationLimits();
        return Optional.of(new HumanTaskAttentionItem(request.taskId(), task.generation(), task.status(),
                process.graphVersionPin().reference(), process.deploymentId(), task.key().processInstanceId(),
                request.traversalId(), request.nodeId(), task.createdAt(), request.expiresAt(),
                request.escalateAt(), request.confirmationPresentation(), limits.maxPromptUtf8Bytes(),
                limits.maxActionLabelUtf8Bytes(), limits.maxCommentUtf8Bytes(), List.of(),
                Optional.empty(), request.presentation()));
    }

    /**
     * Fixed server-authored response carried by a successful embedded resolve action.
     * @return canonical boolean-true response envelope
     */
    public static OpaquePayload confirmationResponse() {
        return OpaquePayload.of(
                ai.ravenroot.api.persistence.HumanTaskConfirmationPresentation.responseBytes(),
                CONFIRMATION_CONTENT_TYPE);
    }

    /**
     * Authorized parser budgets for one embedded confirmation request.
     * @param decisionBodyMaxBytes pinned maximum request-body bytes
     * @param commentMaxUtf8Bytes pinned maximum normalized comment bytes
     */
    public record ConfirmationAuthority(int decisionBodyMaxBytes, int commentMaxUtf8Bytes) {
        /** Validates the immutable task-pinned limits. */
        public ConfirmationAuthority {
            if (decisionBodyMaxBytes < 1 || commentMaxUtf8Bytes < 1) {
                throw new IllegalArgumentException("confirmation limits must be positive");
            }
        }
    }

    public HumanTaskResult resolve(RequestContext context, UUID taskId, long expectedGeneration,
                                   OpaquePayload response) {
        return settle(context, taskId, expectedGeneration, HumanTaskSettlement.resolve(response, ""));
    }

    /**
     * Resolves a task while atomically persisting its separate decision comment.
     * @param context authenticated caller
     * @param taskId exact durable task identity
     * @param expectedGeneration optimistic concurrency fence
     * @param response schema-checked response envelope
     * @param comment separate attributable decision comment
     * @return authoritative settlement result
     */
    public HumanTaskResult resolve(RequestContext context, UUID taskId, long expectedGeneration,
                                   OpaquePayload response, String comment) {
        return settle(context, taskId, expectedGeneration,
                HumanTaskSettlement.resolve(response, comment));
    }

    /**
     * Returns the persisted raw-envelope body budget only after tenant lookup and responder
     * authorization. An empty result deliberately combines absent and unauthorized tasks so the
     * HTTP adapter cannot disclose either task existence or its pinned policy before settlement.
     */
    public OptionalInt authorizedResponseBodyLimit(RequestContext context, UUID taskId) {
        return authorizedResponseBodyLimit(context, taskId, false);
    }

    /** Returns the response budget for a normal or explicit override resolve operation. */
    public OptionalInt authorizedResponseBodyLimit(RequestContext context, UUID taskId,
                                                   boolean override) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(taskId, "taskId");
        DurableHumanTask task = await(store.loadHumanTask(context.tenantId(), taskId)).orElse(null);
        if (task == null) return OptionalInt.empty();
        if (!authorized(task, context, HumanTaskStatus.RESOLVED, override)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(task.request().executionLimits().decisionBodyMaxBytes());
    }

    public HumanTaskResult deny(RequestContext context, UUID taskId, long expectedGeneration) {
        return settle(context, taskId, expectedGeneration, HumanTaskSettlement.deny(""));
    }

    /**
     * Denies a task while atomically persisting its separate decision comment.
     * @param context authenticated caller
     * @param taskId exact durable task identity
     * @param expectedGeneration optimistic concurrency fence
     * @param comment separate attributable decision comment
     * @return authoritative settlement result
     */
    public HumanTaskResult deny(RequestContext context, UUID taskId, long expectedGeneration,
                                String comment) {
        return settle(context, taskId, expectedGeneration, HumanTaskSettlement.deny(comment));
    }

    public HumanTaskResult cancel(RequestContext context, UUID taskId, long expectedGeneration) {
        return settle(context, taskId, expectedGeneration, HumanTaskSettlement.cancel(""));
    }

    /**
     * Cancels a task while atomically persisting its separate decision comment.
     * @param context authenticated caller
     * @param taskId exact durable task identity
     * @param expectedGeneration optimistic concurrency fence
     * @param comment separate attributable decision comment
     * @return authoritative settlement result
     */
    public HumanTaskResult cancel(RequestContext context, UUID taskId, long expectedGeneration,
                                  String comment) {
        return settle(context, taskId, expectedGeneration, HumanTaskSettlement.cancel(comment));
    }

    /** Applies the canonical versioned action/response/comment settlement contract. */
    public HumanTaskResult settle(RequestContext context, UUID taskId, long expectedGeneration,
                                  HumanTaskSettlement settlement) {
        return settle(context, taskId, expectedGeneration, settlement, null);
    }

    /** Applies an explicitly authorized and auditable policy override. */
    public HumanTaskResult settleOverride(RequestContext context, UUID taskId, long expectedGeneration,
                                          HumanTaskSettlement settlement, HumanTaskOverride override) {
        return settle(context, taskId, expectedGeneration, settlement,
                Objects.requireNonNull(override, "override"));
    }

    /**
     * Applies a configured external provider's delegated capability after rechecking the complete
     * current task fence. No requester or responder identity is reconstructed from the capability.
     * @param tenantId immutable capability tenant
     * @param capabilityId opaque capability identity used for attribution
     * @param taskId exact bound task identity
     * @param expectedGeneration capability generation fence
     * @param profileId registered provider profile
     * @param profileVersion registered provider profile version
     * @param schemaDigest pinned response-schema digest
     * @param settlement requested canonical settlement
     * @return authoritative settlement result
     */
    public HumanTaskResult settleInteractionCapability(
            String tenantId, UUID capabilityId, UUID taskId, long expectedGeneration,
            String profileId, int profileVersion, String schemaDigest,
            HumanTaskSettlement settlement) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(settlement, "settlement");
        DurableHumanTask task = await(store.loadHumanTask(tenantId, taskId)).orElse(null);
        if (task == null) return new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null);
        var presentation = task.request().presentation();
        boolean generationCurrent = task.generation() == expectedGeneration
                || task.status().terminal() && task.generation() == expectedGeneration + 1;
        if (!generationCurrent || presentation.kind()
                != ai.ravenroot.api.persistence.HumanTaskPresentationKind.EXTERNAL
                || !profileId.equals(presentation.profileId())
                || profileVersion != presentation.profileVersion()
                || !schemaDigest.equals(responseSchemaDigest(task.request().responseSchema()))
                || !task.request().confirmationPresentation().actions().contains(settlement.action())) {
            return new HumanTaskResult(HumanTaskResult.Code.UNAUTHORIZED, task, null);
        }
        RequestContext capability = new RequestContext("human-task-capability:" + capabilityId,
                "configured-external-provider", PrincipalType.WORKLOAD,
                "urn:ravenroot:human-task-interaction", tenantId, Set.of(), Set.of());
        return settle(capability, taskId, expectedGeneration, settlement, null, true);
    }

    private HumanTaskResult settle(RequestContext context, UUID taskId, long expectedGeneration,
                                   HumanTaskSettlement settlement, HumanTaskOverride override) {
        return settle(context, taskId, expectedGeneration, settlement, override, false);
    }

    private HumanTaskResult settle(RequestContext context, UUID taskId, long expectedGeneration,
                                   HumanTaskSettlement settlement, HumanTaskOverride override,
                                   boolean delegatedCapability) {
        Objects.requireNonNull(settlement, "settlement");
        return switch (settlement.action()) {
            case RESOLVE -> settle(context, taskId, expectedGeneration, HumanTaskStatus.RESOLVED,
                    settlement.response().orElseThrow(), settlement.comment(), override,
                    delegatedCapability);
            case DENY -> settle(context, taskId, expectedGeneration, HumanTaskStatus.DENIED,
                    null, settlement.comment(), override, delegatedCapability);
            case CANCEL -> settle(context, taskId, expectedGeneration, HumanTaskStatus.CANCELLED,
                    null, settlement.comment(), override, delegatedCapability);
        };
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
        return commitTerminal(task, expectedGeneration, HumanTaskStatus.EXPIRED, "", null, "",
                correlationId, null);
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
                        "", null, "", correlationId, timer.fencingToken()).code();
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
                    "", null, "", correlationId, timer.fencingToken()).code();
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
                                   HumanTaskStatus target, OpaquePayload response, String comment,
                                   HumanTaskOverride override) {
        return settle(context, taskId, expectedGeneration, target, response, comment, override, false);
    }

    private HumanTaskResult settle(RequestContext context, UUID taskId, long expectedGeneration,
                                   HumanTaskStatus target, OpaquePayload response, String comment,
                                   HumanTaskOverride override, boolean delegatedCapability) {
        Objects.requireNonNull(context, "context");
        DurableHumanTask task = await(store.loadHumanTask(context.tenantId(), taskId)).orElse(null);
        if (task == null) return new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null);
        String actor = SecurityContext.of(context).qualifiedIdentity();
        if (!delegatedCapability && !authorized(task, context, target, override != null)) {
            auditOnly(task, override == null ? "HUMAN_TASK_UNAUTHORIZED"
                    : "HUMAN_TASK_OVERRIDE_UNAUTHORIZED", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.UNAUTHORIZED, task, null);
        }
        if (!permitsDecision(task, target)) {
            auditOnly(task, "HUMAN_TASK_ACTION_REFUSED", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.PAYLOAD_REFUSED, task, null);
        }
        try {
            comment = normalizePinnedComment(task, comment);
        } catch (IllegalArgumentException refused) {
            auditOnly(task, "HUMAN_TASK_COMMENT_REFUSED", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.PAYLOAD_REFUSED, task, null);
        }
        boolean possibleRedelivery = task.status() == target
                && task.generation() == expectedGeneration + 1;
        if (possibleRedelivery && target == HumanTaskStatus.RESOLVED
                && !validResponse(task, response)) {
            auditOnly(task, "HUMAN_TASK_PAYLOAD_REFUSED", context.requestId());
            return new HumanTaskResult(HumanTaskResult.Code.PAYLOAD_REFUSED, task, null);
        }
        if (possibleRedelivery) {
            return new HumanTaskResult(exactRedelivery(task, target, expectedGeneration, actor, response, comment)
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
        return commitTerminal(task, expectedGeneration, target, actor, response, comment,
                context.requestId(), null, override);
    }

    private boolean authorized(DurableHumanTask task, RequestContext context,
                               HumanTaskStatus target, boolean override) {
        if (override) return overrideAuthorized(context);
        if (!policy.responderEnforcementEnabled()) return true;
        String actor = SecurityContext.of(context).qualifiedIdentity();
        if (target == HumanTaskStatus.CANCELLED) {
            return actor.equals(task.request().requester().qualifiedIdentity());
        }
        Set<String> roles = context.roles().stream().map(Role::name)
                .collect(Collectors.toUnmodifiableSet());
        return task.request().responderRequirements().satisfiedBy(roles, context.scopes());
    }

    private static boolean overrideAuthorized(RequestContext context) {
        return context.scopes().contains(AuthorizationAction.HUMAN_TASK_OVERRIDE.requiredScope())
                && (context.roles().contains(Role.TENANT_ADMIN)
                || context.roles().contains(Role.PLATFORM_ADMIN));
    }

    private void auditOverrideAccess(RequestContext context, HumanTaskAttentionLocator locator,
                                     HumanTaskOverride override) {
        DurableHumanTask task = await(store.loadHumanTask(context.tenantId(), locator.taskId())).orElse(null);
        if (task == null) return;
        int maxAttempts = task.request().executionLimits().writeAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            StoredProcessInstance stored = load(task.key());
            try {
                await(store.apply(ExecutionBatch.to(task.key())
                        .expecting(RevisionExpectation.exactly(stored.revision()))
                        .publish(EventEnvelope.of(UUID.randomUUID(), task.key().tenantId(),
                                "HUMAN_TASK_OVERRIDE_REVIEWED", task.key().processInstanceId(),
                                task.request().traversalId(), task.request().invocationId(),
                                task.request().attemptId(), null, context.requestId(),
                                stored.graphVersionPin().reference(), clock.instant(),
                                overrideReviewPayload(locator.taskId(), locator.generation(),
                                        SecurityContext.of(context).qualifiedIdentity(), override.reason())))
                        .build()));
                return;
            } catch (ExecutionStoreException conflict) {
                if (!(conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict)
                        || attempt == maxAttempts) throw conflict;
            }
        }
    }

    private static HumanTaskStatus actionStatus(HumanTaskConfirmationAction action) {
        return switch (action) {
            case RESOLVE -> HumanTaskStatus.RESOLVED;
            case DENY -> HumanTaskStatus.DENIED;
            case CANCEL -> HumanTaskStatus.CANCELLED;
        };
    }

    private HumanTaskAttentionAuthorization attentionAuthorization(RequestContext context) {
        Set<String> roles = context.roles().stream().map(Role::name)
                .collect(Collectors.toUnmodifiableSet());
        return new HumanTaskAttentionAuthorization(
                SecurityContext.of(context).qualifiedIdentity(), roles, context.scopes(),
                policy.responderEnforcementEnabled());
    }

    private boolean validResponse(DurableHumanTask task, OpaquePayload response) {
        if (response == null) return false;
        var schema = task.request().responseSchema();
        if (!schema.contentType().equals(response.contentType()) || response.size() > schema.maxBytes()) {
            return false;
        }
        try {
            PayloadEnvelope envelope = PayloadJson.readEnvelope(response.bytes(),
                    task.request().executionLimits().responsePayload());
            boolean contract = schema.schema().equals(envelope.schema())
                    && schema.schemaVersion().equals(envelope.schemaVersion())
                    && schema.kind() == envelope.kind();
            if (!contract) return false;
            if (task.request().presentation().kind()
                    == ai.ravenroot.api.persistence.HumanTaskPresentationKind.FORM) {
                task.request().presentation().decodedFormSchema().requireResponse(envelope.value());
            }
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private HumanTaskResult nonTerminal(ExecutionKey key, UUID taskId, long expectedGeneration,
                                        String correlationId, Long fencingToken) {
        int maxAttempts = originalWriteAttempts(key, taskId);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
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
                        && attempt < maxAttempts) continue;
                throw conflict;
            }
        }
        throw new IllegalStateException("human-task escalation retry budget exhausted");
    }

    private HumanTaskResult commitTerminal(DurableHumanTask original, long expectedGeneration,
                                           HumanTaskStatus target, String actor, OpaquePayload response,
                                           String comment, String correlationId, Long fencingToken) {
        return commitTerminal(original, expectedGeneration, target, actor, response, comment,
                correlationId, fencingToken, null);
    }

    private HumanTaskResult commitTerminal(DurableHumanTask original, long expectedGeneration,
                                           HumanTaskStatus target, String actor, OpaquePayload response,
                                           String comment, String correlationId, Long fencingToken,
                                           HumanTaskOverride override) {
        int maxAttempts = original.request().executionLimits().writeAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            DurableHumanTask task = await(store.loadHumanTask(original.key().tenantId(),
                    original.request().taskId())).orElse(null);
            if (task == null || !task.key().equals(original.key())) {
                return new HumanTaskResult(HumanTaskResult.Code.NOT_FOUND, null, null);
            }
            if (task.generation() != expectedGeneration) {
                if (exactRedelivery(task, target, expectedGeneration, actor, response, comment)) {
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
                case RESOLVED -> new HumanTaskTransition.Resolved(task.request().taskId(), expectedGeneration, actor, comment);
                case DENIED -> new HumanTaskTransition.Denied(task.request().taskId(), expectedGeneration, actor, comment);
                case CANCELLED -> new HumanTaskTransition.Cancelled(task.request().taskId(), expectedGeneration, actor, comment);
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
            builder
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
                            correlationId, resumeTraversalId, target, expectedGeneration + 1));
            if (override != null) {
                builder.publish(EventEnvelope.of(UUID.randomUUID(), task.key().tenantId(),
                        "HUMAN_TASK_OVERRIDE_APPLIED", task.key().processInstanceId(), resumeTraversalId,
                        task.request().invocationId(), task.request().attemptId(), null, correlationId,
                        stored.graphVersionPin().reference(), clock.instant(),
                        overridePayload(task.request().taskId(), target, expectedGeneration + 1,
                                actor, override.reason())));
            }
            var batch = builder.build();
            try {
                await(store.apply(batch));
                return new HumanTaskResult(HumanTaskResult.Code.valueOf(target.name()),
                        await(store.loadHumanTask(task.key().tenantId(), task.request().taskId())).orElseThrow(),
                        resumeTraversalId);
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attempt < maxAttempts) continue;
                if (conflict.failure() instanceof ExecutionStoreFailure.HumanTaskNotResolvable refusal) {
                    if (refusal.requested() == HumanTaskStatus.EXPIRED
                            && target != HumanTaskStatus.EXPIRED) {
                        DurableHumanTask current = await(store.loadHumanTask(task.key().tenantId(),
                                task.request().taskId())).orElse(task);
                        return commitTerminal(current, current.generation(), HumanTaskStatus.EXPIRED,
                                "", null, "", correlationId, fencingToken);
                    }
                    if (attempt < maxAttempts) continue;
                }
                throw conflict;
            }
        }
        throw new IllegalStateException("human-task settlement retry budget exhausted");
    }

    private void auditOnly(DurableHumanTask task, String type, String correlationId) {
        int maxAttempts = task.request().executionLimits().writeAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
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
                        || attempt == maxAttempts) throw conflict;
            }
        }
    }

    private int originalWriteAttempts(ExecutionKey key, UUID taskId) {
        DurableHumanTask task = await(store.loadHumanTask(key.tenantId(), taskId))
                .filter(candidate -> candidate.key().equals(key)).orElse(null);
        return task == null ? policy.writeAttempts()
                : task.request().executionLimits().writeAttempts();
    }

    private boolean exactRedelivery(DurableHumanTask task, HumanTaskStatus target,
                                    long expectedGeneration, String actor, OpaquePayload response,
                                    String comment) {
        if (task.status() != target || task.generation() != expectedGeneration + 1
                || !task.actor().equals(actor) || !task.decisionComment().equals(comment)) return false;
        if (target != HumanTaskStatus.RESOLVED) return true;
        DurableHandler handler = await(store.loadHandler(task.key(), task.request().taskId())).orElse(null);
        return handler != null && response != null && response.equals(handler.outcomePayload());
    }

    private static String normalizePinnedComment(DurableHumanTask task, String comment) {
        comment = comment == null ? "" : comment.strip();
        var requirement = task.request().confirmationPresentation().commentRequirement();
        if (requirement == ai.ravenroot.api.persistence.HumanTaskCommentRequirement.DISALLOWED
                && !comment.isEmpty()) {
            throw new IllegalArgumentException("decision comment is not allowed by this presentation");
        }
        if (requirement == ai.ravenroot.api.persistence.HumanTaskCommentRequirement.REQUIRED
                && comment.isEmpty()) {
            throw new IllegalArgumentException("decision comment is required by this presentation");
        }
        for (int index = 0; index < comment.length(); index++) {
            char unit = comment.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 == comment.length() || !Character.isLowSurrogate(comment.charAt(index + 1))) {
                    throw new IllegalArgumentException("decision comment contains malformed Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(unit)
                    || (Character.isISOControl(unit) && unit != '\n' && unit != '\t')) {
                throw new IllegalArgumentException("decision comment contains invalid control or Unicode data");
            }
        }
        if (comment.getBytes(StandardCharsets.UTF_8).length
                > task.request().confirmationLimits().maxCommentUtf8Bytes()) {
            throw new IllegalArgumentException("decision comment exceeds pinned byte limit");
        }
        return comment;
    }

    private static boolean permitsDecision(DurableHumanTask task, HumanTaskStatus target) {
        if (!task.request().confirmationPresentation().embedded()) return true;
        ai.ravenroot.api.persistence.HumanTaskConfirmationAction action = switch (target) {
            case RESOLVED -> ai.ravenroot.api.persistence.HumanTaskConfirmationAction.RESOLVE;
            case DENIED -> ai.ravenroot.api.persistence.HumanTaskConfirmationAction.DENY;
            case CANCELLED -> ai.ravenroot.api.persistence.HumanTaskConfirmationAction.CANCEL;
            default -> null;
        };
        return action == null || task.request().confirmationPresentation().actions().contains(action);
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

    private static OpaquePayload overridePayload(UUID taskId, HumanTaskStatus status, long generation,
                                                 String actor, String reason) {
        String json = "{\"actor\":\"" + json(actor) + "\",\"generation\":" + generation
                + ",\"override\":true,\"reasonDigest\":\"sha256:" + sha256(reason)
                + "\",\"status\":\"" + status.name() + "\",\"taskId\":\"" + taskId + "\"}";
        return OpaquePayload.of(json.getBytes(StandardCharsets.UTF_8), EVENT_CONTENT_TYPE);
    }

    private static OpaquePayload overrideReviewPayload(UUID taskId, long generation,
                                                       String actor, String reason) {
        String json = "{\"actor\":\"" + json(actor) + "\",\"generation\":" + generation
                + ",\"outcome\":\"REVIEW_DISCLOSED\",\"override\":true,\"reasonDigest\":\"sha256:"
                + sha256(reason) + "\",\"taskId\":\"" + taskId + "\"}";
        return OpaquePayload.of(json.getBytes(StandardCharsets.UTF_8), EVENT_CONTENT_TYPE);
    }

    private static String responseSchemaDigest(
            ai.ravenroot.api.persistence.HumanTaskResponseSchema schema) {
        String canonical = schema.contentType() + "\n" + schema.schema() + "\n"
                + schema.schemaVersion() + "\n" + schema.kind() + "\n" + schema.maxBytes();
        return "sha256:" + java.util.HexFormat.of().formatHex(sha256Bytes(canonical));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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
