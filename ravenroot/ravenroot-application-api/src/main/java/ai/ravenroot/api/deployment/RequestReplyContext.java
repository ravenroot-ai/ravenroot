package ai.ravenroot.api.deployment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The runtime-issued metadata one {@link RequestReplyProjection} may read while building its payload.
 *
 * <h2>Why the projection sees these values at all</h2>
 * <p>A caller that wants the traversal's own correlation or traversal identifier <em>inside</em> the
 * request payload previously had two bad options: invent an identifier and hope the runtime adopted
 * it, or accept an exchange and then have no way to amend the payload already dispatched. Both make
 * the caller the author of identity. This context is the third option — the runtime allocates,
 * publishes, and the caller only reads.</p>
 *
 * <h2>Why it is a value, not a builder</h2>
 * <p>Every component is final and runtime-owned. The record offers no setter, no {@code with…}
 * copier and no constructor a graph or extension could reach with values of its own choosing, so
 * "correlation, process, traversal, deadline and deployment-generation are not caller-selectable" is
 * a property of the type rather than a rule the coordinator has to keep enforcing. The identifiers
 * observed here are exactly the ones the returned {@link RequestReplyExchange} reports.</p>
 *
 * <p>A projection sees this context only after admission has already succeeded and capacity has been
 * reserved, and always before the traversal is dispatched.</p>
 *
 * @param binding the tenant/deployment/generation/node fence this request was admitted under
 * @param target where the traversal will begin — the value the caller passed, echoed back so a
 *               projection shared between targets need not close over it
 * @param correlationId the runtime-issued correlation identity of the live waiter
 * @param processInstanceId the runtime-issued process instance the traversal will run as
 * @param traversalId the runtime-issued traversal identity
 * @param deadline the absolute finite deadline already validated against the operator ceiling
 */
public record RequestReplyContext(RequestReplyBinding binding, IngressTarget target,
                                  UUID correlationId, UUID processInstanceId, UUID traversalId,
                                  Instant deadline) {
/**
 * Requires every runtime-issued identifier and the already-admitted request deadline.
 */
    public RequestReplyContext {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(processInstanceId, "processInstanceId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(deadline, "deadline");
    }
}
