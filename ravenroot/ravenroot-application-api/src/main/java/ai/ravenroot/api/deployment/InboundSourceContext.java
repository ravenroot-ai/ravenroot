package ai.ravenroot.api.deployment;

import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import java.util.Optional;

/**
 * What a deployment hands an {@link InboundSource} at every lifecycle hook (ADR 0021 D2
 * follow-up).
 *
 * <h2>Security-relevant: runtime provenance, identity and lifetime are the authority</h2>
 * <p>This public interface can be implemented by plugins, tests and embedding code, but implementing
 * it does not mint runtime authority. The core issues the production context passed to
 * {@code createSource}, registers that exact object for its package, deployment, node and activation
 * generation, and recognizes it by object identity at managed-service boundaries. Unregistered,
 * foreign and retired contexts are refused. Its ingress and request/reply facades are likewise bound
 * to that source activation, so retaining a context after stop, rollback, restart or undeploy cannot
 * reach the replacement generation. A caller-created implementation may carry a
 * {@link SecurityContext} or a {@link TrustedIngress} of its own, but the core never treats those
 * values as proof that it issued the context.</p>
 *
 * <h2>Reporting health is the source's job, not a poll</h2>
 * <p>Nothing here is asked "are you healthy?" on a timer. A source that detects it can no longer do
 * its job — a broker connection dropped, a mailbox login started failing — calls
 * {@link #reportDegraded(String)} the moment it knows, and {@link #reportHealthy()} the moment
 * service resumes. The deployment does not infer either from silence.</p>
 */
public interface InboundSourceContext {
/**
 * This source's deployment. Stable for the source's whole life, including across a restart.
 * @return stable identity of the deployment that owns this source.
 */
    DeploymentId deploymentId();

/**
 * The graph node this source was created for, from that node's own {@code NodeConfiguration}.
 * @return graph node for which the deployment created this source.
 */
    String nodeId();

    /**
     * The identity this deployment was started under (SEC-07). Never one the source supplies —
     * see the type Javadoc — and never synthesised: it is exactly the {@link SecurityContext} the
     * caller of {@link GraphDeployment#start} passed.
 * @return security identity that activated the owning deployment.
     */
    SecurityContext identity();

/**
 * This source activation's trusted inbound surface. It may be a distinct facade from
 * {@link GraphDeployment#ingress()} because it is fenced to this context's activation and lifetime;
 * after the context is retired it refuses new work. The caller still supplies the
 * {@link SecurityContext} required by {@link TrustedIngress}.
 * @return source-scoped, deployment-owned inbound facade available to this source.
 */
    TrustedIngress ingress();

    /**
     * This source generation's request/reply capability, bound to {@link #identity()}.
     *
     * <p>The default is deny-only for SDK /1 binary compatibility. A runtime that supports live
     * request/reply overrides it with a generation-fenced view.</p>
 * @return generation-fenced request/reply facade, or the deny-only compatibility default.
     */
    default RequestReplyIngress requestReply() { return RequestReplyIngress.unsupported(); }

/**
 * Optional managed HTTP route capability, already bound to this source's trusted identity.
 * @return source-bound managed-route authority when HTTP ingress is enabled.
 */
    default Optional<IngressRouteAuthority> ingressRoutes() { return Optional.empty(); }

    /**
     * Reports that this source can no longer do its job, without having stopped.
     *
     * @param sanitizedReason an already operator-safe summary — same discipline
     *                        {@link DeploymentStatus}'s own cause field documents. Never a raw
     *                        exception message, a credential, or content the source observed.
     */
    void reportDegraded(String sanitizedReason);

    /** Reports that a previously degraded source is serving normally again. Idempotent. */
    void reportHealthy();
}
