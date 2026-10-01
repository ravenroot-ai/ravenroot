package ai.ravenroot.core.runtime;

import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.core.graph.GraphNode;

/** Trusted core counterpart of an SDK package's inbound-source capability. */
public interface CoreInboundSourceFactory extends NodeBehaviorFactory {
    /** Creates the deployment-scoped source for one configured graph node. */
    InboundSource createSource(GraphNode node, InboundSourceContext context);

    /** Sanitized startup failure codes this core source may publish. */
    default java.util.Set<String> sourceStartFailureCodes() {
        return java.util.Set.of();
    }
}
