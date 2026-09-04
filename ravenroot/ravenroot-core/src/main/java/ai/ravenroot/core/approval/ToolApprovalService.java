package ai.ravenroot.core.approval;

import ai.ravenroot.api.application.NodeAttemptStatus;
import ai.ravenroot.api.application.NodeInvocationStatus;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.HandlerPayloadSchema;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HandlerTransition;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.PendingWork;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.persistence.TimerSchedule;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.persistence.ToolApprovalStatus;
import ai.ravenroot.api.persistence.ToolApprovalTransition;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.runtime.ExecutionRecorder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Reference monitor for durable, exact, one-time tool approval.
 *
 * <p>The approval aggregate is authoritative. Its handler is only the durable wake-up projection
 * that creates a fresh traversal after settlement; it never grants an effect.</p>
 */
public final class ToolApprovalService {
    public static final String HANDLER_NAME = "tool-approval";
    public static final int MAX_WRITE_ATTEMPTS = 3;
    private static final String OUTCOME_CONTENT_TYPE = "application/vnd.ravenroot.tool-approval+json";
    private static final String EVENT_CONTENT_TYPE = "application/vnd.ravenroot.tool-approval-event";
    private static final PayloadLimits ARGUMENT_LIMITS =
            new PayloadLimits(ToolApprovalRegistration.MAX_ARGUMENT_BYTES, 32, 1024, 4096, 16 * 1024, 256);

    private final ExecutionStore store;
    private final Clock clock;
    private final ToolApprovalBudgetHooks budgetHooks;
    private final Map<ExecutionKey, ExecutionRecorder> liveRecorders = new ConcurrentHashMap<>();
    private volatile Set<String> recoverableTenants;

    public ToolApprovalService(ExecutionStore store, Clock clock) {
        this(store, clock, ToolApprovalBudgetHooks.none());
    }

