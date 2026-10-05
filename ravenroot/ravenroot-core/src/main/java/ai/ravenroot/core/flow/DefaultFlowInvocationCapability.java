package ai.ravenroot.core.flow;

import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.flow.*;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.core.humantask.DurableHumanTaskSuspension;
import ai.ravenroot.core.humantask.HumanTaskDefinition;
import ai.ravenroot.core.humantask.HumanTaskService;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.GraphExecutionResult;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable, version-pinned implementation behind all three intergraph node types. */
public final class DefaultFlowInvocationCapability implements FlowInvocationCapability, AutoCloseable {
    private static final System.Logger LOGGER =
            System.getLogger(DefaultFlowInvocationCapability.class.getName());
    private static final int RECOVERY_PAGE_SIZE = 1_000;
    private static final int SETTLEMENT_CAS_RETRIES = 8;
    private final ExecutionStore store;
    private final DeploymentRegistry deployments;
    private final DefaultRavenrootApplication application;
    private final HumanTaskService durableContinuations;
    private final FlowTargetAuthorizer authorizer;
    private final FlowInvocationPolicy policy;
    private final Clock clock;
    private final ScheduledExecutorService deadlines;
    private final Set<String> recoveryTenants = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean recoveryLoopStarted = new AtomicBoolean();
    private final AtomicBoolean reconciliationRunning = new AtomicBoolean();
    private final ConcurrentHashMap<DeadlineKey, ScheduledFuture<?>> scheduledDeadlines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FlowHandle> retainedCursors = new ConcurrentHashMap<>();

