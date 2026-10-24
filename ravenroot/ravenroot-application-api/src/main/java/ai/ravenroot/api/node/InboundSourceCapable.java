package ai.ravenroot.api.node;

import ai.ravenroot.api.deployment.GraphDeployment;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.node.service.NodePackageServices;

/**
 * Opts a {@link NodeBehavior} into deployment-scoped inbound source lifecycle.
 *
 * <h2>Owned listeners and runtime-owned SOURCE emission</h2>
 * <p>A behavior implements this alongside {@link NodeBehavior}. An effective SOURCE starts its
 * listener only during validated deployment activation. Authenticated source-local ingress emits
 * the accepted input from that owning entry into its successors. Reaching a SOURCE from a parent
 * traversal settles that branch at the source boundary; its ordinary {@code NodeAction} is never
 * composed. This also applies to one-shot execution and the playground. A dual SOURCE/WORKER
 * descriptor may permit an explicit WORKER override, which uses the ordinary action instead.</p>
 * <p>{@link #createSource} runs during deployment activation, never at package load, catalog
 * registration or a one-shot Test. A behavior without this interface has no listener lifecycle.</p>
 */
public interface InboundSourceCapable {
    /**
     * Closed codes this trusted source implementation may expose when startup is refused.
     * Undeclared codes are deliberately projected as a generic incident by the core.
     * @return immutable declared public code set; empty by default
     */
    default java.util.Set<String> sourceStartFailureCodes() {
        return java.util.Set.of();
    }

    /**
     * Builds this node's inbound source once per owned listener activation, after graph validation
     * and before the listener's readiness stage completes. Configuration parsing and client
     * construction belong here; readiness and listener startup belong in {@link InboundSource#start}.
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
