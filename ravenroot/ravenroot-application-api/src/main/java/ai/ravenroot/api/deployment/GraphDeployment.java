package ai.ravenroot.api.deployment;

import ai.ravenroot.api.security.SecurityContext;

import java.util.concurrent.CompletionStage;

/**
 * A long-lived graph deployment: the unit of ownership for graphs that run continuously and accept
 * unsolicited inbound events (ADR 0021 D2).
 *
 * <h2>What this is not</h2>
 * <p>Not the path a one-shot submission takes. {@code RavenrootApplication.startGraphMl} keeps
 * today's behaviour on the shared host engine — a fresh runner per submission, torn down on
 * completion — and the playground is deliberately unchanged. A deployment is for a graph that must
 * be running before any request arrives, because something outside is going to push events into it.
 *
 * <h2>Lifecycle</h2>
 * <p>{@link #start} takes the deployment from {@link DeploymentState#COLD} through
 * {@link DeploymentState#STARTING} to {@link DeploymentState#READY}, and <b>completes only at
 * readiness</b>: a caller whose stage has completed has a deployment that is actually serving, not
 * one that has begun trying. A start that fails part-way <b>rolls back atomically</b> — no half-bound
 * listeners, no orphaned actors — and reports {@link DeploymentState#FAILED} with a sanitized cause.
 *
 * <p>{@link #start}, {@link #stop} and {@link #restart} are <b>asynchronous, idempotent and
 * single-flight</b>. Concurrent callers of the same operation observe the same in-flight stage rather
 * than racing to duplicate the work; calling {@code start} on a {@link DeploymentState#READY}
 * deployment completes immediately with its current status. {@code restart} must not leave duplicated
 * subscriptions behind: it is a stop that completes before a start begins, not the two overlapping.
 *
 * <p>{@link #stop} closes admission first, then drains what was already accepted within a bound, then
 * releases listeners, actors and other resources. Admission does not reopen.
 *
 * <h2>Identity is mandatory at activation</h2>
 * <p>{@link #start} requires a {@link SecurityContext} and there is no overload without one, for the
 * same reason {@code RavenrootApplication.startGraphMl} has none (SEC-07): a deployment that ran as
 * nobody would give every event it ingests an authority nobody granted.
 *
 * <h2>Scaling: what is claimed, and what is not</h2>
 * <p>Recorded here rather than only in ADR 0021 D3, because this is where the expectation forms and
 * it is the first one to break in production.
 *
 * <p><b>Aggregate throughput scales near-linearly with pods until a shared bottleneck.</b> More pods
 * means more items finishing per unit time, which is the objective.
 *
 * <p><b>Per-item speedup is bounded by the graph's own dependency structure.</b> Fan-out branches
 * parallelise; a deep sequential chain does not go faster on twenty pods than on one, because each
 * node still waits for its predecessor. Adding pods does not shorten a chain.
 *
 * <p>And node-granular cross-pod placement would make a single item <em>slower</em>, not faster: it
 * adds a serialization and a network hop to every edge that crosses a pod. Distribution is a
 * footprint and capacity facility. It is never a latency claim.
 *
 * <h2>Durability is not provided in Phase A</h2>
 * <p>Checkpointing, deduplication, at-least-once delivery and the acknowledgement boundary are open
 * and not claimed. See {@link TrustedIngress} for what that means for an inbound adapter deciding
 * when to acknowledge — the consequence lands there, and getting it wrong loses data silently.
 */
public interface GraphDeployment {
/**
 * Stable identity. See {@link DeploymentId} for what is deliberately not decided about it.
 * @return process-scoped deployment identity.
 */
    DeploymentId id();

/**
 * The current observable status. Cheap, non-blocking, safe to poll from a readiness probe.
 * @return current non-blocking lifecycle observation.
 */
    DeploymentStatus status();

    /**
     * Starts the deployment and completes when it is {@link DeploymentState#READY}.
     *
     * @param security the principal activating the deployment; mandatory (SEC-07)
     * @return the status at readiness, or a stage completed exceptionally if startup failed after
     *         rolling back
     */
    CompletionStage<DeploymentStatus> start(SecurityContext security);

/**
 * Closes admission, drains, releases resources. Idempotent and single-flight.
 * @return stage completing after admission closes and resources are released.
 */
    CompletionStage<DeploymentStatus> stop();

/**
 * A completed {@link #stop} followed by a {@link #start}. Never overlapping, never duplicating.
 * @param security principal authorized to activate the replacement lifecycle.
 * @return stage completing with the restarted deployment status.
 */
    CompletionStage<DeploymentStatus> restart(SecurityContext security);

    /**
     * The trusted inbound surface.
     *
     * <p>Available whatever the state — a source may hold this reference across a restart — but it
     * admits events only while {@link DeploymentState#READY}, and reports the refusal rather than
     * throwing. Read {@link TrustedIngress}'s durability section before deciding when to ack.
 * @return trusted ingress facade that reports admission refusal instead of throwing.
     */
    TrustedIngress ingress();

    /**
     * The live request/reply surface for this deployment.
     *
     * <p>The default is deny-only so a deployment implementation compiled before this additive
     * method existed remains binary-linkable and fails closed rather than fabricating capability.</p>
 * @return live request/reply facade, or the deny-only compatibility default.
     */
    default RequestReplyIngress requestReply() {
        return RequestReplyIngress.unsupported();
    }
}