    public DefaultFlowInvocationCapability(ExecutionStore store, DeploymentRegistry deployments,
                                           DefaultRavenrootApplication application,
                                           HumanTaskService durableContinuations,
                                           FlowTargetAuthorizer authorizer,
                                           FlowInvocationPolicy policy, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.application = Objects.requireNonNull(application, "application");
        this.durableContinuations = Objects.requireNonNull(durableContinuations, "durableContinuations");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlines = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("ravenroot-flow-deadlines-", 0).factory());
    }

    @Override
    public CompletionStage<FlowHandle> start(NodeMessage caller, FlowTarget target, Object input,
                                             Duration deadline) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(target, "target");
        Duration boundedDeadline = boundedDeadline(deadline);
        return store.findFlowInvocationByCaller(caller.security().tenantId(), caller.processInstanceId(),
                        caller.invocationId())
                .thenComposeAsync(existing -> existing.<CompletionStage<FlowHandle>>map(record -> {
                    verifyRetry(record, target);
                    return CompletableFuture.completedFuture(record.handle());
                }).orElseGet(() -> resolveAndCreate(caller, target, input, boundedDeadline)));
    }

    private CompletionStage<FlowHandle> resolveAndCreate(NodeMessage caller, FlowTarget target, Object input,
                                                          Duration deadline) {
        String tenant = caller.security().tenantId();
        return deployments.version(tenant, target.deploymentId(), target.version()).thenCompose(optional -> {
            GraphVersion version = optional.orElseThrow(() ->
                    new IllegalArgumentException("registered target version was not found"));
            if (!authorizer.authorized(caller.security(), version)) {
                throw new SecurityException("caller is not authorized for the target flow");
            }
            FlowGraphContract contract = FlowGraphContract.read(version.canonicalSnapshot());
            Object admittedInput = contract.input(input);
            byte[] encodedInput = PayloadJson.writeJava(admittedInput, PayloadLimits.DEFAULTS);
            Instant now = clock.instant();
            FlowHandle handle = new FlowHandle(UUID.randomUUID());
            UUID childProcess = UUID.randomUUID();
            UUID childTraversal = UUID.randomUUID();
            var intent = new FlowInvocationRecord(tenant, handle, caller.processInstanceId(),
                    caller.traversalId(), caller.invocationId(), caller.security().subject(),
                    caller.security().principalType(), caller.security().issuer(),
                    target.deploymentId(), target.version(),
                    version.canonicalDigest(), childProcess, childTraversal, FlowInvocationStatus.INTENT,
                    encodedInput, null, "", "", null, 1, now, now, now.plus(deadline),
                    now.plus(policy.retention()));
            return store.admitFlowInvocation(intent, policy.maximumUnfinishedPerTenant())
                    .thenComposeAsync(created -> {
                        if (!created.handle().equals(handle)) {
                            verifyRetry(created, target);
                            return CompletableFuture.completedFuture(created.handle());
                        }
                        return launch(created, version, contract, admittedInput).thenApply(ignored -> handle);
                    });
        });
    }

    private CompletionStage<FlowInvocationRecord> launch(FlowInvocationRecord intent, GraphVersion version,
                                                          FlowGraphContract contract, Object admittedInput) {
        final DefaultRavenrootApplication.StartedFlowExecution started;
        try {
            started = application.startCalledGraphMl(securityFor(intent), intent.childProcessInstanceId(),
                    intent.childTraversalId(), new ByteArrayInputStream(version.canonicalSnapshot()), admittedInput);
        } catch (RuntimeException failure) {
            return settle(intent, FlowInvocationStatus.FAILED, null, "START_FAILED", "child start was refused", null);
        }
        var launchedMutation = mutation(intent, FlowInvocationStatus.LAUNCHED, null, "", "", null);
        return store.mutateFlowInvocation(intent.tenantId(), launchedMutation).thenApply(launched -> {
            started.completion().whenComplete((result, failure) ->
                    settleCompletion(launched, contract, result, failure));
            scheduleDeadline(launched);
            return launched;
        });
    }

    private void settleCompletion(FlowInvocationRecord launched, FlowGraphContract contract,
                                  GraphExecutionResult result, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof DurableHumanTaskSuspension
                || cause instanceof ai.ravenroot.core.security.nodepackage.DurableToolApprovalSuspension
                || cause instanceof ai.ravenroot.core.runner.RunnerJobSuspension) {
            // The child is durably parked. Its recovery continuation records the eventual terminal
            // result under the same traversal id; the reconciliation loop observes that record and
            // settles this relation. Treating the suspension signal as a failure would permanently
            // discard a child that is deliberately waiting for external work.
            return;
        }
        if (failure != null || result == null) {
            reportSettlement(settleLatest(launched.tenantId(), launched.handle(), FlowInvocationStatus.FAILED, null,
                    "CHILD_FAILED", "child execution failed"), launched);
            return;
        }
        try {
            Object output = contract.output(result.payload());
            byte[] encoded = PayloadJson.writeJava(output, PayloadLimits.DEFAULTS);
            reportSettlement(settleLatest(launched.tenantId(), launched.handle(),
                    FlowInvocationStatus.COMPLETED, encoded, "", ""), launched);
        } catch (RuntimeException invalid) {
            reportSettlement(settleLatest(launched.tenantId(), launched.handle(), FlowInvocationStatus.FAILED, null,
                    "OUTPUT_SCHEMA", "child output did not satisfy its call contract"), launched);
        }
    }

    private void scheduleDeadline(FlowInvocationRecord record) {
        if (record.terminal()) {
            cancelDeadline(record.tenantId(), record.handle());
            return;
        }
        DeadlineKey key = new DeadlineKey(record.tenantId(), record.handle());
        long delay = Math.max(0, Duration.between(clock.instant(), record.deadlineAt()).toMillis());
        scheduledDeadlines.computeIfAbsent(key, ignored -> deadlines.schedule(() -> {
            scheduledDeadlines.remove(key);
            expireDeadline(key);
        }, delay, TimeUnit.MILLISECONDS));
    }

    private void expireDeadline(DeadlineKey key) {
        store.loadFlowInvocation(key.tenantId(), key.handle()).thenComposeAsync(optional -> {
            FlowInvocationRecord latest = optional.orElse(null);
            if (latest == null || latest.terminal()) return CompletableFuture.completedFuture(null);
            if (latest.deadlineAt().isAfter(clock.instant())) {
                scheduleDeadline(latest);
                return CompletableFuture.completedFuture(null);
            }
            ExecutionLookup execution = application.executionResult(latest.tenantId(), latest.childTraversalId());
            if (execution instanceof ExecutionLookup.Found found && found.outcome().status().terminal()) {
                return reconcile(latest);
            }
            application.cancelTraversal(latest.tenantId(), latest.childTraversalId());
            return settleLatest(latest.tenantId(), latest.handle(), FlowInvocationStatus.DEADLINE_EXCEEDED, null,
                    "DEADLINE_EXCEEDED", "child flow deadline elapsed");
        }).whenComplete((ignored, failure) -> {
            if (failure != null) LOGGER.log(System.Logger.Level.ERROR,
                    "flow deadline reconciliation failed tenant={0} handle={1}: {2}",
                    key.tenantId(), key.handle(), unwrap(failure).toString());
        });
    }

    @Override
    public CompletionStage<FlowInvocationResult> await(NodeMessage caller, FlowHandle handle) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(handle, "handle");
        String tenant = caller.security().tenantId();
        return store.loadFlowInvocation(tenant, handle).thenComposeAsync(optional -> {
            FlowInvocationRecord record = optional.orElseThrow(() -> new IllegalArgumentException("unknown flow handle"));
            if (!record.callerProcessInstanceId().equals(caller.processInstanceId())) {
                throw new SecurityException("flow handle does not belong to this caller process");
            }
            // A fast child may settle before start-flow's result reaches await-flow (and call-flow
            // deliberately uses that same composition). There is no external work to park behind
            // once the relation is terminal; returning the durable projection also avoids creating
            // a continuation that would require a recovery sweep solely to observe an answer already
            // committed in the same store.
            if (record.terminal()) {
                return recordContinuationClaim(record, caller.invocationId()).thenApply(this::project);
            }
            Duration remaining = Duration.between(clock.instant(), record.deadlineAt());
            if (remaining.isZero() || remaining.isNegative()) remaining = Duration.ofMillis(1);
            var suspension = durableContinuations.suspendInternal(caller, continuationDefinition(remaining),
                    handle.value());
            if (suspension.code() != ai.ravenroot.core.humantask.HumanTaskResult.Code.CREATED
                    && suspension.code() != ai.ravenroot.core.humantask.HumanTaskResult.Code.ALREADY_APPLIED) {
                throw new IllegalStateException("flow continuation was already accepted: " + suspension.code());
            }
            // Completion may have committed between the relation read and the atomic caller park.
            // Re-read after the park so either ordering resolves the same durable task.
            return recordContinuationClaim(record, caller.invocationId()).thenComposeAsync(claimed -> {
                store.loadFlowInvocation(tenant, handle).thenAcceptAsync(latest ->
                        latest.filter(FlowInvocationRecord::terminal).ifPresent(this::settleContinuation));
                return CompletableFuture.failedFuture(new DurableHumanTaskSuspension(handle.value()));
            });
        });
    }

    @Override
    public CompletionStage<FlowInvocationResult> cancel(NodeMessage caller, FlowHandle handle, String reason) {
        Objects.requireNonNull(caller, "caller");
        return store.loadFlowInvocation(caller.security().tenantId(), handle).thenComposeAsync(optional -> {
            FlowInvocationRecord record = optional.orElseThrow(() -> new IllegalArgumentException("unknown flow handle"));
            if (!record.callerProcessInstanceId().equals(caller.processInstanceId())) {
                throw new SecurityException("flow handle does not belong to this caller process");
            }
            if (record.terminal()) return CompletableFuture.completedFuture(project(record));
            boolean stopped = application.cancelTraversal(record.tenantId(), record.childTraversalId());
            FlowInvocationStatus status = stopped ? FlowInvocationStatus.CANCELLED : FlowInvocationStatus.ORPHANED;
            String message = stopped ? "child flow cancelled" : "child cancellation could not be confirmed";
            return settle(record, status, null, status.name(), message, null).thenApply(this::project);
        });
    }

    @Override
    public CompletionStage<java.util.Optional<FlowInvocationObservation>> observe(
            String tenantId, FlowHandle handle) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        Objects.requireNonNull(handle, "handle");
        return store.loadFlowInvocation(tenantId, handle).thenApply(record -> record.map(this::observe));
    }

    private FlowInvocationObservation observe(FlowInvocationRecord record) {
        return new FlowInvocationObservation(record.handle(), record.callerProcessInstanceId(),
                record.callerTraversalId(), record.callerInvocationId(), record.childProcessInstanceId(),
                record.childTraversalId(), record.targetDeploymentId(), record.targetVersion(),
                record.targetDigest(), record.status(), record.continuationClaim(), record.failureCode(),
                record.createdAt(), record.updatedAt(), record.deadlineAt(), record.retainedUntil());
    }

    /** Reconciles durable unfinished relations for one configured tenant after restart. */
    public CompletionStage<Void> recoverTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        recoveryTenants.add(tenantId);
        if (recoveryLoopStarted.compareAndSet(false, true)) {
            deadlines.scheduleWithFixedDelay(this::reconcileConfiguredTenants, 1, 1, TimeUnit.SECONDS);
        }
        return reconcileTenant(tenantId);
    }

    private CompletionStage<Void> reconcileTenant(String tenantId) {
        CompletionStage<Void> unfinished = store.unfinishedFlowInvocations(tenantId, RECOVERY_PAGE_SIZE)
                .thenComposeAsync(records -> CompletableFuture.allOf(records.stream()
                        .map(record -> reconcile(record).toCompletableFuture())
                        .toArray(CompletableFuture[]::new)));
        FlowHandle cursor = retainedCursors.get(tenantId);
        CompletionStage<Void> continuations = store.retainedFlowInvocationsAfter(tenantId,
                        java.util.Optional.ofNullable(cursor), RECOVERY_PAGE_SIZE)
                .thenAcceptAsync(records -> {
                    records.stream().filter(FlowInvocationRecord::terminal)
                            .filter(record -> record.continuationClaim() != null)
                            .forEach(this::settleContinuation);
                    if (records.size() < RECOVERY_PAGE_SIZE) retainedCursors.remove(tenantId);
                    else retainedCursors.put(tenantId, records.getLast().handle());
                });
        return CompletableFuture.allOf(unfinished.toCompletableFuture(), continuations.toCompletableFuture())
                .thenCompose(ignored -> store.purgeExpiredFlowInvocations(tenantId).thenApply(count -> null));
    }

    private void reconcileConfiguredTenants() {
        if (!reconciliationRunning.compareAndSet(false, true)) return;
        CompletableFuture.allOf(recoveryTenants.stream()
                        .map(tenantId -> reconcileTenant(tenantId).exceptionally(failure -> {
                            LOGGER.log(System.Logger.Level.ERROR,
                                    "flow reconciliation failed tenant={0}: {1}",
                                    tenantId, unwrap(failure).toString());
                            return null;
                        })
                                .toCompletableFuture())
                        .toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> reconciliationRunning.set(false));
    }

    private CompletionStage<Void> reconcile(FlowInvocationRecord record) {
        if (record.status() == FlowInvocationStatus.INTENT) {
            ExecutionLookup existing = application.executionResult(record.tenantId(), record.childTraversalId());
            if (!(existing instanceof ExecutionLookup.Unknown)) {
                return store.mutateFlowInvocation(record.tenantId(), mutation(record,
                                FlowInvocationStatus.LAUNCHED, null, "", "", record.continuationClaim()))
                        .thenComposeAsync(this::reconcile);
            }
            return deployments.version(record.tenantId(), record.targetDeploymentId(), record.targetVersion())
                    .thenCompose(optional -> {
                        GraphVersion version = optional.orElse(null);
                        if (version == null || !version.canonicalDigest().equals(record.targetDigest())) {
                            return settle(record, FlowInvocationStatus.AMBIGUOUS, null, "TARGET_UNAVAILABLE",
                                    "pinned target version is unavailable during recovery", null).thenApply(x -> null);
                        }
                        FlowGraphContract contract = FlowGraphContract.read(version.canonicalSnapshot());
                        Object input = PayloadJson.read(record.input(), PayloadLimits.DEFAULTS).toJava();
                        return launch(record, version, contract, input).thenApply(x -> null);
                    });
        }
        ExecutionLookup lookup = application.executionResult(record.tenantId(), record.childTraversalId());
        if (lookup instanceof ExecutionLookup.Found found && found.outcome().status().terminal()) {
            if (found.outcome().status() == ProcessInstanceStatus.COMPLETED) {
                return settleRecoveredCompletion(record, found.outcome().payload());
            }
            FlowInvocationStatus status = ExecutionTerminationReason.isCancellation(
                    found.outcome().terminationReason()) ? FlowInvocationStatus.CANCELLED : FlowInvocationStatus.FAILED;
            return settleLatest(record.tenantId(), record.handle(), status, null, status.name(),
                    "child terminal outcome recovered");
        } else if (!record.deadlineAt().isAfter(clock.instant())) {
            application.cancelTraversal(record.tenantId(), record.childTraversalId());
            return settleLatest(record.tenantId(), record.handle(), FlowInvocationStatus.DEADLINE_EXCEEDED, null,
                    "DEADLINE_EXCEEDED", "child flow deadline elapsed");
        } else {
            scheduleDeadline(record);
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> settleRecoveredCompletion(FlowInvocationRecord record, Object rawOutput) {
        return deployments.version(record.tenantId(), record.targetDeploymentId(), record.targetVersion())
                .thenCompose(optional -> {
                    GraphVersion version = optional.orElse(null);
                    if (version == null || !version.canonicalDigest().equals(record.targetDigest())) {
                        return settle(record, FlowInvocationStatus.AMBIGUOUS, null, "TARGET_UNAVAILABLE",
                                "pinned target version is unavailable during recovery", null).thenApply(x -> null);
                    }
                    try {
                        Object output = FlowGraphContract.read(version.canonicalSnapshot()).output(rawOutput);
                        byte[] encoded = PayloadJson.writeJava(output, PayloadLimits.DEFAULTS);
                        return settleLatest(record.tenantId(), record.handle(), FlowInvocationStatus.COMPLETED,
                                encoded, "", "");
                    } catch (RuntimeException invalid) {
                        return settle(record, FlowInvocationStatus.FAILED, null, "OUTPUT_SCHEMA",
                                "child output did not satisfy its call contract", null).thenApply(x -> null);
                    }
                });
    }

    private CompletionStage<FlowInvocationRecord> settle(FlowInvocationRecord current, FlowInvocationStatus status,
                                                          byte[] result, String code, String message, UUID claim) {
        return store.mutateFlowInvocation(current.tenantId(), mutation(current, status, result, code, message, claim))
                .whenComplete((settled, failure) -> {
                    if (failure == null && settled.terminal()) {
                        cancelDeadline(settled.tenantId(), settled.handle());
                        CompletableFuture.runAsync(() -> settleContinuation(settled));
                    }
                });
    }

    private CompletionStage<Void> settleLatest(String tenant, FlowHandle handle, FlowInvocationStatus status,
                                               byte[] result, String code, String message) {
        return settleLatest(tenant, handle, status, result, code, message, SETTLEMENT_CAS_RETRIES);
    }

    private CompletionStage<Void> settleLatest(String tenant, FlowHandle handle, FlowInvocationStatus status,
                                               byte[] result, String code, String message, int retries) {
        return store.loadFlowInvocation(tenant, handle).thenCompose(optional -> {
            FlowInvocationRecord latest = optional.orElse(null);
            if (latest == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("flow invocation disappeared before settlement"));
            }
            if (latest.terminal()) {
                cancelDeadline(tenant, handle);
                if (latest.status() == FlowInvocationStatus.COMPLETED
                        && status == FlowInvocationStatus.COMPLETED
                        && !java.util.Arrays.equals(latest.result(), result)) {
                    return CompletableFuture.failedFuture(new IllegalStateException(
                            "recovered child output disagrees with the durable invocation result"));
                }
                return CompletableFuture.completedFuture(latest);
            }
            return settle(latest, status, result, code, message, null).handle((settled, failure) -> {
                if (failure == null) return CompletableFuture.completedFuture(settled);
                Throwable cause = unwrap(failure);
                if (cause instanceof FlowInvocationConflictException && retries > 0) {
                    return settleLatest(tenant, handle, status, result, code, message, retries - 1)
                            .thenApply(ignored -> (FlowInvocationRecord) null);
                }
                return CompletableFuture.<FlowInvocationRecord>failedFuture(cause);
            }).thenCompose(stage -> stage);
        }).thenApply(ignored -> null);
    }

    private void reportSettlement(CompletionStage<Void> settlement, FlowInvocationRecord record) {
        settlement.whenComplete((ignored, failure) -> {
            if (failure != null) LOGGER.log(System.Logger.Level.ERROR,
                    "flow settlement failed tenant={0} handle={1}: {2}",
                    record.tenantId(), record.handle(), unwrap(failure).toString());
        });
    }

    private void cancelDeadline(String tenantId, FlowHandle handle) {
        ScheduledFuture<?> scheduled = scheduledDeadlines.remove(new DeadlineKey(tenantId, handle));
        if (scheduled != null) scheduled.cancel(false);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private FlowInvocationMutation mutation(FlowInvocationRecord current, FlowInvocationStatus status,
                                              byte[] result, String code, String message, UUID claim) {
        return new FlowInvocationMutation(current.handle(), current.revision(), status,
                current.childProcessInstanceId(), current.childTraversalId(), result, code, message,
                claim == null ? current.continuationClaim() : claim,
                clock.instant());
    }

    private HumanTaskDefinition continuationDefinition(Duration remaining) {
        int max = PayloadLimits.DEFAULTS.maxEncodedBytes();
        return new HumanTaskDefinition(
                new ai.ravenroot.api.persistence.HumanTaskMetadata("Flow continuation",
                        "Internal continuation for a version-pinned child flow."),
                new ai.ravenroot.api.persistence.HumanTaskResponseSchema("application/json",
                        HumanTaskService.INTERNAL_FLOW_SCHEMA, "1", ai.ravenroot.api.payload.PayloadKind.MAP, max),
                ai.ravenroot.api.persistence.HandlerAuthorization.none(), java.util.Optional.empty(), remaining,
                new ai.ravenroot.api.persistence.HumanTaskReentryMapping(
                        "completed", "failed", "deadline_exceeded", "cancelled"),
                ai.ravenroot.api.persistence.HumanTaskExecutionLimits.legacy(max));
    }

    private void settleContinuation(FlowInvocationRecord record) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("status", record.status().name());
        if (record.status() == FlowInvocationStatus.COMPLETED) {
            value.put("output", PayloadJson.read(record.result(), PayloadLimits.DEFAULTS).toJava());
        } else {
            value.put("code", record.failureCode());
            value.put("message", record.failureMessage());
        }
        byte[] raw = PayloadJson.writeJava(value, PayloadLimits.DEFAULTS);
        var envelope = ai.ravenroot.api.payload.PayloadEnvelope.of(HumanTaskService.INTERNAL_FLOW_SCHEMA, "1",
                PayloadJson.read(raw, PayloadLimits.DEFAULTS));
        byte[] encoded = envelope.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        durableContinuations.resolveInternal(record.tenantId(), record.handle().value(),
                OpaquePayload.of(encoded, "application/json"));
    }

    private FlowInvocationResult project(FlowInvocationRecord record) {
        if (record.status() == FlowInvocationStatus.COMPLETED) {
            return FlowInvocationResult.completed(PayloadJson.read(record.result(), PayloadLimits.DEFAULTS).toJava());
        }
        return FlowInvocationResult.failed(record.status(), record.failureCode(), record.failureMessage());
    }

    private void verifyRetry(FlowInvocationRecord existing, FlowTarget target) {
        if (!existing.targetDeploymentId().equals(target.deploymentId())
                || existing.targetVersion() != target.version()) {
            throw new IllegalStateException("caller invocation was already bound to a different flow intent");
        }
    }

    private CompletionStage<FlowInvocationRecord> recordContinuationClaim(
            FlowInvocationRecord record, UUID claimant) {
        if (record.continuationClaim() != null) {
            if (!record.continuationClaim().equals(claimant)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("flow invocation already has a continuation claimant"));
            }
            return CompletableFuture.completedFuture(record);
        }
        return store.mutateFlowInvocation(record.tenantId(), mutation(record, record.status(), record.result(),
                record.failureCode(), record.failureMessage(), claimant)).handle((claimed, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(claimed);
            return store.loadFlowInvocation(record.tenantId(), record.handle()).thenApply(latest -> {
                FlowInvocationRecord current = latest.orElseThrow();
                if (!claimant.equals(current.continuationClaim())) {
                    throw new IllegalStateException("flow invocation already has a continuation claimant");
                }
                return current;
            });
        }).thenCompose(stage -> stage);
    }

    private Duration boundedDeadline(Duration requested) {
        Duration value = requested == null ? policy.maximumDeadline() : requested;
        if (value.isZero() || value.isNegative() || value.compareTo(policy.maximumDeadline()) > 0) {
            throw new IllegalArgumentException("flow deadline is outside policy bounds");
        }
        return value;
    }

    private static ai.ravenroot.api.security.SecurityContext securityFor(FlowInvocationRecord record) {
        // Recovery never reconstructs authority from graph data. This identity is used only for an
        // already-authorized child whose immutable relation contains the caller's tenant; live starts
        // use the original NodeMessage identity before reaching this helper.
        return new ai.ravenroot.api.security.SecurityContext("flow:" + record.handle(), record.tenantId(),
                record.callerSubject(), record.callerPrincipalType(), record.callerIssuer());
    }

    private record DeadlineKey(String tenantId, FlowHandle handle) { }

    @Override public void close() {
        scheduledDeadlines.values().forEach(task -> task.cancel(false));
        scheduledDeadlines.clear();
        deadlines.shutdownNow();
    }
}
