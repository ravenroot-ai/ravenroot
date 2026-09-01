package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphNode;

/** Creates one handler per graph node so configuration and state are never shared accidentally. */
public interface NodeBehaviorFactory {
    NodeTypeDescriptor descriptor();

    NodeHandler create(GraphNode node);
}
