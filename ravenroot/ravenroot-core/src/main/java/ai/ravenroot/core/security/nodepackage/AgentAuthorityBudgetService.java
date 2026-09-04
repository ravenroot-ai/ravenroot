package ai.ravenroot.core.security.nodepackage;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.ToolCallContinuationInput;
import ai.ravenroot.api.node.service.AgentModelReservation;
import ai.ravenroot.api.node.service.AgentChildResourceRequest;
import ai.ravenroot.api.node.service.AgentResourceRequest;
import ai.ravenroot.api.node.service.AgentResourceService;
import ai.ravenroot.api.node.service.AgentResourceSession;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.persistence.AgentAuthorityBinding;
import ai.ravenroot.api.persistence.AgentAuthorityGrantRegistration;
import ai.ravenroot.api.persistence.AgentAuthorityRootRegistration;
import ai.ravenroot.api.persistence.AgentAuthorityState;
import ai.ravenroot.api.persistence.AgentAuthorityControl;
import ai.ravenroot.api.persistence.AgentAuthorityControlState;
import ai.ravenroot.api.persistence.AgentBudgetOperation;
import ai.ravenroot.api.persistence.AgentBudgetReservation;
import ai.ravenroot.api.persistence.AgentBudgetVector;
import ai.ravenroot.api.persistence.AgentGrantState;
import ai.ravenroot.api.persistence.AgentOperationKey;
import ai.ravenroot.api.persistence.AgentReservationState;
import ai.ravenroot.api.persistence.DurableAgentAuthorityBudget;
import ai.ravenroot.api.persistence.DurableToolApproval;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.ToolApprovalRegistration;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.approval.ToolApprovalBudgetHooks;
import ai.ravenroot.core.runtime.ExecutionRecorder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable, process-rooted first-party agent authority and budget mediator. */
public final class AgentAuthorityBudgetService implements AgentResourceService, ToolApprovalBudgetHooks {
    private static final String INTERNAL_DELEGATION_SCOPE = "runtime:delegate";
    private static final String INTERNAL_ROOT_SCOPE = "runtime:root";
    private final ExecutionStore store;
    private final Clock clock;
    private final AgentAuthorityBudgetPolicy policy;
    private final AgentBudgetTelemetry telemetry;
    private final ConcurrentHashMap<ExecutionKey, ExecutionRecorder> liveRecorders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InvocationKey, UUID> aliases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InvocationKey, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Creates the durable first-party authority mediator.
     * @param store execution store supporting agent-authority budgets
     * @param clock runtime clock used only for bounded request calculations
     * @param policy immutable operator policy and rate card
     * @param telemetry identifier-free aggregate sink
     */
    public AgentAuthorityBudgetService(ExecutionStore store, Clock clock,
                                       AgentAuthorityBudgetPolicy policy,
                                       AgentBudgetTelemetry telemetry) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        if (!store.supports(StoreCapability.AGENT_AUTHORITY_BUDGETS)) {
            throw new IllegalArgumentException("execution store does not support agent authority budgets");
        }
    }

    /**
     * Associates all resource writes for one live execution with its current store fence.
     * @param key execution identity
     * @param recorder fenced execution writer
     * @return handle that removes this exact live binding
     */
    public AutoCloseable bindLive(ExecutionKey key, ExecutionRecorder recorder) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(recorder, "recorder");
        ExecutionRecorder prior = liveRecorders.putIfAbsent(key, recorder);
        if (prior != null && prior != recorder) throw new IllegalStateException("execution already has a budget writer");
        return () -> liveRecorders.remove(key, recorder);
    }

    /**
     * Terminates grants still active after the execution has reached its final outcome.
     * @param key completed execution identity
     * @param completed whether the execution completed successfully rather than failing or cancelling
     */
    public void finishProcess(ExecutionKey key, boolean completed) {
        ExecutionRecorder recorder = liveRecorders.get(Objects.requireNonNull(key, "key"));
        DurableAgentAuthorityBudget budget = budget(key).orElse(null);
        if (recorder == null || budget == null) return;
        List<AgentBudgetOperation> terminal = budget.grants().values().stream()
                .filter(grant -> grant.state() == AgentGrantState.ACTIVE)
                .map(grant -> completed
                        ? (AgentBudgetOperation) new AgentBudgetOperation.ExhaustGrant(
                                grant.registration().grantId())
                        : new AgentBudgetOperation.CancelGrant(grant.registration().grantId()))
                .toList();
        if (terminal.isEmpty()) return;
        recorder.record(List.of(), List.of(), terminal);
        telemetry.record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                AgentBudgetTelemetry.Outcome.RELEASED, terminal.size());
    }

    @Override
    public AgentResourceSession admit(NodeMessage message, AgentResourceRequest request) {
        Objects.requireNonNull(message, "message"); Objects.requireNonNull(request, "request");
        {
            AgentAuthorityControl control = activeControl();
            ExecutionKey key = key(message);
            ExecutionRecorder recorder = recorder(key);
            DurableAgentAuthorityBudget budget = budget(key).orElse(null);
            UUID grantId = grantId(key, message.invocationId(), control.epoch());
            if (budget != null && budget.grants().containsKey(grantId)) {
                var existing = budget.grants().get(grantId);
                AgentAuthorityBinding expected = new AgentAuthorityBinding(grantId, message.nodeId(),
                        message.invocationId(), message.parentInvocationIds());
                if (existing.state() != AgentGrantState.ACTIVE || !existing.binding().equals(expected)) {
                    throw refused();
                }
            }
            InvocationKey invocation = InvocationKey.of(message);
            Session session = new Session(message, request, grantId, control.epoch(), recorder, false);
            Session prior = sessions.putIfAbsent(invocation, session);
            if (prior != null) return prior;
            var operations = new ArrayList<AgentBudgetOperation>();
            try {
                if (budget == null) {
                    operations.add(new AgentBudgetOperation.RegisterRoot(
                            root(message.security(), key, clock.instant()), control.epoch()));
                } else {
                    requireIdentity(budget, message);
                    requirePolicyCompatible(budget);
                    if (budget.state() != AgentAuthorityState.ACTIVE
                            || budget.controlEpoch() != control.epoch()) throw refused();
                }
                DurableAgentAuthorityBudget projected = budget;
                if (projected == null || !projected.grants().containsKey(grantId)) {
                    Set<UUID> parents = parentGrants(projected, message.parentInvocationIds());
                    boolean knownAgentParent = projected != null && projected.grants().values().stream()
                            .anyMatch(grant -> message.parentInvocationIds().contains(
                                    grant.binding().invocationId()));
                    if (knownAgentParent && parents.isEmpty()) {
                        throw refused();
                    }
                    AgentAuthorityGrantRegistration grant = grant(projected, grantId, parents, request,
                            clock.instant());
                    long bootEpoch = projected == null ? policy.bootEpoch() : projected.root().bootEpoch();
                    operations.add(new AgentBudgetOperation.RegisterGrant(grant,
                            new AgentAuthorityBinding(grantId, message.nodeId(), message.invocationId(),
                                    message.parentInvocationIds()), bootEpoch, control.epoch()));
                }
                if (!operations.isEmpty()) {
                    recorder.record(List.of(), List.of(event(message, "AGENT_AUTHORITY_ADMITTED", "RESERVED",
                            AgentBudgetVector.ZERO)), operations);
                    if (operations.stream().anyMatch(AgentBudgetOperation.RegisterGrant.class::isInstance)) {
                        telemetry.record(AgentBudgetTelemetry.Dimension.TEAM_CUMULATIVE,
                                AgentBudgetTelemetry.Outcome.USED, 1);
                        telemetry.record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                                AgentBudgetTelemetry.Outcome.RESERVED, 1);
                    }
                }
                aliases.put(invocation, grantId);
                session.admissionSucceeded();
                return session;
            } catch (RuntimeException failure) {
                sessions.remove(invocation, session);
                session.admissionFailed(failure);
                throw failure;
            }
        }
    }

    @Override
    public AgentResourceSession resume(ToolCallContinuationInput continuation, AgentResourceRequest request) {
        Objects.requireNonNull(continuation, "continuation"); Objects.requireNonNull(request, "request");
        AgentAuthorityControl control = activeControl();
        NodeMessage current = continuation.message();
        ExecutionKey key = key(current);
        DurableToolApproval approval = await(store.loadToolApproval(key, continuation.approvalId()))
                .orElseThrow(this::refused);
        ToolApprovalRegistration exact = approval.request();
        if (!matchesDecision(approval, continuation.decision())
                || !exact.traversalId().equals(continuation.originalTraversalId())
                || !exact.invocationId().equals(continuation.originalInvocationId())
                || !exact.attemptId().equals(continuation.originalAttemptId())
                || !exact.nodeId().equals(current.nodeId()) || !exact.tool().equals(continuation.tool())
                || !exact.argumentsDigest().equals(continuation.argumentsDigest())
                || !java.util.Arrays.equals(exact.canonicalArguments(), continuation.canonicalArguments())
                || exact.continuationVersion() != continuation.version()
                || !exact.continuationDigest().equals(continuation.checkpointDigest())
                || !java.util.Arrays.equals(exact.continuation(), continuation.checkpoint())) {
            throw refused();
        }
        DurableAgentAuthorityBudget budget = budget(key).orElseThrow(this::refused);
        if (!budget.root().runtimeInstanceId().equals(policy.runtimeInstanceId())
                || !budget.root().security().equals(current.security())
                || budget.controlEpoch() != control.epoch()) {
            throw refused();
        }
        requirePolicyCompatible(budget);
        UUID originalGrant = grantFor(key, approval.request().invocationId());
        String operationKey = toolOperationKey(key, originalGrant, approval.request().invocationId(),
                approval.request().attemptId(), approval.request().nodeId(), approval.request().callId());
        AgentBudgetReservation reservation = budget.reservations().values().stream()
                .filter(candidate -> candidate.operationKey().equals(operationKey)).findFirst()
                .orElseThrow(this::refused);
        if (budget.state() != AgentAuthorityState.ACTIVE
                || budget.grants().get(reservation.grantId()) == null
                || budget.grants().get(reservation.grantId()).state() != AgentGrantState.ACTIVE) {
            throw refused();
        }
        ExecutionRecorder recorder = recorder(key);
        Session session = new Session(current, request, reservation.grantId(), control.epoch(), recorder, true);
        InvocationKey invocation = InvocationKey.of(current);
        sessions.put(invocation, session);
        aliases.put(invocation, reservation.grantId());
        return session;
    }

    /** Reserves and dispatches a direct model-authorized tool call before its effect. */
    ToolReservation reserveDirectTool(NodeMessage message, UUID callId) {
        activeControl();
        ExecutionKey key = key(message);
        UUID grantId = grantFor(message);
        DurableAgentAuthorityBudget budget = budget(key).orElseThrow(this::refused);
        AgentBudgetReservation reservation = toolReservation(key, grantId, message.nodeId(),
                message.invocationId(), message.attemptId(), callId);
        recorder(key).record(List.of(), List.of(event(message, "AGENT_TOOL_RESERVED", "RESERVED",
                        reservation.requested())), List.of(
                new AgentBudgetOperation.Hold(reservation, budget.root().bootEpoch(), budget.controlEpoch()),
                new AgentBudgetOperation.Dispatch(reservation.reservationId(), budget.root().bootEpoch(),
                        budget.controlEpoch())));
        recordTelemetry(reservation.requested(), AgentBudgetTelemetry.Outcome.RESERVED);
        return new ToolReservation(key, message, reservation.reservationId(), recorder(key));
    }

    /** Charges a denied proposal once; no tool effect is implied. */
    void chargeDeniedTool(NodeMessage message, UUID callId) {
        ToolReservation reservation = reserveDirectTool(message, callId);
        reservation.settle();
    }

    @Override
    public Optional<AgentBudgetOperation> hold(ExecutionKey key, ToolApprovalRegistration request) {
        DurableAgentAuthorityBudget budget = budget(key).orElse(null);
        if (budget == null) return Optional.empty();
        UUID grantId = grantFor(key, request.invocationId());
        return Optional.of(new AgentBudgetOperation.Hold(toolReservation(key, grantId, request.nodeId(),
                request.invocationId(), request.attemptId(), request.callId()),
                budget.root().bootEpoch(), budget.controlEpoch()));
    }

    @Override public List<AgentBudgetOperation> dispatch(DurableToolApproval approval) {
        activeControl();
        DurableAgentAuthorityBudget budget = budget(approval.key()).orElse(null);
        if (budget == null) return List.of();
        if (budget.state() != AgentAuthorityState.ACTIVE) throw refused();
        AgentBudgetReservation reservation = reservationFor(approval, budget);
        if (reservation == null) return List.of();
        if (reservation.state() != AgentReservationState.HELD
                || !budget.root().runtimeInstanceId().equals(policy.runtimeInstanceId())) {
            throw refused();
        }
        var operations = new ArrayList<AgentBudgetOperation>();
        long controlEpoch = budget.controlEpoch();
        long bootEpoch = budget.root().bootEpoch();
        operations.add(new AgentBudgetOperation.Dispatch(reservation.reservationId(), bootEpoch, controlEpoch));
        return List.copyOf(operations);
    }
    @Override public Optional<AgentBudgetOperation> release(DurableToolApproval approval) {
        return reservationOperation(approval, (budget, reservation) ->
                new AgentBudgetOperation.Release(reservation.reservationId()));
    }
    @Override public Optional<AgentBudgetOperation> settle(DurableToolApproval approval) {
        return reservationOperation(approval, (budget, reservation) ->
                new AgentBudgetOperation.Settle(reservation.reservationId(), reservation.requested()));
    }
    @Override public Optional<AgentBudgetOperation> indeterminate(DurableToolApproval approval) {
        return reservationOperation(approval, (budget, reservation) ->
                new AgentBudgetOperation.MarkIndeterminate(reservation.reservationId()));
    }

    /**
     * Applies the authenticated store-global emergency stop idempotently.
     * @param context authenticated platform-administrator context
     * @return committed control epoch
     */
    public long trip(RequestContext context) {
        requireOperator(context);
        ControlTransition transition = transitionControl(AgentAuthorityControlState.ACTIVE,
                AgentAuthorityControlState.KILLED);
        if (transition.changed()) {
            long released = transition.control().teamActiveReleased()
                    - transition.previousTeamActiveReleased();
            record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                    AgentBudgetTelemetry.Outcome.RELEASED, released);
        }
        RuntimeException cleanupFailure = null;
        for (Session session : List.copyOf(sessions.values())) {
            try {
                session.cancelForKill();
            } catch (RuntimeException failure) {
                cleanupFailure = combine(cleanupFailure, failure);
            }
        }
        if (cleanupFailure != null) throw cleanupFailure;
        return transition.control().epoch();
    }

    /**
     * Resets the authenticated store-global control without reviving old roots.
     * @param context authenticated platform-administrator context
     * @return committed control epoch
     */
    public long reset(RequestContext context) {
        requireOperator(context);
        return transitionControl(AgentAuthorityControlState.KILLED,
                AgentAuthorityControlState.ACTIVE).control().epoch();
    }

    private ControlTransition transitionControl(AgentAuthorityControlState expected,
                                                AgentAuthorityControlState target) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            AgentAuthorityControl current = await(store.loadAgentAuthorityControl());
            if (current.state() == target) {
                return new ControlTransition(current, current.teamActiveReleased(), false);
            }
            if (current.state() != expected) throw refused();
            try {
                return new ControlTransition(await(store.transitionAgentAuthorityControl(
                        expected, current.epoch(), target)), current.teamActiveReleased(), true);
            } catch (RuntimeException stale) {
                if (ai.ravenroot.api.persistence.ExecutionStoreException.unwrap(stale) == null) throw stale;
                AgentAuthorityControl winner = await(store.loadAgentAuthorityControl());
                if (winner.state() == target) {
                    return new ControlTransition(winner, winner.teamActiveReleased(), false);
                }
                if (attempt == 3) throw stale;
            }
        }
        throw refused();
    }

    private record ControlTransition(AgentAuthorityControl control,
                                     long previousTeamActiveReleased,
                                     boolean changed) { }

    private Optional<AgentBudgetOperation> reservationOperation(DurableToolApproval approval,
                                                                 ReservationOperation factory) {
        DurableAgentAuthorityBudget budget = budget(approval.key()).orElse(null);
        if (budget == null) return Optional.empty();
        AgentBudgetReservation reservation = reservationFor(approval, budget);
        if (reservation != null && reservation.state().terminal()) return Optional.empty();
        return reservation == null ? Optional.empty() : Optional.of(factory.create(budget, reservation));
    }

    private AgentBudgetReservation reservationFor(DurableToolApproval approval,
                                                    DurableAgentAuthorityBudget budget) {
        UUID grantId = grantFor(approval.key(), approval.request().invocationId());
        String operationKey = toolOperationKey(approval.key(), grantId, approval.request().invocationId(),
                approval.request().attemptId(), approval.request().nodeId(), approval.request().callId());
        return budget.reservations().values().stream()
                .filter(candidate -> candidate.operationKey().equals(operationKey)).findFirst().orElse(null);
    }

    private AgentBudgetReservation toolReservation(ExecutionKey key, UUID grantId, String nodeId,
                                                    UUID invocationId, UUID attemptId, UUID callId) {
        String operationKey = toolOperationKey(key, grantId, invocationId, attemptId, nodeId, callId);
        return new AgentBudgetReservation(UUID.nameUUIDFromBytes(operationKey.getBytes(StandardCharsets.UTF_8)),
                grantId, operationKey, new AgentBudgetVector(0, 0, 0, 0, 0, 1, 0, 0, 0),
                AgentBudgetVector.ZERO, AgentReservationState.HELD);
    }

    private static String toolOperationKey(ExecutionKey key, UUID grantId, UUID invocationId, UUID attemptId,
                                           String nodeId, UUID callId) {
        return AgentOperationKey.of(key, grantId, nodeId, invocationId, attemptId,
                AgentOperationKey.Kind.TOOL_CALL, 0, callId);
    }

    private UUID grantFor(NodeMessage message) { return grantFor(key(message), message.invocationId()); }

    private UUID grantFor(ExecutionKey key, UUID invocationId) {
        UUID alias = aliases.get(new InvocationKey(key, invocationId));
        if (alias != null) return alias;
        DurableAgentAuthorityBudget budget = budget(key).orElseThrow(this::refused);
        return budget.grants().values().stream()
                .filter(grant -> grant.binding().invocationId().equals(invocationId))
                .map(grant -> grant.registration().grantId()).findFirst().orElseThrow(this::refused);
    }

    private Set<UUID> parentGrants(DurableAgentAuthorityBudget budget, Set<UUID> invocationIds) {
        if (budget == null || invocationIds.isEmpty()) return Set.of();
        var result = new LinkedHashSet<UUID>();
        for (var grant : budget.grants().values()) {
            if (grant.state() != AgentGrantState.CANCELLED
                    && grant.registration().authorityScopes().contains(INTERNAL_DELEGATION_SCOPE)
                    && invocationIds.contains(grant.binding().invocationId())) {
                result.add(grant.registration().grantId());
            }
        }
        return Set.copyOf(result);
    }

    private AgentAuthorityGrantRegistration grant(DurableAgentAuthorityBudget current, UUID grantId,
                                                    Set<UUID> parents, AgentResourceRequest request, Instant now) {
        AgentBudgetVector root = policy.rootMaxima();
        long total = request.maximumTotalTokens() == 0 ? Long.MAX_VALUE : request.maximumTotalTokens();
        AgentBudgetVector ceilings = new AgentBudgetVector(
                Math.min(root.turns(), request.maximumTurns()),
                Math.min(root.inputTokens(), total), Math.min(root.outputTokens(), total),
                Math.min(root.elapsedMillis(), request.maximumDuration().toMillis()),
                root.costMicros(), root.toolCalls(), root.delegationDepth(),
                root.teamCumulative(), root.teamActive());
        long depth = 1;
        UUID primary = null;
        Set<String> data = policy.dataScopes();
        Set<String> authority = new LinkedHashSet<>(policy.authorityScopes());
        Instant deadline = minimum(now.plus(request.maximumDuration()), now.plus(policy.rootLifetime()));
        if (current != null && !parents.isEmpty()) {
            for (UUID parentId : parents) {
                var parent = current.grants().get(parentId);
                if (parent == null || parent.state() == AgentGrantState.CANCELLED) throw refused();
                if (primary == null) primary = parentId;
                depth = Math.max(depth, parent.registration().depth() + 1);
                data = intersection(data, parent.registration().dataScopes());
                authority = intersection(authority, parent.registration().authorityScopes());
                ceilings = minimum(ceilings, parent.registration().ceilings());
                deadline = minimum(deadline, parent.registration().absoluteDeadline());
            }
            if (deadline.equals(current.grants().get(primary).registration().absoluteDeadline())
                    && ceilings.equals(current.grants().get(primary).registration().ceilings())
                    && data.equals(current.grants().get(primary).registration().dataScopes())
                    && authority.equals(current.grants().get(primary).registration().authorityScopes())) {
                if (ceilings.turns() == 0) throw refused();
                ceilings = new AgentBudgetVector(ceilings.turns() - 1, ceilings.inputTokens(),
                        ceilings.outputTokens(), ceilings.elapsedMillis(), ceilings.costMicros(),
                        ceilings.toolCalls(), ceilings.delegationDepth(), ceilings.teamCumulative(),
                        ceilings.teamActive());
            }
        }
        long combinedTokens = request.maximumTotalTokens() == 0
                ? addExact(ceilings.inputTokens(), ceilings.outputTokens())
                : Math.min(request.maximumTotalTokens(),
                addExact(ceilings.inputTokens(), ceilings.outputTokens()));
        return new AgentAuthorityGrantRegistration(grantId, primary, parents, depth, data,
                Set.copyOf(authority), ceilings, combinedTokens, deadline);
    }

    private AgentAuthorityRootRegistration root(ai.ravenroot.api.security.SecurityContext security,
                                                 ExecutionKey key, Instant now) {
        return rootAtDeadline(security, key, now.plus(policy.rootLifetime()));
    }

    private AgentAuthorityRootRegistration rootAtDeadline(ai.ravenroot.api.security.SecurityContext security,
                                                           ExecutionKey key, Instant deadline) {
        var rootAuthority = new LinkedHashSet<>(policy.authorityScopes());
        rootAuthority.add(INTERNAL_ROOT_SCOPE);
        return new AgentAuthorityRootRegistration(policy.runtimeInstanceId(), policy.bootEpoch(), security,
                policy.policyVersion(), policy.rateCardVersion(), deadline, policy.dataScopes(),
                Set.copyOf(rootAuthority), policy.rootMaxima(), policy.currency());
    }

    private void requireIdentity(DurableAgentAuthorityBudget budget, NodeMessage message) {
        if (!budget.root().runtimeInstanceId().equals(policy.runtimeInstanceId())
                || !budget.root().security().equals(message.security())) {
            throw refused();
        }
    }

    private AgentAuthorityControl activeControl() {
        AgentAuthorityControl control = await(store.loadAgentAuthorityControl());
        if (control.state() != AgentAuthorityControlState.ACTIVE) throw refused();
        return control;
    }

    private void requirePolicyCompatible(DurableAgentAuthorityBudget budget) {
        if (!budget.root().runtimeInstanceId().equals(policy.runtimeInstanceId())
                || !budget.root().policyVersion().equals(policy.policyVersion())
                || !budget.root().rateCardVersion().equals(policy.rateCardVersion())
                || !budget.root().currency().equals(policy.currency())) throw refused();
    }

    private ExecutionRecorder recorder(ExecutionKey key) {
        ExecutionRecorder recorder = liveRecorders.get(key);
        if (recorder == null) throw refused();
        return recorder;
    }

    private Optional<DurableAgentAuthorityBudget> budget(ExecutionKey key) {
        return await(store.loadAgentAuthorityBudget(key));
    }

    private void requireOperator(RequestContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.roles().contains(Role.PLATFORM_ADMIN)
                || !context.scopes().contains(
                ai.ravenroot.api.security.AuthorizationAction.AGENT_AUTHORITY_CONTROL.requiredScope())) {
            throw new SecurityException("agent authority control denied");
        }
    }

    private NodePackageServiceException refused() {
        return new NodePackageServiceException(NodePackageServiceException.Reason.ADMISSION_REFUSED);
    }

    private EventEnvelope event(NodeMessage message, String type, String outcome, AgentBudgetVector vector) {
        return event(message, type, outcome, vector, recorder(key(message)).graphVersionPin().reference());
    }

    private EventEnvelope event(NodeMessage message, String type, String outcome, AgentBudgetVector vector,
                                String graphVersion) {
        byte[] payload = PayloadJson.write(PayloadValue.fromJava(Map.of(
                "outcome", outcome, "turns", vector.turns(), "inputTokens", vector.inputTokens(),
                "outputTokens", vector.outputTokens(), "elapsedMillis", vector.elapsedMillis(),
                "costMicros", vector.costMicros(), "toolCalls", vector.toolCalls(),
                "teamCumulative", vector.teamCumulative(), "teamActive", vector.teamActive()),
                PayloadLimits.DEFAULTS)).getBytes(StandardCharsets.UTF_8);
        return EventEnvelope.of(UUID.randomUUID(), message.tenantId(), type, message.processInstanceId(),
                message.traversalId(), message.invocationId(), message.attemptId(), null,
                message.security().requestId(), graphVersion, clock.instant(),
                OpaquePayload.of(payload, "application/json"));
    }

    private void applyUnfenced(ExecutionKey key, EventEnvelope event, AgentBudgetOperation operation) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            var stored = await(store.load(key));
            try {
                await(store.apply(ai.ravenroot.api.persistence.ExecutionBatch.to(key)
                        .expecting(ai.ravenroot.api.persistence.RevisionExpectation.exactly(stored.revision()))
                        .applyAgentBudget(operation).publish(event).build()));
                return;
            } catch (ai.ravenroot.api.persistence.ExecutionStoreException conflict) {
                if (!(conflict.failure() instanceof ai.ravenroot.api.persistence.ExecutionStoreFailure.ConcurrencyConflict)
                        || attempt == 3) throw conflict;
                DurableAgentAuthorityBudget refreshed = budget(key).orElseThrow();
                operation = new AgentBudgetOperation.KillRoot(refreshed.controlEpoch());
            }
        }
    }

    private void recordTelemetry(AgentBudgetVector vector, AgentBudgetTelemetry.Outcome outcome) {
        record(AgentBudgetTelemetry.Dimension.TURNS, outcome, vector.turns());
        record(AgentBudgetTelemetry.Dimension.INPUT_TOKENS, outcome, vector.inputTokens());
        record(AgentBudgetTelemetry.Dimension.OUTPUT_TOKENS, outcome, vector.outputTokens());
        record(AgentBudgetTelemetry.Dimension.ELAPSED_MILLIS, outcome, vector.elapsedMillis());
        record(AgentBudgetTelemetry.Dimension.COST_MICROS, outcome, vector.costMicros());
        record(AgentBudgetTelemetry.Dimension.TOOL_CALLS, outcome, vector.toolCalls());
        record(AgentBudgetTelemetry.Dimension.TEAM_CUMULATIVE, outcome, vector.teamCumulative());
        record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE, outcome, vector.teamActive());
    }

    private void record(AgentBudgetTelemetry.Dimension dimension, AgentBudgetTelemetry.Outcome outcome, long value) {
        if (value > 0) telemetry.record(dimension, outcome, value);
    }

    private static ExecutionKey key(NodeMessage message) {
        return new ExecutionKey(message.tenantId(), message.processInstanceId());
    }

    private static UUID grantId(ExecutionKey key, UUID invocationId, long epoch) {
        return UUID.nameUUIDFromBytes((key.tenantId() + ':' + key.processInstanceId() + ':' + invocationId
                + ':' + epoch).getBytes(StandardCharsets.UTF_8));
    }

    private static Instant minimum(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static boolean matchesDecision(DurableToolApproval approval,
                                           ToolCallContinuationInput.Decision decision) {
        return switch (decision) {
            case APPROVED -> approval.status() == ai.ravenroot.api.persistence.ToolApprovalStatus.CONSUMED;
            case DENIED -> approval.status() == ai.ravenroot.api.persistence.ToolApprovalStatus.DENIED;
            case EXPIRED -> approval.status() == ai.ravenroot.api.persistence.ToolApprovalStatus.EXPIRED;
            case CANCELLED -> approval.status() == ai.ravenroot.api.persistence.ToolApprovalStatus.CANCELLED;
        };
    }
    private static Set<String> intersection(Set<String> a, Set<String> b) {
        var result = new LinkedHashSet<>(a); result.retainAll(b); return Set.copyOf(result);
    }
    private static AgentBudgetVector minimum(AgentBudgetVector a, AgentBudgetVector b) {
        return new AgentBudgetVector(Math.min(a.turns(), b.turns()), Math.min(a.inputTokens(), b.inputTokens()),
                Math.min(a.outputTokens(), b.outputTokens()), Math.min(a.elapsedMillis(), b.elapsedMillis()),
                Math.min(a.costMicros(), b.costMicros()), Math.min(a.toolCalls(), b.toolCalls()),
                Math.min(a.delegationDepth(), b.delegationDepth()),
                Math.min(a.teamCumulative(), b.teamCumulative()), Math.min(a.teamActive(), b.teamActive()));
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private record InvocationKey(ExecutionKey key, UUID invocationId) {
        static InvocationKey of(NodeMessage message) {
            return new InvocationKey(AgentAuthorityBudgetService.key(message), message.invocationId());
        }
    }

    @FunctionalInterface
    private interface ReservationOperation {
        AgentBudgetOperation create(DurableAgentAuthorityBudget budget, AgentBudgetReservation reservation);
    }

    public final class ToolReservation {
        private final ExecutionKey key;
        private final NodeMessage message;
        private final UUID reservationId;
        private final ExecutionRecorder recorder;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private ToolReservation(ExecutionKey key, NodeMessage message, UUID reservationId,
                                ExecutionRecorder recorder) {
            this.key = key; this.message = message; this.reservationId = reservationId; this.recorder = recorder;
        }
        public void settle() {
            if (!terminal.compareAndSet(false, true)) return;
            AgentBudgetReservation reservation = budget(key).orElseThrow().reservations().get(reservationId);
            recorder.record(List.of(), List.of(event(message, "AGENT_TOOL_SETTLED", "USED",
                            reservation.requested())),
                    List.of(new AgentBudgetOperation.Settle(reservationId, reservation.requested())));
            recordTelemetry(reservation.requested(), AgentBudgetTelemetry.Outcome.USED);
        }
        public void indeterminate() {
            if (!terminal.compareAndSet(false, true)) return;
            AgentBudgetReservation reservation = budget(key).orElseThrow().reservations().get(reservationId);
            recorder.record(List.of(), List.of(event(message, "AGENT_TOOL_INDETERMINATE", "INDETERMINATE",
                            reservation.requested())),
                    List.of(new AgentBudgetOperation.MarkIndeterminate(reservationId)));
            recordTelemetry(reservation.requested(), AgentBudgetTelemetry.Outcome.INDETERMINATE);
        }
    }

    private final class Session implements AgentResourceSession {
        private final NodeMessage message;
        private final AgentResourceRequest request;
        private final UUID grantId;
        private final long admittedRuntimeEpoch;
        private final ExecutionRecorder recorder;
        private final ConcurrentHashMap<Long, ModelReservation> turns = new ConcurrentHashMap<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final CompletableFuture<Void> admission = new CompletableFuture<>();

        private Session(NodeMessage message, AgentResourceRequest request, UUID grantId,
                        long admittedRuntimeEpoch, ExecutionRecorder recorder, boolean admitted) {
            this.message = message; this.request = request; this.grantId = grantId;
            this.admittedRuntimeEpoch = admittedRuntimeEpoch; this.recorder = recorder;
            if (admitted) admission.complete(null);
        }

        private void admissionSucceeded() { admission.complete(null); }

        private void admissionFailed(RuntimeException failure) { admission.completeExceptionally(failure); }

        @Override public AgentModelReservation reserveModelTurn(long ordinal) {
            requirePermit();
            if (ordinal < 1) throw new IllegalArgumentException("turn ordinal must be positive");
            return turns.computeIfAbsent(ordinal, this::reserve);
        }

        @Override public AgentResourceSession createChild(AgentChildResourceRequest childRequest) {
            requirePermit();
            Objects.requireNonNull(childRequest, "childRequest");
            NodeMessage child = childRequest.child();
            if (!child.security().equals(message.security())
                    || !child.processInstanceId().equals(message.processInstanceId())
                    || !child.parentInvocationIds().contains(message.invocationId())) {
                throw refused();
            }
            DurableAgentAuthorityBudget current = budget(key(message)).orElseThrow(
                    AgentAuthorityBudgetService.this::refused);
            var parent = current.grants().get(grantId);
            if (parent == null || parent.state() != AgentGrantState.ACTIVE
                    || !parent.registration().authorityScopes().contains(INTERNAL_DELEGATION_SCOPE)
                    || !parent.registration().dataScopes().containsAll(childRequest.dataScopes())
                    || !parent.registration().authorityScopes().containsAll(childRequest.authorityScopes())) {
                throw refused();
            }
            UUID childGrantId = grantId(key(child), child.invocationId(), admittedRuntimeEpoch);
            AgentAuthorityGrantRegistration derived = grant(current, childGrantId, Set.of(grantId),
                    childRequest.resources(), clock.instant());
            derived = new AgentAuthorityGrantRegistration(derived.grantId(), grantId, Set.of(grantId),
                    parent.registration().depth() + 1, childRequest.dataScopes(), childRequest.authorityScopes(),
                    derived.ceilings(), derived.maximumTotalTokens(), derived.absoluteDeadline());
            InvocationKey childKey = InvocationKey.of(child);
            Session childSession = new Session(child, childRequest.resources(), childGrantId,
                    admittedRuntimeEpoch, recorder, false);
            Session existingSession = sessions.putIfAbsent(childKey, childSession);
            if (existingSession != null) return existingSession;
            boolean newlyRegistered = !current.grants().containsKey(childGrantId);
            try {
                recorder.record(List.of(), List.of(event(child, "AGENT_CHILD_AUTHORITY_CREATED", "RESERVED",
                                AgentBudgetVector.ZERO)),
                        List.of(new AgentBudgetOperation.RegisterGrant(derived,
                                new AgentAuthorityBinding(childGrantId, child.nodeId(), child.invocationId(),
                                        child.parentInvocationIds()), current.root().bootEpoch(),
                                current.controlEpoch())));
            } catch (RuntimeException refused) {
                sessions.remove(childKey, childSession);
                childSession.admissionFailed(refused);
                throw AgentAuthorityBudgetService.this.refused();
            }
            if (newlyRegistered) {
                telemetry.record(AgentBudgetTelemetry.Dimension.TEAM_CUMULATIVE,
                        AgentBudgetTelemetry.Outcome.USED, 1);
                telemetry.record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                        AgentBudgetTelemetry.Outcome.RESERVED, 1);
            }
            aliases.put(childKey, childGrantId);
            childSession.admissionSucceeded();
            return childSession;
        }

        private ModelReservation reserve(long ordinal) {
            ExecutionKey key = key(message);
            DurableAgentAuthorityBudget budget = budget(key).orElseThrow(AgentAuthorityBudgetService.this::refused);
            Instant now = clock.instant();
            RemainingCapacity remaining = remainingCapacity(budget, grantId, now);
            if (remaining.vector().turns() == 0 || remaining.deadlineMillis() == 0) throw refused();
            long input = policy.maximumInputTokensPerTurn();
            if (remaining.vector().inputTokens() < input
                    || remaining.maximumTotalTokens() <= input) throw refused();
            long output = Math.min(request.maximumOutputTokensPerTurn() == 0
                    ? policy.maximumOutputTokensPerTurn()
                    : Math.min(policy.maximumOutputTokensPerTurn(), request.maximumOutputTokensPerTurn()),
                    remaining.vector().outputTokens());
            output = Math.min(output, remaining.maximumTotalTokens() - input);
            long inputCost = exactCost(input, 0);
            if (inputCost > remaining.vector().costMicros()) throw refused();
            if (policy.outputTokenRateMicros() > 0) {
                output = Math.min(output,
                        (remaining.vector().costMicros() - inputCost) / policy.outputTokenRateMicros());
            }
            if (output == 0) throw refused();
            long cost = exactCost(input, output);
            long elapsed = Math.min(request.maximumDuration().toMillis(),
                    Math.min(remaining.vector().elapsedMillis(), remaining.deadlineMillis()));
            if (elapsed == 0) throw refused();
            AgentBudgetVector requested = new AgentBudgetVector(1, input, output,
                    elapsed, cost, 0, 0, 0, 0);
            String operationKey = AgentOperationKey.of(key, grantId, message.nodeId(), message.invocationId(),
                    message.attemptId(), AgentOperationKey.Kind.MODEL_TURN, ordinal, null);
            UUID reservationId = UUID.nameUUIDFromBytes(operationKey.getBytes(StandardCharsets.UTF_8));
            AgentBudgetReservation reservation = new AgentBudgetReservation(reservationId, grantId, operationKey,
                    requested, AgentBudgetVector.ZERO, AgentReservationState.HELD);
            recorder.record(List.of(), List.of(event(message, "AGENT_MODEL_RESERVED", "RESERVED", requested)),
                    List.of(new AgentBudgetOperation.Hold(reservation, budget.root().bootEpoch(),
                            budget.controlEpoch())));
            recordTelemetry(requested, AgentBudgetTelemetry.Outcome.RESERVED);
            return new ModelReservation(this, message, reservationId, requested, now, recorder);
        }

        @Override public void complete() { terminate(new AgentBudgetOperation.ExhaustGrant(grantId)); }
        @Override public void cancel() { terminate(new AgentBudgetOperation.CancelGrant(grantId)); }
        @Override public void failAttempt() { suspend(); }
        @Override public void suspend() {
            if (!terminal.compareAndSet(false, true)) return;
            detach();
        }

        private void terminate(AgentBudgetOperation operation) {
            if (!terminal.compareAndSet(false, true)) return;
            RuntimeException failure = cancelTurns();
            long activeBefore = budget(key(message)).map(value -> value.reserved().teamActive()).orElse(0L);
            try {
                recorder.record(List.of(), List.of(event(message,
                                operation instanceof AgentBudgetOperation.ExhaustGrant
                                        ? "AGENT_AUTHORITY_EXHAUSTED" : "AGENT_AUTHORITY_CANCELLED",
                                "RELEASED", AgentBudgetVector.ZERO)), List.of(operation));
                long activeAfter = budget(key(message)).map(value -> value.reserved().teamActive()).orElse(0L);
                record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                        AgentBudgetTelemetry.Outcome.RELEASED, Math.max(0L, activeBefore - activeAfter));
            } catch (RuntimeException terminalFailure) {
                failure = combine(failure, terminalFailure);
            } finally {
                detach();
            }
            if (failure != null) throw failure;
        }

        private void cancelForKill() {
            if (!terminal.compareAndSet(false, true)) return;
            RuntimeException failure;
            try {
                failure = cancelTurns();
            } finally {
                detach();
            }
            if (failure != null) throw failure;
        }

        private void terminateAfterBreach() {
            if (!terminal.compareAndSet(false, true)) return;
            detach();
        }

        private void detach() {
            InvocationKey key = InvocationKey.of(message);
            sessions.remove(key, this); aliases.remove(key, grantId);
        }

        private RuntimeException cancelTurns() {
            RuntimeException failure = null;
            for (ModelReservation turn : List.copyOf(turns.values())) {
                try {
                    turn.cancel();
                } catch (RuntimeException turnFailure) {
                    failure = combine(failure, turnFailure);
                }
            }
            return failure;
        }

        private void requirePermit() {
            admission.join();
            AgentAuthorityControl control = activeControl();
            if (terminal.get() || admittedRuntimeEpoch != control.epoch()) throw refused();
        }
    }

    private final class ModelReservation implements AgentModelReservation {
        private final Session owner;
        private final NodeMessage message;
        private final UUID reservationId;
        private final AgentBudgetVector requested;
        private final Instant started;
        private final ExecutionRecorder recorder;
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();

        private ModelReservation(Session owner, NodeMessage message, UUID reservationId,
                                 AgentBudgetVector requested,
                                 Instant started, ExecutionRecorder recorder) {
            this.owner = owner; this.message = message; this.reservationId = reservationId;
            this.requested = requested;
            this.started = started; this.recorder = recorder;
        }

        @Override public long maximumOutputTokens() { return requested.outputTokens(); }

        @Override public Duration maximumDuration() { return Duration.ofMillis(requested.elapsedMillis()); }

        @Override public void dispatch() {
            if (terminal.get()) throw refused();
            if (!dispatched.compareAndSet(false, true)) return;
            DurableAgentAuthorityBudget budget = budget(key(message)).orElseThrow(
                    AgentAuthorityBudgetService.this::refused);
            try {
                recorder.record(List.of(), List.of(event(message, "AGENT_MODEL_DISPATCHED", "USED",
                                nonRefundable(requested))),
                        List.of(new AgentBudgetOperation.Dispatch(reservationId, budget.root().bootEpoch(),
                                budget.controlEpoch())));
            } catch (RuntimeException failure) {
                dispatched.set(false);
                throw failure;
            }
        }

        @Override public void release() {
            if (dispatched.get()) throw refused();
            if (!terminal.compareAndSet(false, true)) return;
            recorder.record(List.of(), List.of(event(message, "AGENT_MODEL_RELEASED", "RELEASED", requested)),
                    List.of(new AgentBudgetOperation.Release(reservationId)));
            recordTelemetry(requested, AgentBudgetTelemetry.Outcome.RELEASED);
        }

        @Override public void settle(Optional<Long> inputTokens, Optional<Long> outputTokens) {
            if (!dispatched.get()) throw refused();
            if (!terminal.compareAndSet(false, true)) return;
            boolean inputKnown = inputTokens != null && inputTokens.isPresent() && inputTokens.get() >= 0;
            boolean outputKnown = outputTokens != null && outputTokens.isPresent() && outputTokens.get() >= 0;
            long observedInput = inputKnown ? inputTokens.get() : requested.inputTokens();
            long observedOutput = outputKnown ? outputTokens.get() : requested.outputTokens();
            long observedCost = saturatedCost(observedInput, observedOutput);
            boolean breached = observedInput > requested.inputTokens()
                    || observedOutput > requested.outputTokens()
                    || observedCost > requested.costMicros();
            if (breached) {
                DurableAgentAuthorityBudget before = budget(key(message)).orElseThrow(
                        AgentAuthorityBudgetService.this::refused);
                long activeSlots = before.reserved().teamActive();
                AgentBudgetVector observed = new AgentBudgetVector(1, observedInput, observedOutput,
                        requested.elapsedMillis(), observedCost, 0, 0, 0, 0);
                recorder.record(List.of(), List.of(event(message, "AGENT_MODEL_BREACHED", "BREACHED",
                                requested)),
                        List.of(new AgentBudgetOperation.Breach(reservationId, observed)));
                recordTelemetry(observed, AgentBudgetTelemetry.Outcome.BREACHED);
                telemetry.record(AgentBudgetTelemetry.Dimension.TEAM_ACTIVE,
                        AgentBudgetTelemetry.Outcome.RELEASED, activeSlots);
                owner.terminateAfterBreach();
                throw new NodePackageServiceException(
                        NodePackageServiceException.Reason.BUDGET_EXHAUSTED);
            }
            boolean valid = inputKnown && outputKnown;
            long input = valid ? inputTokens.get() : requested.inputTokens();
            long output = valid ? outputTokens.get() : requested.outputTokens();
            long elapsed = valid ? Math.min(requested.elapsedMillis(), Math.max(0,
                    Duration.between(started, clock.instant()).toMillis())) : requested.elapsedMillis();
            AgentBudgetVector actual = new AgentBudgetVector(1, input, output, elapsed,
                    valid ? exactCost(input, output) : requested.costMicros(), 0, 0, 0, 0);
            recorder.record(List.of(), List.of(event(message, "AGENT_MODEL_SETTLED", "USED", actual)),
                    List.of(new AgentBudgetOperation.Settle(reservationId, actual)));
            recordTelemetry(actual, AgentBudgetTelemetry.Outcome.USED);
            recordTelemetry(requested.minus(actual), AgentBudgetTelemetry.Outcome.RELEASED);
        }

        @Override public void indeterminate() {
            if (!dispatched.get()) {
                release();
                return;
            }
            if (!terminal.compareAndSet(false, true)) return;
            recorder.record(List.of(), List.of(event(message, "AGENT_MODEL_INDETERMINATE", "INDETERMINATE",
                            requested)),
                    List.of(new AgentBudgetOperation.MarkIndeterminate(reservationId)));
            recordTelemetry(requested, AgentBudgetTelemetry.Outcome.INDETERMINATE);
        }

        private void cancel() {
            if (dispatched.get()) indeterminate(); else release();
        }
    }

    private static AgentBudgetVector nonRefundable(AgentBudgetVector requested) {
        return new AgentBudgetVector(requested.turns(), 0, 0, 0, 0,
                requested.toolCalls(), 0, 0, 0);
    }

    private static RuntimeException combine(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        if (next != first) first.addSuppressed(next);
        return first;
    }

    private long exactCost(long input, long output) {
        try {
            return Math.addExact(Math.multiplyExact(input, policy.inputTokenRateMicros()),
                    Math.multiplyExact(output, policy.outputTokenRateMicros()));
        } catch (ArithmeticException overflow) {
            throw refused();
        }
    }

    private long saturatedCost(long input, long output) {
        try {
            return Math.addExact(Math.multiplyExact(input, policy.inputTokenRateMicros()),
                    Math.multiplyExact(output, policy.outputTokenRateMicros()));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private long addExact(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw refused();
        }
    }

    private RemainingCapacity remainingCapacity(DurableAgentAuthorityBudget budget, UUID leafGrantId,
                                                  Instant now) {
        AgentBudgetVector remaining = budget.root().maxima().minus(
                budget.spent().plus(budget.reserved()));
        long maximumTotalTokens = Long.MAX_VALUE;
        Instant deadline = budget.root().absoluteDeadline();
        var found = new LinkedHashMap<UUID, DurableAgentAuthorityBudget.DurableAgentGrant>();
        var queue = new ArrayDeque<UUID>();
        queue.add(leafGrantId);
        while (!queue.isEmpty()) {
            UUID grantId = queue.remove();
            var grant = budget.grants().get(grantId);
            boolean leaf = grantId.equals(leafGrantId);
            if (grant == null || leaf && grant.state() != AgentGrantState.ACTIVE
                    || !leaf && grant.state() == AgentGrantState.CANCELLED) {
                throw refused();
            }
            if (found.putIfAbsent(grantId, grant) != null) continue;
            remaining = minimum(remaining, grant.registration().ceilings().minus(
                    grant.spent().plus(grant.reserved())));
            long usedTokens = addExact(addExact(grant.spent().inputTokens(), grant.spent().outputTokens()),
                    addExact(grant.reserved().inputTokens(), grant.reserved().outputTokens()));
            if (usedTokens > grant.registration().maximumTotalTokens()) throw refused();
            maximumTotalTokens = Math.min(maximumTotalTokens,
                    grant.registration().maximumTotalTokens() - usedTokens);
            deadline = minimum(deadline, grant.registration().absoluteDeadline());
            queue.addAll(grant.registration().contributingParentGrantIds());
        }
        long deadlineMillis = deadline.isAfter(now) ? Duration.between(now, deadline).toMillis() : 0;
        return new RemainingCapacity(remaining, maximumTotalTokens, deadlineMillis);
    }

    private record RemainingCapacity(AgentBudgetVector vector, long maximumTotalTokens,
                                     long deadlineMillis) { }
}
