package ai.ravenroot.api.node;

import ai.ravenroot.api.node.service.NodeExternalIoCapacity;
import ai.ravenroot.api.node.service.NodePackageServices;

/**
 * Closed opt-in for a behavior whose graph-bound quantitative I/O policy must be resolved once.
 *
 * <p>The runtime calls {@link #resolveExecutionIoCapacity} during admission, persists the result,
 * and supplies that exact value to {@link #create}. Implementations must not consult a mutable
 * profile again from the create path. Destinations, credentials, headers and protocol grants are
 * deliberately absent: they remain current authorization.</p>
 */
public interface ExecutionIoCapacityCapable {
    NodeExternalIoCapacity resolveExecutionIoCapacity(NodeConfiguration configuration);

    NodeAction create(NodeConfiguration configuration, NodePackageServices services,
                      NodeExternalIoCapacity capacity);
}
