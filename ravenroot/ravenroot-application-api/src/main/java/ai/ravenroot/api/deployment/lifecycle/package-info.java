/**
 * The engine- and transport-neutral vocabulary for deployment lifecycle commands (ADR 0038).
 *
 * <h2>What this package is</h2>
 * <p>Two sealed hierarchies and nothing else: {@link
 * ai.ravenroot.api.deployment.lifecycle.LifecycleCommand}, the eight things an operator can ask of a
 * deployment, and {@link ai.ravenroot.api.deployment.lifecycle.DeploymentCommandOutcome}, the nine
 * answers it can receive. They are values. They carry no behaviour, reach no runtime, and do not
 * decide how a command is transported, authorized, or applied.
 *
 * <h2>An outcome is returned, never thrown</h2>
 * <p>Every member of the outcome hierarchy is an answer to the question the caller asked, including
 * the refusals. A stale generation, an idempotency conflict, and a command that arrived after a
 * shutdown intent are all normal, expected results of asking; making them exceptions would put the
 * routine answers of this contract on the path reserved for the ones nobody planned for, and would
 * lose the compiler's exhaustiveness check exactly where a caller most needs it.
 * {@code DeploymentRegistry.RegistryException} keeps its role unchanged: it reports that the
 * <em>store</em> failed, which is a different kind of event from the lifecycle answering "no".
 *
 * <h2>What is deliberately not in this hierarchy</h2>
 * <p>{@code Unauthorized}, {@code Fenced}, and {@code LeaseLost} are not outcomes a client of a
 * lifecycle command may observe. The first is an authorization answer that belongs to the surface
 * that authenticated the caller and must not be inferable from a lifecycle reply; the other two
 * describe the relationship between an <em>internal</em> owner and the authority, which a client
 * neither holds nor can act on. A coordinator that loses its fence retries or steps down; it does
 * not tell the operator that it did.
 *
 * <h2>Scope of this wave</h2>
 * <p>These types are the accepted contract. No coordinator, adapter, HTTP route, or CLI verb
 * produces or consumes them yet, and this package makes no claim that one does — the same rule ADR
 * 0023 and ADR 0030 state for themselves. The externally published surface is unchanged.
 */
package ai.ravenroot.api.deployment.lifecycle;
