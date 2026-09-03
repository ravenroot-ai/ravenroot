package ai.ravenroot.core.handler;

import ai.ravenroot.api.application.NodeInvocation;
import ai.ravenroot.api.application.ProcessInstance;
import ai.ravenroot.api.application.Traversal;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.persistence.DurableHandler;
import ai.ravenroot.api.persistence.EventEnvelope;
import ai.ravenroot.api.persistence.ExecutionBatch;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.ExecutionStoreFailure;
import ai.ravenroot.api.persistence.ExecutionTransition;
import ai.ravenroot.api.persistence.HandlerEventData;
import ai.ravenroot.api.persistence.HandlerRegistration;
import ai.ravenroot.api.persistence.HandlerStatus;
import ai.ravenroot.api.persistence.HandlerTransition;
import ai.ravenroot.api.persistence.OpaquePayload;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.api.persistence.StoreCapability;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

/**
 * Turns an inbound trigger into a durable handler transition and a re-entry traversal (PERS-05).
 *
 * <p>This is the reference monitor for durable waits, and it sits where it does for one reason: the
 * store carries authorization requirements <em>opaquely</em> and must not evaluate them, while the
 * ingress {@link RequestContext} — the only place roles and scopes legitimately exist — never
 * reaches persistence. So the store owns the state machine and this class owns the decision, and the
 * two meet in a single batch.</p>
 *
 * <h2>Why every refusal is still a durable, audited fact</h2>
 * <p>A trigger that is refused changes no execution state, which historically is how such refusals
 * become invisible. All four refusals are written to the {@link AuditTrail} instead, with the
 * category the criterion asks for and a payload-free reason. Nothing about the refused payload is
 * recorded beyond its size and media type, which
 * {@link ai.ravenroot.api.persistence.HandlerPayloadSchema} already phrases safely.</p>
 *
 * <h2>No actor is revived</h2>
 * <p>A resolution commits three things in one batch: the handler's terminal transition, the
 * {@link ExecutionTransition.TraversalAdded} that creates the re-entry traversal, and the journal
 * event that records it. The store then makes a {@link ai.ravenroot.api.persistence.PendingWork.HandlerTrigger}
 * claimable for that traversal. Nothing in that path names a live object, so the process that
 * registered the wait need not — and after a full shutdown, cannot — still exist.</p>
 *
 * <h2>Concurrency</h2>
 * <p>Writes carry {@link RevisionExpectation#exactly(long)} against the revision this class read, and
 * a {@link ExecutionStoreFailure.ConcurrencyConflict} is retried after a re-read, bounded by
 * {@link #MAX_WRITE_ATTEMPTS}. That is what its {@link ai.ravenroot.api.persistence.Retryability#RETRY_AFTER_REREAD}
 * classification asks for; a blind retry would fail forever, because the expectation is stale by
 * construction. A conflict that outlasts the bound surfaces to the caller rather than being retried
 * indefinitely, because a permanently contended instance is an operational fact, not a transient
 * one.</p>
 */
public final class DurableHandlerService {

    /** How many times a write is rebuilt from fresh state before the conflict is surfaced. */
    public static final int MAX_WRITE_ATTEMPTS = 3;

    private final ExecutionStore store;
    private final AuditTrail auditTrail;
    private final Clock clock;