    public ToolApprovalService(ExecutionStore store, Clock clock, ToolApprovalBudgetHooks budgetHooks) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.budgetHooks = Objects.requireNonNull(budgetHooks, "budgetHooks");
        if (!store.supports(StoreCapability.DURABLE_HANDLERS)
                || !store.supports(StoreCapability.TOOL_APPROVALS)
                || !store.supports(StoreCapability.EVENT_JOURNAL)) {
            throw new IllegalArgumentException("tool approval requires handlers, approvals, and journal support");
        }
    }

    /**
     * Binds one live, fenced execution recorder for managed suspension registration.
     * The returned scope must be closed when the in-memory run tears down.
     */
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

    /** Production fail-closed tenant allowlist; embedders retain the unrestricted additive default. */
    public void restrictRecoveryTenants(Set<String> tenantIds) {
        Set<String> snapshot = Set.copyOf(Objects.requireNonNull(tenantIds, "tenantIds"));
        if (snapshot.isEmpty() || snapshot.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("recovery tenant ids must be non-empty safe values");
        }
        recoverableTenants = snapshot;
    }

    /**
     * Commits the live suspension through the runner's own recorder and fence.
     * Called only by the managed tool authorization implementation.
     */
    public ToolApprovalResult suspend(NodeMessage message, UUID approvalId, UUID callId, String tool,
                                      byte[] canonicalArguments, String argumentsDigest,
                                      ToolApprovalSettings settings, int continuationVersion,
                                      byte[] continuation) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(settings, "settings");
        ExecutionKey key = new ExecutionKey(message.security().tenantId(), message.processInstanceId());
        Set<String> configured = recoverableTenants;
        if (configured != null && !configured.contains(key.tenantId())) {
            return new ToolApprovalResult(ToolApprovalResult.Code.UNAVAILABLE, null, null);
        }
        ExecutionRecorder recorder = liveRecorders.get(key);
        if (recorder == null) {
            return new ToolApprovalResult(ToolApprovalResult.Code.UNAVAILABLE, null, null);
        }
        var request = new ToolApprovalRegistration(approvalId, message.traversalId(),
                message.invocationId(), message.attemptId(), callId, message.nodeId(), tool,
                canonicalArguments, argumentsDigest, message.security(), recorder.graphVersionPin(),
                settings.policyVersion(), clock.instant().plus(settings.timeToLive()),
                settings.approverRequirements(), settings.requesterMayApprove(), continuationVersion,
                continuation, ToolApprovalRegistration.digest(continuation));
        StoredProcessInstance stored = load(key);
        OpaquePayload identity = approvalPayload(approvalId);
        recorder.suspendForToolApproval(request,
                new HandlerRegistration(approvalId, HANDLER_NAME, request.traversalId(),
                        request.invocationId(), approvalId.toString(), approvalId.toString(),
                        new HandlerPayloadSchema(OUTCOME_CONTENT_TYPE,
                                "ravenroot://tool-approval/v1", 256), request.approverRequirements()),
                new TimerSchedule(expiryTimerId(approvalId), request.expiresAt(), request.traversalId(),
                        request.invocationId(), identity),
                event(key, stored, request, "TOOL_APPROVAL_REQUESTED",
                        message.security().requestId(), request.traversalId()),
                budgetHooks.hold(key, request).orElse(null));
        return new ToolApprovalResult(ToolApprovalResult.Code.CREATED,
                await(store.loadToolApproval(key, approvalId)).orElseThrow(), null);
    }

    /** Atomically suspends the exact running invocation and records its approval, timer and handler. */
    ToolApprovalResult request(ExecutionKey key, ToolApprovalRegistration request,
                               String correlationId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(request, "request");
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            var existing = await(store.loadToolApproval(key, request.approvalId())).orElse(null);
            if (existing != null) {
                if (!existing.request().sameRequest(request)) {
                    auditOnly(existing, "TOOL_APPROVAL_DUPLICATE_SCOPE_REFUSED", correlationId);
                    return new ToolApprovalResult(ToolApprovalResult.Code.SCOPE_MISMATCH, existing, null);
                }
                auditOnly(existing, "TOOL_APPROVAL_DUPLICATE_REQUEST", correlationId);
                return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_APPLIED, existing,
                        resumeTraversalOf(key, request.approvalId()));
            }
            StoredProcessInstance stored = load(key);
            OpaquePayload identity = approvalPayload(request.approvalId());
            var batch = ExecutionBatch.to(key)
                    .expecting(RevisionExpectation.exactly(stored.revision()))
                    .apply(new ExecutionTransition.AttemptTransitioned(request.traversalId(),
                            request.invocationId(), request.attemptId(), NodeAttemptStatus.WAITING))
                    .apply(new ExecutionTransition.InvocationTransitioned(request.traversalId(),
                            request.invocationId(), NodeInvocationStatus.WAITING))
                    .apply(new ExecutionTransition.TraversalTransitioned(request.traversalId(),
                            TraversalStatus.WAITING))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.WAITING))
                    .registerToolApproval(request)
                    .registerHandler(new HandlerRegistration(request.approvalId(), HANDLER_NAME,
                            request.traversalId(), request.invocationId(), request.approvalId().toString(),
                            request.approvalId().toString(),
                            new HandlerPayloadSchema(OUTCOME_CONTENT_TYPE,
                                    "ravenroot://tool-approval/v1", 256), request.approverRequirements()))
                    .scheduleTimer(new TimerSchedule(expiryTimerId(request.approvalId()), request.expiresAt(),
                            request.traversalId(), request.invocationId(), identity))
                    .publish(event(key, stored, request, "TOOL_APPROVAL_REQUESTED", correlationId,
                            request.traversalId()))
                    .build();
            try {
                await(store.apply(batch));
                return new ToolApprovalResult(ToolApprovalResult.Code.CREATED,
                        await(store.loadToolApproval(key, request.approvalId())).orElseThrow(), null);
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attempt < MAX_WRITE_ATTEMPTS) continue;
                throw conflict;
            }
        }
        throw new IllegalStateException("tool approval request retry budget exhausted");
    }

    public ToolApprovalResult approve(RequestContext context, UUID processInstanceId, UUID approvalId) {
        return settle(context, processInstanceId, approvalId, ToolApprovalStatus.APPROVED);
    }

    public ToolApprovalResult deny(RequestContext context, UUID processInstanceId, UUID approvalId) {
        return settle(context, processInstanceId, approvalId, ToolApprovalStatus.DENIED);
    }

    public ToolApprovalResult cancel(RequestContext context, UUID processInstanceId, UUID approvalId) {
        return settle(context, processInstanceId, approvalId, ToolApprovalStatus.CANCELLED);
    }

    /** Store-clock timeout; safe to repeat after another worker won the race. */
    public ToolApprovalResult expire(ExecutionKey key, UUID approvalId, String correlationId) {
        DurableToolApproval approval = await(store.loadToolApproval(key, approvalId)).orElse(null);
        if (approval == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
        try {
            return approval.status() == ToolApprovalStatus.APPROVED
                    ? commitSimple(approval, new ToolApprovalTransition.Expired(approvalId),
                            "TOOL_APPROVAL_EXPIRED", correlationId)
                    : commitSettlement(approval, ToolApprovalStatus.EXPIRED, "", correlationId);
        } catch (ExecutionStoreException notDue) {
            if (notDue.failure() instanceof ExecutionStoreFailure.ToolApprovalNotResolvable refusal
                    && refusal.requested() == ToolApprovalStatus.EXPIRED) {
                return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_SETTLED,
                        await(store.loadToolApproval(key, approvalId)).orElse(approval), null);
            }
            throw notDue;
        }
    }

    /**
     * Atomically consumes an approved grant after matching every scope component and rechecking the
     * current policy. The returned bytes are the stored bytes, never caller-provided replacements.
     */
    public ToolApprovalResult redeem(NodeMessage message, UUID approvalId, String tool,
                                     byte[] canonicalArguments, String digest, ToolPolicy currentPolicy) {
        Objects.requireNonNull(message, "message");
        ExecutionKey key = new ExecutionKey(message.security().tenantId(), message.processInstanceId());
        DurableToolApproval approval = await(store.loadToolApproval(key, approvalId)).orElse(null);
        if (approval == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
        if (!scopeMatches(approval.request(), message, tool, canonicalArguments, digest)) {
            auditOnly(approval, "TOOL_APPROVAL_SCOPE_REFUSED", message.security().requestId());
            return new ToolApprovalResult(ToolApprovalResult.Code.SCOPE_MISMATCH, approval, null);
        }
        if (approval.status() == ToolApprovalStatus.CONSUMED
                || approval.status().terminal()) {
            auditOnly(approval, "TOOL_APPROVAL_REDEMPTION_REPLAY", message.security().requestId());
            return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_SETTLED, approval, null);
        }
        if (approval.status() != ToolApprovalStatus.APPROVED) {
            auditOnly(approval, "TOOL_APPROVAL_REDEMPTION_REFUSED", message.security().requestId());
            return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_SETTLED, approval, null);
        }
        if (!currentlyAllowed(approval, message, currentPolicy)) {
            ToolApprovalResult cancelled = commitSimple(approval,
                    new ToolApprovalTransition.Cancelled(approvalId,
                            "ravenroot|SYSTEM|tool-policy"),
                    "TOOL_APPROVAL_POLICY_REVOKED", message.security().requestId());
            return new ToolApprovalResult(ToolApprovalResult.Code.POLICY_REVOKED,
                    cancelled.approval(), cancelled.resumeTraversalId());
        }
        try {
            return commitSimple(approval, new ToolApprovalTransition.Consumed(approvalId),
                    "TOOL_APPROVAL_CONSUMED", message.security().requestId());
        } catch (ExecutionStoreException expired) {
            if (expired.failure() instanceof ExecutionStoreFailure.ToolApprovalNotResolvable refusal
                    && refusal.requested() == ToolApprovalStatus.EXPIRED) {
                return expire(key, approvalId, message.security().requestId());
            }
            throw expired;
        }
    }

    /**
     * Recovery-only redemption using the immutable original scope retained by the aggregate.
     * A fresh re-entry traversal must not be compared with the original traversal identifier.
     */
    public ToolApprovalResult redeemStored(DurableToolApproval approval, ToolPolicy currentPolicy,
                                           String correlationId) {
        Objects.requireNonNull(approval, "approval");
        DurableToolApproval current = await(store.loadToolApproval(approval.key(),
                approval.request().approvalId())).orElse(null);
        if (current == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
        if (current.status() != ToolApprovalStatus.APPROVED) {
            auditOnly(current, "TOOL_APPROVAL_REDEMPTION_REPLAY", correlationId);
            return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_SETTLED, current, null);
        }
        if (!currentlyAllowed(current, currentPolicy)) {
            ToolApprovalResult cancelled = commitSimple(current,
                    new ToolApprovalTransition.Cancelled(current.request().approvalId(),
                            "ravenroot|SYSTEM|tool-policy"),
                    "TOOL_APPROVAL_POLICY_REVOKED", correlationId);
            return new ToolApprovalResult(ToolApprovalResult.Code.POLICY_REVOKED,
                    cancelled.approval(), cancelled.resumeTraversalId());
        }
        try {
            return commitSimple(current, new ToolApprovalTransition.Consumed(current.request().approvalId()),
                    "TOOL_APPROVAL_CONSUMED", correlationId);
        } catch (ExecutionStoreException expired) {
            if (expired.failure() instanceof ExecutionStoreFailure.ToolApprovalNotResolvable refusal
                    && refusal.requested() == ToolApprovalStatus.EXPIRED) {
                return expire(current.key(), current.request().approvalId(), correlationId);
            }
            throw expired;
        }
    }

    /** Recovery redemption committed under the handler claim's exact instance fence. */
    public ToolApprovalResult redeemStoredFenced(DurableToolApproval approval, ToolPolicy currentPolicy,
                                                 String correlationId, long fencingToken) {
        Objects.requireNonNull(approval, "approval");
        DurableToolApproval current = await(store.loadToolApproval(approval.key(),
                approval.request().approvalId())).orElse(null);
        if (current == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
        if (current.status() != ToolApprovalStatus.APPROVED) {
            auditOnly(current, "TOOL_APPROVAL_REDEMPTION_REPLAY", correlationId);
            return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_SETTLED, current, null);
        }
        if (!currentlyAllowed(current, currentPolicy)) {
            return commitSimple(current, new ToolApprovalTransition.Cancelled(current.request().approvalId(),
                    "ravenroot|SYSTEM|tool-policy"), "TOOL_APPROVAL_POLICY_REVOKED", correlationId,
                    fencingToken);
        }
        try {
            return commitSimple(current, new ToolApprovalTransition.Consumed(current.request().approvalId()),
                    "TOOL_APPROVAL_CONSUMED", correlationId, fencingToken);
        } catch (ExecutionStoreException expired) {
            if (expired.failure() instanceof ExecutionStoreFailure.ToolApprovalNotResolvable refusal
                    && refusal.requested() == ToolApprovalStatus.EXPIRED) {
                return commitSimple(current,
                        new ToolApprovalTransition.Expired(current.request().approvalId()),
                        "TOOL_APPROVAL_EXPIRED", correlationId, fencingToken);
            }
            throw expired;
        }
    }

    /** Expires only the approval timer whose full stored scope matches this claimed timer. */
    public boolean expireClaimedTimer(PendingWork.TimerDue timer, String correlationId) {
        DurableToolApproval approval = await(store.toolApprovals(timer.key())).stream()
                .filter(candidate -> expiryTimerId(candidate.request().approvalId()).equals(timer.workItemId()))
                .filter(candidate -> candidate.request().traversalId().equals(timer.traversalId()))
                .filter(candidate -> candidate.request().invocationId().equals(timer.invocationId()))
                .findFirst().orElse(null);
        if (approval == null) return false;
        try {
            if (approval.status() == ToolApprovalStatus.APPROVED) {
                commitSimple(approval, new ToolApprovalTransition.Expired(approval.request().approvalId()),
                        "TOOL_APPROVAL_EXPIRED", correlationId, timer.fencingToken());
            } else if (approval.status() == ToolApprovalStatus.PENDING) {
                commitSettlement(approval, ToolApprovalStatus.EXPIRED, "", correlationId,
                        timer.fencingToken());
            } else {
                auditOnly(approval, "TOOL_APPROVAL_EXPIRY_REPLAY", correlationId);
            }
            return true;
        } catch (ExecutionStoreException notDue) {
            if (notDue.failure() instanceof ExecutionStoreFailure.ToolApprovalNotResolvable) return false;
            throw notDue;
        }
    }

    /** Whether a claimed timer is the exact stored expiry signal of an approval. */
    public boolean ownsTimer(PendingWork.TimerDue timer) {
        return await(store.toolApprovals(timer.key())).stream().anyMatch(candidate ->
                expiryTimerId(candidate.request().approvalId()).equals(timer.workItemId())
                        && candidate.request().traversalId().equals(timer.traversalId())
                        && candidate.request().invocationId().equals(timer.invocationId()));
    }

    public ToolApprovalResult complete(ExecutionKey key, UUID approvalId, boolean succeeded,
                                       String correlationId) {
        DurableToolApproval approval = await(store.loadToolApproval(key, approvalId)).orElse(null);
        if (approval == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
        ToolApprovalTransition transition = succeeded
                ? new ToolApprovalTransition.Succeeded(approvalId)
                : new ToolApprovalTransition.Failed(approvalId);
        return commitSimple(approval, transition,
                succeeded ? "TOOL_APPROVAL_EFFECT_SUCCEEDED" : "TOOL_APPROVAL_EFFECT_FAILED", correlationId);
    }

    /** Commits a recovered effect outcome on the same recorder revision and fence as re-entry. */
    void completeFenced(ExecutionRecorder recorder, DurableToolApproval approval, boolean succeeded,
                        String correlationId) {
        Objects.requireNonNull(recorder, "recorder");
        Objects.requireNonNull(approval, "approval");
        if (!approval.key().tenantId().equals(recorder.tenantId())
                || !approval.key().processInstanceId().equals(recorder.processInstanceId())
                || approval.status() != ToolApprovalStatus.CONSUMED) {
            throw new IllegalArgumentException("approval outcome does not match the claimed recorder");
        }
        String eventType = succeeded
                ? "TOOL_APPROVAL_EFFECT_SUCCEEDED" : "TOOL_APPROVAL_EFFECT_FAILED";
        StoredProcessInstance stored = load(approval.key());
        recorder.completeToolApproval(approval.request().approvalId(), succeeded,
                event(approval.key(), stored, approval.request(), eventType, correlationId,
                        approval.request().traversalId()), budgetHooks.settle(approval).orElse(null));
    }

    /** Marks every crash-left consumed grant indeterminate; none is returned for automatic effect replay. */
    public int markConsumedIndeterminate(ExecutionKey key, String correlationId) {
        int changed = 0;
        for (DurableToolApproval approval : await(store.toolApprovals(key))) {
            if (approval.status() == ToolApprovalStatus.CONSUMED) {
                ToolApprovalResult result = commitSimple(approval,
                        new ToolApprovalTransition.Indeterminate(approval.request().approvalId()),
                        "TOOL_APPROVAL_EFFECT_INDETERMINATE", correlationId);
                if (result.code() == ToolApprovalResult.Code.INDETERMINATE) changed++;
            }
        }
        return changed;
    }

    private ToolApprovalResult settle(RequestContext context, UUID processInstanceId, UUID approvalId,
                                      ToolApprovalStatus target) {
        Objects.requireNonNull(context, "context");
        ExecutionKey key = new ExecutionKey(context.tenantId(), processInstanceId);
        DurableToolApproval approval = await(store.loadToolApproval(key, approvalId)).orElse(null);
        if (approval == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
        String actor = SecurityContext.of(context).qualifiedIdentity();
        boolean requesterCancellation = target == ToolApprovalStatus.CANCELLED
                && actor.equals(approval.request().requester().qualifiedIdentity());
        Set<String> roles = context.roles().stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
        if (!requesterCancellation
                && !approval.request().approverRequirements().satisfiedBy(roles, context.scopes())) {
            auditOnly(approval, "TOOL_APPROVAL_UNAUTHORIZED", context.requestId());
            return new ToolApprovalResult(ToolApprovalResult.Code.UNAUTHORIZED, approval, null);
        }
        if (target == ToolApprovalStatus.APPROVED && !approval.request().requesterMayApprove()
                && actor.equals(approval.request().requester().qualifiedIdentity())) {
            auditOnly(approval, "TOOL_APPROVAL_SEPARATION_REFUSED", context.requestId());
            return new ToolApprovalResult(ToolApprovalResult.Code.UNAUTHORIZED, approval, null);
        }
        if (target == ToolApprovalStatus.CANCELLED
                && approval.status() == ToolApprovalStatus.APPROVED) {
            ToolApprovalResult cancelled = commitSimple(approval,
                    new ToolApprovalTransition.Cancelled(approvalId, actor),
                    "TOOL_APPROVAL_CANCELLED", context.requestId());
            return new ToolApprovalResult(cancelled.code(), cancelled.approval(),
                    resumeTraversalOf(key, approvalId));
        }
        return commitSettlement(approval, target, actor, context.requestId());
    }

    private ToolApprovalResult commitSettlement(DurableToolApproval original, ToolApprovalStatus target,
                                                String actor, String correlationId) {
        return commitSettlement(original, target, actor, correlationId, null);
    }

    private ToolApprovalResult commitSettlement(DurableToolApproval original, ToolApprovalStatus target,
                                                String actor, String correlationId, Long fencingToken) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            DurableToolApproval approval = await(store.loadToolApproval(original.key(),
                    original.request().approvalId())).orElse(null);
            if (approval == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
            ToolApprovalResult currentOutcome = settleCurrentState(
                    approval, target, actor, correlationId, fencingToken);
            if (currentOutcome != null) return currentOutcome;
            StoredProcessInstance stored = load(approval.key());
            DurableToolApproval confirmed = await(store.loadToolApproval(approval.key(),
                    approval.request().approvalId())).orElse(null);
            if (confirmed == null) {
                return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
            }
            ToolApprovalResult refreshedOutcome = settleCurrentState(
                    confirmed, target, actor, correlationId, fencingToken);
            if (refreshedOutcome != null) return refreshedOutcome;
            UUID resumeTraversalId = UUID.randomUUID();
            ToolApprovalTransition approvalTransition = switch (target) {
                case APPROVED -> new ToolApprovalTransition.Approved(approval.request().approvalId(), actor);
                case DENIED -> new ToolApprovalTransition.Denied(approval.request().approvalId(), actor);
                case EXPIRED -> new ToolApprovalTransition.Expired(approval.request().approvalId());
                case CANCELLED -> new ToolApprovalTransition.Cancelled(approval.request().approvalId(), actor);
                default -> throw new IllegalArgumentException("not a settlement status: " + target);
            };
            HandlerTransition handlerTransition = switch (target) {
                case APPROVED -> new HandlerTransition.Resolved(approval.request().approvalId(), actor,
                        resumeTraversalId, approvalPayload(approval.request().approvalId()));
                case DENIED, CANCELLED -> new HandlerTransition.Denied(approval.request().approvalId(), actor,
                        resumeTraversalId, approvalPayload(approval.request().approvalId()));
                case EXPIRED -> new HandlerTransition.Expired(approval.request().approvalId(), resumeTraversalId);
                default -> throw new IllegalArgumentException("not a settlement status: " + target);
            };
            var builder = ExecutionBatch.to(approval.key())
                    .expecting(RevisionExpectation.exactly(stored.revision()));
            if (fencingToken != null) builder.fencedBy(fencingToken);
            var batch = builder
                    .apply(new ExecutionTransition.AttemptTransitioned(approval.request().traversalId(),
                            approval.request().invocationId(), approval.request().attemptId(),
                            NodeAttemptStatus.RUNNING))
                    .apply(new ExecutionTransition.AttemptTransitioned(approval.request().traversalId(),
                            approval.request().invocationId(), approval.request().attemptId(),
                            NodeAttemptStatus.COMPLETED))
                    .apply(new ExecutionTransition.InvocationTransitioned(approval.request().traversalId(),
                            approval.request().invocationId(), NodeInvocationStatus.RUNNING))
                    .apply(new ExecutionTransition.InvocationTransitioned(approval.request().traversalId(),
                            approval.request().invocationId(), NodeInvocationStatus.COMPLETED))
                    .apply(new ExecutionTransition.TraversalTransitioned(approval.request().traversalId(),
                            TraversalStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalTransitioned(approval.request().traversalId(),
                            TraversalStatus.COMPLETED))
                    .apply(new ExecutionTransition.ProcessTransitioned(ProcessInstanceStatus.RUNNING))
                    .apply(new ExecutionTransition.TraversalAdded(new Traversal(resumeTraversalId,
                            approval.request().nodeId(), TraversalStatus.ACCEPTED, Map.of())))
                    .applyToolApproval(approvalTransition)
                    .applyHandler(handlerTransition)
                    .cancelTimer(expiryTimerId(approval.request().approvalId()))
                    .publish(event(approval.key(), stored, approval.request(),
                            "TOOL_APPROVAL_" + target.name(), correlationId, resumeTraversalId));
            if (target == ToolApprovalStatus.DENIED || target == ToolApprovalStatus.EXPIRED
                    || target == ToolApprovalStatus.CANCELLED) {
                budgetHooks.release(approval).ifPresent(batch::applyAgentBudget);
            }
            try {
                await(store.apply(batch.build()));
                ToolApprovalResult.Code code = ToolApprovalResult.Code.valueOf(target.name());
                return new ToolApprovalResult(code,
                        await(store.loadToolApproval(approval.key(), approval.request().approvalId())).orElseThrow(),
                        resumeTraversalId);
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attempt < MAX_WRITE_ATTEMPTS) continue;
                if (conflict.failure() instanceof ExecutionStoreFailure.InvalidRequest) {
                    DurableToolApproval winner = await(store.loadToolApproval(approval.key(),
                            approval.request().approvalId())).orElse(null);
                    if (winner != null) {
                        ToolApprovalResult winnerOutcome = settleCurrentState(
                                winner, target, actor, correlationId, fencingToken);
                        if (winnerOutcome != null) return winnerOutcome;
                    }
                    // A still-PENDING approval proves this was not a concurrent settlement race.
                    throw conflict;
                }
                if (conflict.failure() instanceof ExecutionStoreFailure.ToolApprovalNotResolvable refusal) {
                    if (refusal.requested() == ToolApprovalStatus.EXPIRED
                            && target != ToolApprovalStatus.EXPIRED
                            && attempt < MAX_WRITE_ATTEMPTS) {
                        target = ToolApprovalStatus.EXPIRED;
                        actor = "";
                        continue;
                    }
                    if (attempt < MAX_WRITE_ATTEMPTS) continue;
                }
                throw conflict;
            }
        }
        throw new IllegalStateException("tool approval settlement retry budget exhausted");
    }

    /**
     * Dispatches a freshly read state that must not repeat the one-time PENDING lifecycle batch.
     * Returns {@code null} only when that batch is still the valid next operation.
     */
    private ToolApprovalResult settleCurrentState(DurableToolApproval approval,
                                                  ToolApprovalStatus target, String actor,
                                                  String correlationId, Long fencingToken) {
        if (approval.status() == target) {
            auditOnly(approval, "TOOL_APPROVAL_DUPLICATE_DECISION", correlationId);
            return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_APPLIED, approval,
                    resumeTraversalOf(approval.key(), approval.request().approvalId()));
        }
        if (approval.status() == ToolApprovalStatus.PENDING) return null;
        if (approval.status() == ToolApprovalStatus.APPROVED
                && (target == ToolApprovalStatus.CANCELLED || target == ToolApprovalStatus.EXPIRED)) {
            ToolApprovalTransition transition = target == ToolApprovalStatus.CANCELLED
                    ? new ToolApprovalTransition.Cancelled(approval.request().approvalId(), actor)
                    : new ToolApprovalTransition.Expired(approval.request().approvalId());
            String eventType = target == ToolApprovalStatus.CANCELLED
                    ? "TOOL_APPROVAL_CANCELLED" : "TOOL_APPROVAL_EXPIRED";
            ToolApprovalResult transitioned = commitSimple(
                    approval, transition, eventType, correlationId, fencingToken);
            return new ToolApprovalResult(transitioned.code(), transitioned.approval(),
                    resumeTraversalOf(approval.key(), approval.request().approvalId()));
        }
        auditOnly(approval, "TOOL_APPROVAL_CONFLICTING_DECISION", correlationId);
        return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_SETTLED, approval,
                resumeTraversalOf(approval.key(), approval.request().approvalId()));
    }

    private ToolApprovalResult commitSimple(DurableToolApproval original, ToolApprovalTransition transition,
                                            String eventType, String correlationId) {
        return commitSimple(original, transition, eventType, correlationId, null);
    }

    private ToolApprovalResult commitSimple(DurableToolApproval original, ToolApprovalTransition transition,
                                            String eventType, String correlationId, Long fencingToken) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            DurableToolApproval approval = await(store.loadToolApproval(original.key(),
                    original.request().approvalId())).orElse(null);
            if (approval == null) return new ToolApprovalResult(ToolApprovalResult.Code.NOT_FOUND, null, null);
            if (approval.alreadyApplied(transition)) {
                auditOnly(approval, "TOOL_APPROVAL_DUPLICATE_TRANSITION", correlationId);
                return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_APPLIED, approval, null);
            }
            if (!approval.status().canTransitionTo(transition.next())) {
                auditOnly(approval, "TOOL_APPROVAL_REPLAY_REFUSED", correlationId);
                return new ToolApprovalResult(ToolApprovalResult.Code.ALREADY_SETTLED, approval, null);
            }
            StoredProcessInstance stored = load(approval.key());
            var builder = ExecutionBatch.to(approval.key())
                    .expecting(RevisionExpectation.exactly(stored.revision()));
            if (fencingToken != null) builder.fencedBy(fencingToken);
            var batch = builder
                    .applyToolApproval(transition)
                    .publish(event(approval.key(), stored, approval.request(), eventType, correlationId,
                            approval.request().traversalId()));
            budgetOperations(approval, transition).forEach(batch::applyAgentBudget);
            try {
                await(store.apply(batch.build()));
                return new ToolApprovalResult(ToolApprovalResult.Code.valueOf(transition.next().name()),
                        await(store.loadToolApproval(approval.key(), approval.request().approvalId())).orElseThrow(),
                        null);
            } catch (ExecutionStoreException conflict) {
                if (conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                        && attempt < MAX_WRITE_ATTEMPTS) continue;
                if (conflict.failure() instanceof ExecutionStoreFailure.ToolApprovalNotResolvable
                        && attempt < MAX_WRITE_ATTEMPTS) continue;
                throw conflict;
            }
        }
        throw new IllegalStateException("tool approval transition retry budget exhausted");
    }

    private java.util.List<ai.ravenroot.api.persistence.AgentBudgetOperation> budgetOperations(
            DurableToolApproval approval, ToolApprovalTransition transition) {
        return switch (transition.next()) {
            case CONSUMED -> budgetHooks.dispatch(approval);
            case DENIED, EXPIRED, CANCELLED -> budgetHooks.release(approval).stream().toList();
            case SUCCEEDED, FAILED -> budgetHooks.settle(approval).stream().toList();
            case INDETERMINATE -> budgetHooks.indeterminate(approval).stream().toList();
            default -> java.util.List.of();
        };
    }

    private void auditOnly(DurableToolApproval approval, String eventType, String correlationId) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            StoredProcessInstance stored = load(approval.key());
            try {
                await(store.apply(ExecutionBatch.to(approval.key())
                        .expecting(RevisionExpectation.exactly(stored.revision()))
                        .publish(event(approval.key(), stored, approval.request(), eventType, correlationId,
                                approval.request().traversalId()))
                        .build()));
                return;
            } catch (ExecutionStoreException conflict) {
                if (!(conflict.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict)
                        || attempt == MAX_WRITE_ATTEMPTS) throw conflict;
            }
        }
    }

    private boolean currentlyAllowed(DurableToolApproval approval, NodeMessage message, ToolPolicy policy) {
        try {
            PayloadValue parsed = PayloadJson.read(approval.request().canonicalArguments(), ARGUMENT_LIMITS);
            if (!(parsed instanceof PayloadValue.MapValue object)) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) object.toJava();
            ToolDecision decision = Objects.requireNonNull(policy, "currentPolicy").evaluate(
                    new ToolInvocation(message.security(), message.processInstanceId(), message.nodeId(),
                            approval.request().tool(), arguments));
            return decision != null && decision.disposition() != ToolDecision.Disposition.DENY;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean currentlyAllowed(DurableToolApproval approval, ToolPolicy policy) {
        try {
            PayloadValue parsed = PayloadJson.read(approval.request().canonicalArguments(), ARGUMENT_LIMITS);
            if (!(parsed instanceof PayloadValue.MapValue object)) return false;
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) object.toJava();
            ToolDecision decision = Objects.requireNonNull(policy, "currentPolicy").evaluate(
                    new ToolInvocation(approval.request().requester(),
                            approval.key().processInstanceId(), approval.request().nodeId(),
                            approval.request().tool(), arguments));
            return decision != null && decision.disposition() != ToolDecision.Disposition.DENY;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean scopeMatches(ToolApprovalRegistration request, NodeMessage message, String tool,
                                        byte[] canonicalArguments, String digest) {
        return request.traversalId().equals(message.traversalId())
                && request.invocationId().equals(message.invocationId())
                && request.attemptId().equals(message.attemptId())
                && request.nodeId().equals(message.nodeId())
                && request.tool().equals(tool)
                && request.argumentsDigest().equals(digest)
                && Arrays.equals(request.canonicalArguments(), canonicalArguments)
                && request.requester().equals(message.security());
    }

    private EventEnvelope event(ExecutionKey key, StoredProcessInstance stored,
                                ToolApprovalRegistration request, String eventType,
                                String correlationId, UUID traversalId) {
        boolean requested = "TOOL_APPROVAL_REQUESTED".equals(eventType);
        return EventEnvelope.of(requested ? request.callId() : UUID.randomUUID(), key.tenantId(), eventType,
                key.processInstanceId(), traversalId, request.invocationId(), request.attemptId(),
                requested ? null : request.callId(), correlationId,
                stored.graphVersionPin().reference(), clock.instant(),
                OpaquePayload.of(request.approvalId().toString().getBytes(StandardCharsets.UTF_8),
                        EVENT_CONTENT_TYPE));
    }

    private UUID resumeTraversalOf(ExecutionKey key, UUID approvalId) {
        DurableHandler handler = await(store.loadHandler(key, approvalId)).orElse(null);
        return handler == null ? null : handler.resumeTraversalId();
    }

    private StoredProcessInstance load(ExecutionKey key) {
        return await(store.load(key));
    }

    private static OpaquePayload approvalPayload(UUID approvalId) {
        String json = "{\"approvalId\":\"" + approvalId + "\"}";
        return OpaquePayload.of(json.getBytes(StandardCharsets.UTF_8), OUTCOME_CONTENT_TYPE);
    }

    /** Stable timer identity derived without exposing or persisting a bearer secret. */
    public static UUID expiryTimerId(UUID approvalId) {
        return UUID.nameUUIDFromBytes(("tool-approval-expiry:" + approvalId)
                .getBytes(StandardCharsets.UTF_8));
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
