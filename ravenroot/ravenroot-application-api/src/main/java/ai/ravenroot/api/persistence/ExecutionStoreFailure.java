package ai.ravenroot.api.persistence;

import java.util.UUID;

/**
 * The single classification vocabulary every execution-store adapter must use (ADR 0010 section 12).
 *
 * <p>A shared, closed taxonomy is what makes the conformance suite falsifiable: without it each
 * adapter would throw its own exception type and the core could not classify anything.</p>
 *
 * <p>Two pairs must never be collapsed. {@link FencedOut} and {@link ConcurrencyConflict} demand
 * opposite responses — abandon immediately versus re-read and retry. {@link Unavailable} and
 * {@link OutcomeUnknown} differ on whether the write is known not to have applied; misreporting the
 * second as the first asserts an absence of effect that the adapter cannot actually observe.</p>
 */
public sealed interface ExecutionStoreFailure {

/**
 * Returns whether retrying this classified store failure can be meaningful.
 * @return retry guidance for callers and adapters.
 */
    Retryability retryability();

/**
 * Human-readable diagnosis. Never contains payload bytes or secrets.
 * @return payload-safe human-readable diagnosis of this failure.
 */
    String describe();

/**
 * The stored revision did not match the caller's {@link RevisionExpectation}.
 * @param key the stable key used to identify the requested resource.
 * @param expected revision required for an optimistic update.
 * @param actual revision observed by the store.
 */
    record ConcurrencyConflict(ExecutionKey key, RevisionExpectation expected, long actual)
            implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.RETRY_AFTER_REREAD;
        }

        @Override
        public String describe() {
            return "revision expectation " + expected + " not met for " + key.processInstanceId()
                    + "; stored revision is " + actual + " (re-read, then retry)";
        }
    }

    /**
     * The presented fencing token is not the current token. The rejection rule is inequality, not
     * "lower than": rejecting only lower tokens leaves a hole in which a caller presenting an
     * unissued higher token fences out the legitimate owner.
 * @param key the stable key used to identify the requested resource.
 * @param presentedToken the stable presented token used to identify the requested resource.
 * @param currentToken the stable current token used to identify the requested resource.
     */
    record FencedOut(ExecutionKey key, long presentedToken, long currentToken)
            implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "fencing token " + presentedToken + " is not the current token " + currentToken
                    + " for " + key.processInstanceId() + " (stop working on this instance)";
        }
    }

    /**
     * A lease the caller <strong>held</strong> expired or was revoked. Kept distinct from
     * {@link FencedOut} because the handling is the same but the diagnosis is not: operators need to
     * distinguish "it expired" from "someone else took it".
     *
     * <p>Never used for failing to <em>acquire</em> a lease — that is {@link LeaseHeldByAnother}.
     * Losing ownership is rare and serious: there may be in-flight side effects to abandon and the
     * caller must stop immediately.</p>
 * @param key the stable key used to identify the requested resource.
 * @param workerId the stable worker id used to identify the requested resource.
     */
    record LeaseLost(ExecutionKey key, String workerId) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "lease held by " + workerId + " on " + key.processInstanceId()
                    + " expired or was revoked (stop working on this instance)";
        }
    }

    /**
     * A lease the caller tried to <strong>acquire</strong> is validly held by another worker.
     *
     * <p>Separate from {@link LeaseLost} because of frequency, not because it is hard to tell them
     * apart: in a busy multi-worker deployment ordinary contention dominates by orders of magnitude,
     * so merging the two would bury the rare critical signal in routine noise and make an alert on
     * {@code LeaseLost} unusable exactly when it matters. Nothing was started and nothing is at
     * risk.</p>
     *
     * @param expiresAt when the holder's lease lapses, so a caller can retry when retrying is
     *                  sensible instead of busy-looping, and an operator can spot a stuck holder
     *                  without a second query
 * @param key the stable key used to identify the requested resource.
 * @param holderWorkerId the stable holder worker id used to identify the requested resource.
     */
    record LeaseHeldByAnother(ExecutionKey key, String holderWorkerId, java.time.Instant expiresAt)
            implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.RETRYABLE_NO_EFFECT;
        }

        @Override
        public String describe() {
            return "lease on " + key.processInstanceId() + " is held by " + holderWorkerId
                    + " until " + expiresAt + " (nothing was started; retry after it lapses)";
        }
    }

/**
 * Absent, or not visible to this tenant. The two are deliberately indistinguishable.
 * @param key the stable key used to identify the requested resource.
 */
    record NotFound(ExecutionKey key) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "no process instance " + key.processInstanceId() + " visible to this tenant";
        }
    }

/**
 * A {@link RevisionExpectation.NotPresent} expectation was made against an existing instance.
 * @param key the stable key used to identify the requested resource.
 * @param revision revision assigned to the durable join.
 */
    record AlreadyExists(ExecutionKey key, long revision) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "process instance " + key.processInstanceId() + " already exists at revision " + revision;
        }
    }

