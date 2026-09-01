package ai.ravenroot.api.ingress;

import ai.ravenroot.api.execution.CancellationSignal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The runtime-issued handling window of one admitted managed-ingress request.
 *
 * <h2>Why a handler is given these two values and nothing else</h2>
 * <p>Before this context existed, a handler had no way to know how long it was allowed to run, and no
 * way to learn that the answer it was computing had stopped being wanted. Both facts were held by the
 * managed adapter alone, so the only observable consequence of a request timing out or of its route
 * being retired was that the handler kept working and its result was silently discarded. The two
 * members here are exactly the two facts, and nothing more: no exchange, no connection, no listener,
 * no route identity a handler could use to widen what it was granted.</p>
 *
 * <h2>Why the values are runtime-owned</h2>
 * <p>{@code deadline} is derived from the operator-approved
 * {@link IngressAuthorityDeclaration#requestTimeout()} of the package's authority, and the cancellation
 * signal is owned by the managed adapter. The operator's deployment configuration <em>does</em> name the
 * timeout — it is the value they set, bounded by
 * {@link IngressAuthorityDeclaration#HARD_MAX_TIMEOUT} — but graph data and package code at runtime name
 * neither the deadline nor that ceiling: there is no public path that builds a context and feeds it back
 * into admission, so a package can read its window but cannot select or extend it. The record is
 * constructible — the way {@code RequestReplyContext} is — because a test needs to build one;
 * building one grants nothing, since the adapter never accepts a context as input.</p>
 *
 * <h2>Which thread a cancellation listener runs on</h2>
 * <p>Do not assume. The managed adapter deliberately runs listeners on a thread of its own that is
 * neither the one serving the exchange nor the one performing a retirement, because a listener that
 * blocks must not be able to extend a deadline or stall a shutdown budget that belongs to the adapter.
 * A listener may therefore run concurrently with, or after, the handler stage it was registered from,
 * and must do its own synchronisation.</p>
 *
 * <h2>One deadline, not one per stage</h2>
 * <p>The same absolute instant governs admission, the bounded request-body read, handler execution and
 * response delivery. A handler that propagates it downstream — as an HTTP request timeout, a database
 * statement timeout, a nested Ravenroot deadline — is propagating the value the adapter itself
 * enforces, so the downstream call cannot outlive the request that caused it.</p>
 *
 * <h2>Precision of {@link #remaining()}</h2>
 * <p>The adapter enforces the deadline on a monotonic clock, so a wall-clock adjustment cannot shorten
 * or extend a request already in flight. {@code deadline} and {@link #remaining()} are the wall-clock
 * projection of that instant, published so it can be logged and propagated. They are therefore
 * advisory: treat them as the budget, and treat cancellation as the authority on whether the work is
 * still wanted.</p>
 *
 * @param deadline the absolute instant at which this request stops being answerable
 * @param cancellation fires at most once, on the first of request timeout, route retirement,
 *                     deployment stop or rollback, and an observable client disconnect
 */
public record IngressRequestContext(Instant deadline, CancellationSignal cancellation) {
/**
 * Requires runtime-issued deadline and cancellation signal.
 */
    public IngressRequestContext {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * The budget still available, floored at {@link Duration#ZERO}.
     *
     * <p>Zero means the deadline has passed and the adapter will refuse the response; it does not mean
     * the handler is required to stop, only that continuing is wasted work.</p>
 * @return non-negative advisory time remaining before the request becomes unanswerable.
     */
    public Duration remaining() {
        Duration left = Duration.between(Instant.now(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

/**
 * Whether the work is still wanted: neither past its deadline nor cancelled.
 * @return whether the deadline has not passed and cancellation has not fired.
 */
    public boolean live() {
        return !cancellation.cancelled() && !remaining().isZero();
    }
}