    /**
     * Creates a service over one execution store and one audit trail.
     * @param store durable execution store declaring {@link StoreCapability#DURABLE_HANDLERS}.
     * @param auditTrail trail every refusal and every accepted trigger is recorded to.
     * @param clock clock used for audit and event timestamps.
     */
    public DurableHandlerService(ExecutionStore store, AuditTrail auditTrail, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.auditTrail = Objects.requireNonNull(auditTrail, "auditTrail");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!store.supports(StoreCapability.DURABLE_HANDLERS)) {
            // Composition time, not first use. The core reads static self-description to fail fast,
            // which is the whole reason capabilities() is synchronous; discovering this on the first
            // human task would mean discovering it in production.
            throw new IllegalArgumentException(
                    "the execution store does not declare " + StoreCapability.DURABLE_HANDLERS);
        }
    }

    /**
     * Resolves a waiting handler with an authorized principal's outcome, re-entering the process.
     * @param context authenticated ingress context of the resolving principal.
     * @param handlerName opaque handler name presented by the trigger.
     * @param correlationKey business identity presented by the trigger.
     * @param payload outcome body, validated against the handler's declared schema.
     * @return what happened, accepted or one of the four deterministic refusals.
     */
    public HandlerTriggerOutcome resolve(RequestContext context, String handlerName, String correlationKey,
                                         OpaquePayload payload) {
        return settle(context, handlerName, correlationKey, payload, HandlerStatus.RESOLVED);
    }

    /**
     * Refuses a waiting handler on an authorized principal's behalf, re-entering the process.
     *
     * <p>A denial is an outcome and not a failure: the process continues down whatever route its
     * author declared for a refusal, so this commits a re-entry traversal exactly as
     * {@link #resolve} does.</p>
     * @param context authenticated ingress context of the refusing principal.
     * @param handlerName opaque handler name presented by the trigger.
     * @param correlationKey business identity presented by the trigger.
     * @param payload bounded refusal body carried into the resumed traversal.
     * @return what happened, accepted or one of the four deterministic refusals.
     */
    public HandlerTriggerOutcome deny(RequestContext context, String handlerName, String correlationKey,
                                      OpaquePayload payload) {
        return settle(context, handlerName, correlationKey, payload, HandlerStatus.DENIED);
    }

    /**
     * Ends a wait that produced no trigger, re-entering the process on its timeout route.
     *
     * <p>Driven by a {@link ai.ravenroot.api.persistence.TimerSchedule} the registrant scheduled, so
     * it carries no principal: a deadline is not an actor, and recording a system placeholder as one
     * would put a fabricated principal into a durable audit record. Delivery of that timer is
     * at-least-once, so a redelivery after the handler already expired is answered as
     * {@link HandlerTriggerOutcome.AlreadySettled} rather than failing the sweep.</p>
     * @param key the stable key used to identify the requested resource.
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param correlationId end-to-end correlation identifier for the audit and journal records.
     * @return what happened, accepted or a deterministic refusal.
     */
    public HandlerTriggerOutcome expire(ExecutionKey key, UUID handlerId, String correlationId) {
        DurableHandler handler = await(store.loadHandler(key, handlerId)).orElse(null);
        if (handler == null) {
            return audited(key.tenantId(), systemPrincipal(), correlationId, "handler.expire",
                    String.valueOf(handlerId), new HandlerTriggerOutcome.NotFound());
        }
        return audited(key.tenantId(), systemPrincipal(), correlationId, "handler.expire",
                handlerId.toString(),
                commit(handler, correlationId, HandlerStatus.EXPIRED, "",
                        OpaquePayload.empty(HandlerTransition.EMPTY_CONTENT_TYPE)));
    }

    /**
     * Marks a waiting handler as having exceeded an attention threshold, leaving it resolvable.
     *
     * <p>Produces no traversal and no trigger. Re-escalating an already escalated handler is accepted
     * as a no-op, because the timer that drives it is delivered at least once and an escalation that
     * could fail on redelivery would make an ordinary retry look like an incident.</p>
     * @param key the stable key used to identify the requested resource.
     * @param handlerId the stable handler id used to identify the requested resource.
     * @param reason bounded, operator-safe reason recorded with the escalation.
     * @param correlationId end-to-end correlation identifier for the audit and journal records.
     * @return what happened, accepted or a deterministic refusal.
     */
    public HandlerTriggerOutcome escalate(ExecutionKey key, UUID handlerId, String reason,
                                          String correlationId) {
        DurableHandler handler = await(store.loadHandler(key, handlerId)).orElse(null);
        if (handler == null) {
            return audited(key.tenantId(), systemPrincipal(), correlationId, "handler.escalate",
                    String.valueOf(handlerId), new HandlerTriggerOutcome.NotFound());
        }
        return audited(key.tenantId(), systemPrincipal(), correlationId, "handler.escalate",
                handlerId.toString(), commitEscalation(handler, reason, correlationId));
    }

    // ---------------------------------------------------------------- decision

    private HandlerTriggerOutcome settle(RequestContext context, String handlerName, String correlationKey,
                                         OpaquePayload payload, HandlerStatus target) {
        Objects.requireNonNull(context, "context");
        HandlerRegistration.requireBoundedKey(handlerName, "handlerName");
        HandlerRegistration.requireBoundedKey(correlationKey, "correlationKey");
        String action = target == HandlerStatus.RESOLVED ? "handler.resolve" : "handler.deny";
        String principal = SecurityContext.of(context).qualifiedIdentity();

        Optional<DurableHandler> found =
                await(store.findHandler(context.tenantId(), handlerName, correlationKey));
        if (found.isEmpty()) {
            // Unknown correlation key and another tenant's handler answer identically. Reporting the
            // second as a denial would let a caller enumerate another tenant's live correlation keys
            // by watching which refusal came back.
            return audited(context.tenantId(), principal, context.requestId(), action, correlationKey,
                    new HandlerTriggerOutcome.NotFound());
        }
        DurableHandler handler = found.get();

        Set<String> roles = context.roles().stream().map(Role::name).collect(Collectors.toUnmodifiableSet());
        if (!handler.authorization().satisfiedBy(roles, context.scopes())) {
            return audited(context.tenantId(), principal, context.requestId(), action,
                    handler.handlerId().toString(),
                    new HandlerTriggerOutcome.Unauthorized("the handler declares "
                            + handler.authorization().requiredRoles().size() + " role and "
                            + handler.authorization().requiredScopes().size()
                            + " scope requirements that this principal does not satisfy"));
        }

        if (target == HandlerStatus.RESOLVED) {
            // Checked here as well as in the store. This one produces the audited refusal with a
            // usable reason; the store's is the one that holds when a caller builds its own batch.
            Optional<String> refusal = handler.payloadSchema().rejectionOf(payload);
            if (refusal.isPresent()) {
                return audited(context.tenantId(), principal, context.requestId(), action,
                        handler.handlerId().toString(),
                        new HandlerTriggerOutcome.PayloadRefused(refusal.get()));
            }
        }

        return audited(context.tenantId(), principal, context.requestId(), action,
                handler.handlerId().toString(),
                commit(handler, context.requestId(), target, principal, payload));
    }

    // ---------------------------------------------------------------- durable write

    private HandlerTriggerOutcome commit(DurableHandler handler, String correlationId, HandlerStatus target,
                                         String actor, OpaquePayload payload) {
        for (int attempt = 1; ; attempt++) {
            StoredProcessInstance stored;
            try {
                stored = await(store.load(handler.key()));
            } catch (ExecutionStoreException absent) {
                if (absent.failure() instanceof ExecutionStoreFailure.NotFound) {
                    return new HandlerTriggerOutcome.NotFound();
                }
                throw absent;
            }
            UUID resumeTraversalId = UUID.randomUUID();
            String reentryNodeId = reentryNodeIdOf(stored.state(), handler);
            if (reentryNodeId == null) {
                // The invocation the handler named is gone, so there is no node to re-enter at. This
                // is the same class of fact as a stale work item: nothing to do, and nothing to
                // invent a node id for.
                return new HandlerTriggerOutcome.NotFound();
            }

            HandlerTransition transition = target == HandlerStatus.RESOLVED
                    ? new HandlerTransition.Resolved(handler.handlerId(), actorOrSystem(actor),
                            resumeTraversalId, payload)
                    : target == HandlerStatus.DENIED
                            ? new HandlerTransition.Denied(handler.handlerId(), actorOrSystem(actor),
                                    resumeTraversalId, payload)
                            : new HandlerTransition.Expired(handler.handlerId(), resumeTraversalId);

            ExecutionBatch batch = ExecutionBatch.to(handler.key())
                    .expecting(RevisionExpectation.exactly(stored.revision()))
                    // ACCEPTED, not RUNNING: this class commits the re-entry point, and whichever
                    // worker claims the trigger is the one that starts running it. Writing RUNNING
                    // here would claim work had begun in a process that may not be the one that runs
                    // it, or may not exist by then.
                    .apply(new ExecutionTransition.TraversalAdded(new Traversal(resumeTraversalId,
                            reentryNodeId, TraversalStatus.ACCEPTED, Map.of())))
                    .applyHandler(transition)
                    .publish(handlerEvent(handler, stored, resumeTraversalId, target, correlationId))
                    .build();

            try {
                await(store.apply(batch));
                return new HandlerTriggerOutcome.Accepted(handler.handlerId(), target, resumeTraversalId);
            } catch (ExecutionStoreException refused) {
                HandlerTriggerOutcome outcome = classify(refused, handler, attempt);
                if (outcome != null) {
                    return outcome;
                }
            }
        }
    }

    private HandlerTriggerOutcome commitEscalation(DurableHandler handler, String reason,
                                                   String correlationId) {
        for (int attempt = 1; ; attempt++) {
            StoredProcessInstance stored;
            try {
                stored = await(store.load(handler.key()));
            } catch (ExecutionStoreException absent) {
                if (absent.failure() instanceof ExecutionStoreFailure.NotFound) {
                    return new HandlerTriggerOutcome.NotFound();
                }
                throw absent;
            }
            ExecutionBatch batch = ExecutionBatch.to(handler.key())
                    .expecting(RevisionExpectation.exactly(stored.revision()))
                    .applyHandler(new HandlerTransition.Escalated(handler.handlerId(), reason))
                    .publish(handlerEvent(handler, stored, handler.traversalId(), HandlerStatus.ESCALATED,
                            correlationId))
                    .build();
            try {
                await(store.apply(batch));
                return new HandlerTriggerOutcome.Accepted(handler.handlerId(), HandlerStatus.ESCALATED, null);
            } catch (ExecutionStoreException refused) {
                HandlerTriggerOutcome outcome = classify(refused, handler, attempt);
                if (outcome != null) {
                    return outcome;
                }
            }
        }
    }

    /**
     * Maps a store refusal onto an outcome, or returns {@code null} to retry after a re-read.
     *
     * <p>{@link ExecutionStoreFailure.HandlerNotResolvable} is the duplicate and the late arrival at
     * once, and it is where a trigger that raced another one lands: the loser reads the winner's
     * terminal state and is refused deterministically rather than committing a second re-entry.</p>
     */
    private HandlerTriggerOutcome classify(ExecutionStoreException refused, DurableHandler handler,
                                           int attempt) {
        if (refused.failure() instanceof ExecutionStoreFailure.HandlerNotResolvable notResolvable) {
            return new HandlerTriggerOutcome.AlreadySettled(notResolvable.handlerId(),
                    notResolvable.current());
        }
        if (refused.failure() instanceof ExecutionStoreFailure.InvalidRequest invalid) {
            return new HandlerTriggerOutcome.PayloadRefused(invalid.reason());
        }
        if (refused.failure() instanceof ExecutionStoreFailure.ConcurrencyConflict
                && attempt < MAX_WRITE_ATTEMPTS) {
            return null;
        }
        if (refused.failure() instanceof ExecutionStoreFailure.NotFound) {
            return new HandlerTriggerOutcome.NotFound();
        }
        throw refused;
    }

    private EventEnvelope handlerEvent(DurableHandler handler, StoredProcessInstance stored,
                                       UUID traversalId, HandlerStatus target, String correlationId) {
        return EventEnvelope.of(UUID.randomUUID(), handler.key().tenantId(),
                HandlerEventData.eventTypeFor(target), handler.key().processInstanceId(), traversalId,
                handler.invocationId(), null, null, correlationId,
                stored.graphVersionPin().reference(), clock.instant(),
                // The handler identity, and nothing else. Every other level -- process, traversal,
                // invocation -- is already an envelope field, so a reader distinguishes all four
                // without any of them being inferred, and no outcome bytes reach the journal body.
                HandlerEventData.payload(handler.handlerId()));
    }

    private static String reentryNodeIdOf(ProcessInstance state, DurableHandler handler) {
        Traversal traversal = state.traversals().get(handler.traversalId());
        if (traversal == null) {
            return null;
        }
        NodeInvocation invocation = traversal.invocations().get(handler.invocationId());
        return invocation == null ? null : invocation.nodeId();
    }

    private static String actorOrSystem(String actor) {
        // An expiry has no actor, and HandlerTransition.Expired models that by carrying none. This
        // only ever sees a real principal for a resolution or a denial.
        return actor == null || actor.isBlank() ? "system" : actor;
    }

    private static String systemPrincipal() {
        return "ravenroot|SYSTEM|handler-scheduler";
    }

    private HandlerTriggerOutcome audited(String tenantId, String principal, String correlationId,
                                          String action, String resourceId, HandlerTriggerOutcome outcome) {
        auditTrail.append(AuditEnvelope.of(tenantId, principal, AuditCategory.APPROVAL, action,
                "execution-handler", resourceId,
                outcome.accepted() ? AuditOutcome.ALLOWED : AuditOutcome.DENIED,
                outcome.reason(), correlationId, clock.instant()));
        return outcome;
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof ExecutionStoreException store) {
                throw store;
            }
            if (wrapped.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw wrapped;
        }
    }
}