/**
 * The same idempotency key was replayed with a different request fingerprint.
 * @param idempotencyKey the stable idempotency key used to identify the requested resource.
 */
    record IdempotencyConflict(String idempotencyKey) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "idempotency key " + idempotencyKey + " was recorded with a different request fingerprint";
        }
    }

    /**
     * The key was retained for its declared window, then forgotten, and the store can no longer
     * answer. Never a silent re-execution: without this member, expiry quietly degrades
     * exactly-once into at-least-once with nothing to observe.
 * @param idempotencyKey the stable idempotency key used to identify the requested resource.
     */
    record IdempotencyRecordExpired(String idempotencyKey) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.INDETERMINATE;
        }

        @Override
        public String describe() {
            return "idempotency key " + idempotencyKey
                    + " outlived its declared retention window; the store cannot say whether it was applied";
        }
    }

    /**
     * A contract violation <strong>decidable from the request alone</strong>, without reading stored
     * state. This always indicates a caller bug a developer must fix.
     *
     * <p>Never used for a condition that depends on what is stored. Whether an instance exists is
     * state, and its absence is frequently a legitimate race rather than a defect, so that is
     * {@link NotFound}; classifying a race as a bug would poison the one signal this member carries.</p>
 * @param reason machine-readable reason for the store failure.
     */
    record InvalidRequest(String reason) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "invalid request: " + reason;
        }
    }

/**
 * Exceeds the adapter's declared maximum payload size. Operator-fixable, not caller-fixable.
 * @param actualBytes payload size that exceeded the store limit.
 * @param limitBytes maximum payload size accepted by the store.
 */
    record PayloadTooLarge(int actualBytes, int limitBytes) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "payload of " + actualBytes + " bytes exceeds the adapter limit of " + limitBytes;
        }
    }

/**
 * Optional behaviour this adapter does not implement, declared through {@link StoreCapability}.
 * @param capability capability unavailable from the adapter.
 */
    record CapabilityNotSupported(StoreCapability capability) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "this adapter does not declare " + capability;
        }
    }

    /**
     * The store connection's own authorization failed. Never used for tenant scoping of a row:
     * tenant scoping is {@link NotFound}.
 * @param reason machine-readable reason for the store failure.
     */
    record NotAuthorized(String reason) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "the store connection is not authorized: " + reason;
        }
    }

