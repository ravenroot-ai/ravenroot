package ai.ravenroot.api.deployment;

import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.ingress.IngressRouteAuthority;
import java.util.Optional;

/**
 * What a deployment hands an {@link InboundSource} at every lifecycle hook (ADR 0021 D2
 * follow-up).
 *
 * <h2>Security-relevant: this is the only channel, and it is one-way</h2>
 * <p>A plugin never constructs one of these. Every implementation of this interface is a private
 * inner class of the {@link GraphDeployment} that owns it, so the only way a third-party
 * {@code NodeBehavior} ever sees a {@link SecurityContext} paired with a live {@link TrustedIngress}
 * is as the parameter this interface's methods are called with. There is no factory, no builder and
 * no static accessor anywhere in the SDK a plugin depends on that produces one; nothing in
 * {@code ai.ravenroot.api.node} returns a {@link TrustedIngress} or a {@link SecurityContext} at all.
 * A plugin that could mint its own context — or reach a {@link TrustedIngress} belonging to a
 * deployment it was never given — would manufacture deployment authority rather than receive it.
 * {@code createSource} receiving this as a parameter,
 * rather than a source reaching out to obtain one, is the whole of the enforcement.</p>
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
 * This deployment's trusted inbound surface. See {@link TrustedIngress} before deciding when to ack.
 * @return deployment-owned inbound facade available to this source.
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
