package ai.ravenroot.api.node;

import ai.ravenroot.api.deployment.GraphDeployment;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.node.service.NodePackageServices;

/**
 * Opts a {@link NodeBehavior} into deployment-scoped inbound source lifecycle.
 *
 * <h2>An addition, not a replacement</h2>
 * <p>A behavior that also needs to poll, subscribe or listen implements this alongside
 * {@link NodeBehavior}, unchanged. {@link NodeBehavior#create} keeps building the per-message
 * {@code NodeAction} exactly as it does today, for the one-shot {@code startGraphMl} path and the
 * playground, neither of which this interface has any bearing on. {@link #createSource} is consulted
 * only when a node using this behavior is spawned inside a {@link GraphDeployment#start} — never at
 * package load, never at catalog registration, and never for a behavior used only outside a
 * deployment. A behavior that does not implement this interface is unaffected in every respect.</p>
 */
public interface InboundSourceCapable {
    /**
     * Builds this node's inbound source. Called once per node, at the same point in a deployment's
     * startup where {@link NodeBehavior#create} builds that node's action — configuration parsing and
     * any client construction belong here, not deferred into {@link InboundSource#start}, for the same
     * reason {@link NodeBehavior#create}'s own Javadoc gives.
     *
     * @param configuration this node's configuration — the same instance {@link NodeBehavior#create}
     *                       would receive for it
     * @param context        assembled by the deployment; see {@link InboundSourceContext} for why a
     *                       source never assembles one itself
     * @return a not-yet-started source owned by the deployment lifecycle
     */
    InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context);

    /**
     * SDK /2 construction path for a source that needs operator-composed package services.
     *
     * <p>The deployment supplies the same trusted {@code context} used by the legacy method and the
     * exact package-bound service view. The default bridge preserves already compiled SDK /1 source
     * behaviors and deliberately ignores services for them.</p>
     * @param configuration graph-validated configuration for the source node
     * @param context deployment-owned lifecycle and identity context
     * @param services capabilities explicitly granted to this package by the composition root
     * @return a source constructed with the supplied trusted dependencies, but not yet started
     */
    default InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context,
                                       NodePackageServices services) {
        return createSource(configuration, context);
    }
}