/**
 * Transiently unreachable, busy or locked, and <em>definitely not applied</em>.
 * @param reason machine-readable reason for the store failure.
 */
    record Unavailable(String reason) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.RETRYABLE_NO_EFFECT;
        }

        @Override
        public String describe() {
            return "store temporarily unavailable, nothing was applied: " + reason;
        }
    }

    /**
     * The operation failed after the write may or may not have applied. A distributed adapter cannot
     * function without this member: a commit that times out mid-flight is neither applied nor
     * not-applied from the caller's view, and reporting it as {@link Unavailable} would assert an
     * absence of effect the adapter cannot observe.
 * @param key the stable key used to identify the requested resource.
 * @param reason machine-readable reason for the store failure.
     */
    record OutcomeUnknown(ExecutionKey key, String reason) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.INDETERMINATE;
        }

        @Override
        public String describe() {
            return "outcome unknown for " + key.processInstanceId() + ": " + reason
                    + " (re-read the revision or replay under the idempotency key)";
        }
    }

    /**
     * Stored state did not reconstruct into a legal aggregate. This is the observable consequence of
     * the {@code ProcessInstance} revalidation hazard and must never be swallowed or retried.
 * @param key the stable key used to identify the requested resource.
 * @param reason machine-readable reason for the store failure.
     */
    record Corrupted(ExecutionKey key, String reason) implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "stored state for " + key.processInstanceId()
                    + " did not reconstruct into a legal aggregate: " + reason;
        }
    }

    /**
     * The journal no longer retains the offset a reader asked to continue from (ADR 0011, PERS-07).
     *
     * <p>This member exists so that retention has a <strong>signal</strong> rather than a silent
     * consequence. The alternative — returning whatever survives from the requested offset — hands
     * the caller a stream with a hole in it that looks exactly like a complete one, and a projection
     * built from it is quietly wrong forever. That is not hypothetical: it is the behaviour of the
     * in-memory SSE history this journal replaces, where a reconnecting client resuming past the end
     * of a 2048-entry ring receives a short, gap-bearing replay and no indication that anything was
     * lost.</p>
     *
     * <p>{@link Retryability#DETERMINISTIC_REJECT} rather than anything retryable: the events are
     * gone, so the identical request fails identically forever. The caller's recourse is to resync
     * from current state and resume at {@link #retainedFrom()}, not to retry.</p>
     *
     * @param retainedFrom the lowest offset the journal still holds, so a caller is told where it
     *                     <em>can</em> resume instead of having to probe for it
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param requestedAfterOffset exclusive journal offset requested by the reader.
     */
    record JournalTruncated(String tenantId, long requestedAfterOffset, long retainedFrom)
            implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "the journal of tenant " + tenantId + " no longer retains offsets after "
                    + requestedAfterOffset + "; the earliest retained offset is " + retainedFrom;
        }
    }

    /**
     * A publisher tried to advance an outbox cursor that another publisher has already moved
     * (ADR 0011, PERS-07).
     *
     * <p>A distinct member rather than a {@link ConcurrencyConflict}, and the reason is the same one
     * ADR 0010 section 13.2 gave for refusing {@code FencedOut(current = 0)} on a nonexistent
     * instance: {@code ConcurrencyConflict} carries an {@link ExecutionKey}, and a cursor advance has
     * no process instance to name. Reporting one would mean fabricating a real-looking key — a nil
     * UUID, or the instance of whichever event happened to be last — for an entity that was never
     * part of the request, and that value then reaches {@code describe()} and the logs. The
     * diagnostic an operator actually needs here is <em>which destination</em>, which this member
     * carries and {@code ConcurrencyConflict} has nowhere to put.</p>
     *
     * <p>{@link Retryability#RETRY_AFTER_REREAD}, identically to {@code ConcurrencyConflict}: nothing
     * was applied, but the request is stale, so the publisher must re-read the cursor before trying
     * again. A blind retry of an advance derived from a stale position would loop forever, and — far
     * worse — an advance that succeeded from a stale position would skip past events that this
     * publisher never delivered, which is a silent loss rather than a duplicate.</p>
 * @param tenantId the stable tenant id used to identify the requested resource.
 * @param destination journal destination whose cursor is updated.
 * @param expected revision required for an optimistic update.
 * @param actual revision observed by the store.
     */
    record OutboxCursorConflict(String tenantId, String destination, long expected, long actual)
            implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.RETRY_AFTER_REREAD;
        }

        @Override
        public String describe() {
            return "outbox cursor for destination " + destination + " of tenant " + tenantId
                    + " was expected at " + expected + " but stands at " + actual;
        }
    }

    /**
     * A handler transition was applied to a handler whose stored state does not permit it (PERS-05).
     *
     * <p>This is the single refusal behind "duplicate, late, cross-tenant and unauthorized
     * resolutions are refused deterministically". A second resolution, a resolution after an expiry
     * and a denial after a resolution all reach it, because all three are the same fact: the handler
     * is no longer in a state that accepts the transition. It is decided from stored state alone, so
     * it is answered identically on every retry and across a restart — which is what
     * {@link Retryability#DETERMINISTIC_REJECT} claims and what a deduplication window with a
     * retention period could not have promised.</p>
     *
     * <p>Deliberately <em>not</em> {@link ConcurrencyConflict}: that value invites the caller to
     * re-read and retry, and here the answer after re-reading is the same refusal forever. A caller
     * that retried this one would loop.</p>
 * @param handlerId the stable handler id used to identify the requested resource.
 * @param current stored handler state at the moment of the refusal.
 * @param requested state the refused transition asked for.
     */
    record HandlerNotResolvable(UUID handlerId, HandlerStatus current, HandlerStatus requested)
            implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "handler " + handlerId + " is " + current + " and cannot transition to " + requested;
        }
    }

    /**
     * A handler registration reused a correlation key that another live handler already holds
     * (PERS-05).
     *
     * <p>Correlation keys are unique per {@code (tenantId, name, correlationKey)} across handlers
     * that are not yet terminal, because a trigger presenting one must resolve to exactly one
     * handler. Two live handlers sharing a key would make an inbound trigger's target depend on
     * iteration order — a nondeterministic answer to an authorization-bearing question.</p>
     *
     * <p>Terminal handlers do not participate, so a correlation key becomes reusable once the wait it
     * named is over. The key is not echoed into {@link #describe()} beyond its own text, which is
     * caller-supplied business identity rather than payload: it is bounded and control-free by
     * {@link HandlerRegistration}, and an operator cannot act on this without seeing which key
     * collided.</p>
 * @param handlerName opaque handler name whose correlation namespace was contended.
 * @param correlationKey correlation key already held by a live handler.
     */
    record HandlerCorrelationTaken(String handlerName, String correlationKey)
            implements ExecutionStoreFailure {
        @Override
        public Retryability retryability() {
            return Retryability.DETERMINISTIC_REJECT;
        }

        @Override
        public String describe() {
            return "handler " + handlerName + " already has a live registration for correlation key "
                    + correlationKey;
        }
    }

/**
 * Convenience for adapters that reject a batch before any key context exists.
 * @param reason machine-readable reason for the store failure.
 * @return invalid-request failure carrying the supplied machine-readable reason.
 */
    static ExecutionStoreFailure invalid(String reason) {
        return new InvalidRequest(reason);
    }

/**
 * Convenience for timer and work-item identities that no longer resolve.
 * @param workItemId the stable work item id used to identify the requested resource.
 * @return failure identifying the claim acknowledgement's unknown work item.
 */
    static ExecutionStoreFailure unknownWorkItem(UUID workItemId) {
        return new InvalidRequest("unknown work item " + workItemId);
    }
}
