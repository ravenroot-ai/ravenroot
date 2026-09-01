package ai.ravenroot.api.deployment;

import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.SecurityContext;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * The trusted API by which a long-lived inbound source starts a traversal from an unsolicited
 * external event (ADR 0021 D2; durable receipt PERS-INGRESS).
 *
 * <h2>Trusted means the caller has already established identity</h2>
 * <p>An inbound consumer — an IMAP poller, a queue subscriber, a socket listener — has no HTTP
 * request behind it, so it cannot inherit an identity from one. It must assert whose authority it is
 * acting under, and {@link SecurityContext} is mandatory here for exactly the reason it is mandatory
 * on {@code RavenrootApplication.startGraphMl}: there is deliberately no overload that omits it,
 * because an overload that minted an identity would be a supported way to run work as nobody
 * (SEC-07). This is not a simulated node invocation; it is a new traversal with a stated principal.
 *
 * <h2>What {@link #offer} provides, and has always provided</h2>
 * <p>Admission control, a bounded in-memory buffer, backpressure through a declared
 * {@link IngressOverflowPolicy}, and a disposition per offered event so the source knows what
 * happened and what to do next. {@link IngressDisposition#ACCEPTED} is in-memory custody only, exactly
 * as it always was — {@code offer} is unchanged, and every existing caller keeps the same
 * guarantee it always had.
 *
 * <h2>{@link #offerDurably}: a durable, idempotent boundary, and precisely how far it reaches</h2>
 * <p>ADR 0021 D6 settled the ack boundary as <em>durable traversal acceptance</em>: an ack, offset or
 * checkpoint may advance once, and only once, the event is durably recorded. {@link #offerDurably}
 * is that boundary, backed by {@link ai.ravenroot.api.persistence.ExecutionStore}'s inbox
 * ({@code recordInboxDelivery}) when this deployment is configured with one. Its receipt,
 * {@link IngressReceipt}, distinguishes refusal, volatile custody, durable commit, an already-admitted
 * duplicate and an ambiguous outcome that must be reconciled by re-offering the same key — see that
 * type for what each means and, most importantly, <b>what "durable" does not span.</b></p>
 *
 * <p><b>This boundary is single-pod.</b> It is exactly correct for one process holding one store, and
 * it says nothing about two. Distributed ownership with lease and fencing across replicas is
 * PERS-08; removing the single-replica constraint on top of it is PLAT-03. Neither is a
 * prerequisite for the guarantee this interface now makes — a receipt correct within one process does
 * not need cross-replica arbitration to be true — but running more than one replica against
 * independent stores before those guarantees exist turns "durably committed" into "committed at this pod", which is
 * at-least-once overall even though each pod individually never redelivers. {@link IngressReceipt}'s
 * own Javadoc repeats this because it is the fact a consumer author is most likely to read past.</p>
 *
 * <p>Without a configured store, {@link #offerDurably} degrades to exactly what {@link #offer} already
 * gave: {@link IngressReceipt.VolatileCustody} for an admitted event, {@link IngressReceipt.Refused}
 * otherwise. It never claims durability it cannot back.</p>
 *
 * <h2>What is still genuinely open</h2>
 * <p>Poison-event quarantine (a payload that fails every retry) is not addressed here and has no
 * mechanism in {@link ai.ravenroot.api.persistence.ExecutionStore}'s inbox to build one on; an adapter
 * that needs it must track its own attempt count today. And nothing here resumes a traversal that was
 * durably committed but interrupted mid-flight by a crash — that needs runtime writes routed through
 * the store (PERS-04/CORE-03), which does not exist yet. What {@link #offerDurably} guarantees across
 * a crash is narrower and still load-bearing: the fact of commitment is never lost, and a redelivered
 * copy of the same event is always recognised as a duplicate rather than re-admitted.</p>
 */
public interface TrustedIngress {
    /**
     * Offers an unsolicited external event to this deployment.
     *
     * <p>Synchronous and non-blocking with respect to graph execution: it returns as soon as the
     * event is admitted or refused, not when the traversal it starts completes. A source that needs
     * the outcome of the traversal is not doing ingress and should use the request/response path.
     *
     * @param security the principal the inbound source is acting under; mandatory, see the type
     *                 Javadoc — never synthesised on the caller's behalf
     * @param target   where the traversal begins. A parameter rather than an assumption so the
     *                 deployment can enforce its ingress topology (see {@link IngressTarget})
     * @param payload  the event body, carried as the same interior object model the rest of the
     *                 runtime uses
     * @return what the deployment did with the event; never {@code null}, and never an exception for
     *         the ordinary refusals — a full buffer is an expected operating condition, not a fault
     */
    IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload);

/**
 * The bound on the in-memory buffer. Fixed for the life of the deployment.
 * @return maximum events admitted into the deployment's volatile buffer.
 */
    int bufferCapacity();

/**
 * What happens at the bound. See {@link IngressOverflowPolicy} for why there is one choice.
 * @return fixed policy used when that buffer reaches capacity.
 */
    IngressOverflowPolicy overflowPolicy();

    /**
     * {@link #offer}, plus a durable, idempotent receipt an external source can ack, checkpoint or
     * recover against (PERS-INGRESS). See the type Javadoc for exactly what "durable" spans and does not.
     *
     * <p>Refusal is checked before any durable write is attempted, using the same admission
     * {@link #offer} uses — a deployment that is not ready or a full buffer never reaches the store,
     * so a {@link IngressReceipt.Refused} never has a durable side effect to reconcile.</p>
     *
     * <p>The default implementation preserves the legacy behavior: no store is
     * assumed configured, so it delegates to {@link #offer} and reports {@link IngressReceipt.VolatileCustody}
     * or {@link IngressReceipt.Refused} accordingly, never a durable outcome. An implementation backed
     * by a real store overrides this to actually commit.</p>
     *
     * @param sourceId      the calling source's own stable identity — a graph node id, ordinarily
     *                      (see {@code InboundSourceContext#nodeId()}) — used together with this
     *                      deployment's id as the durable dedup and checkpoint key's scope
     * @param idempotentKey the source's own dedup key for this event (a broker message id, an IMAP
     *                      UID, or similar) — stable across every redelivery of the same event and
     *                      distinct for every genuinely different one
 * @param security principal under which the source offers the event.
 * @param target graph entry point selected for the event.
 * @param payload event value in Ravenroot's runtime object model.
 * @return custody receipt that distinguishes refusal, volatile acceptance, and durable acceptance.
     */
    default IngressReceipt offerDurably(SecurityContext security, IngressTarget target, Object payload,
                                        String sourceId, String idempotentKey) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(idempotentKey, "idempotentKey");
        IngressDisposition disposition = offer(security, target, payload);
        return switch (disposition) {
            case ACCEPTED -> new IngressReceipt.VolatileCustody();
            case REJECTED_BUFFER_FULL -> new IngressReceipt.Refused("buffer full");
            case REJECTED_NOT_READY -> new IngressReceipt.Refused("not ready");
            case REJECTED_ADMISSION_CLOSED -> new IngressReceipt.Refused("admission closed");
            // Fail closed, in the receipt shape the caller already reads. Unreachable today --
            // see IngressDisposition.REJECTED_INSTANCE_BUSY -- and whether this case should instead
            // queue is the re-entry work's product decision, not one to settle here.
            case REJECTED_INSTANCE_BUSY ->
                    new IngressReceipt.Refused("process instance is already leased by another worker");
        };
    }

    /**
     * This source's durable position, for a restarted source to resume from (PERS-INGRESS). Keyed by this
     * deployment's id and {@code sourceId}, scoped to {@code security}'s tenant.
     *
     * <p>Independent of {@link #offerDurably}'s per-event dedup: a checkpoint says where to resume
     * <em>polling</em>, an idempotency record says whether one specific event was already committed.
     * The two are not written atomically against each other — see {@link IngressReceipt.Ambiguous} —
     * so a source recovering after a crash should trust the checkpoint for where to resume and the
     * per-event receipt (by re-offering) for whether any specific event in that range already landed.</p>
     *
     * <p>The default fails for every caller: without a configured, durable store there is nothing to
     * be durable about, and returning a fabricated always-zero cursor would let a caller believe it
     * had recovered when nothing was ever recorded.</p>
 * @param security tenant principal that owns the source checkpoint.
 * @param sourceId stable inbound-source identity within that tenant and deployment.
 * @return stage yielding the stored resume cursor, or failing when no durable store exists.
     */
    default CompletionStage<JournalCursor> sourceCheckpoint(SecurityContext security, String sourceId) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("No durable store is configured for this deployment"));
    }

    /**
     * Advances this source's checkpoint to {@code throughPosition}, compare-and-set against
     * {@code expected} (PERS-INGRESS) — see
     * {@link ai.ravenroot.api.persistence.ExecutionStore#advanceOutboxCursor} for the failure this
     * produces when another writer moved it first, which should not happen for a single, non-replicated
     * source but is surfaced rather than silently overwritten if it does.
 * @param expected cursor revision required for the checkpoint compare-and-set.
 * @param throughPosition source position durably acknowledged by this advance.
 * @return stage yielding the persisted cursor, or failing when no durable store exists.
     */
    default CompletionStage<JournalCursor> advanceSourceCheckpoint(JournalCursor expected, long throughPosition) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("No durable store is configured for this deployment"));
    }
}
