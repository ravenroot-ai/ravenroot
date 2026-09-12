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
    /**
     * Resolves the immutable quantitative I/O capacity for one graph node binding.
     *
     * @param configuration graph-authored node configuration
     * @return bounded capacity to persist with the execution manifest
     */
    NodeExternalIoCapacity resolveExecutionIoCapacity(NodeConfiguration configuration);

    /**
     * Creates an action using the exact capacity pinned during execution admission.
     *
     * @param configuration graph-authored node configuration
     * @param services runtime-managed package service view
     * @param capacity capacity resolved and pinned for this node binding
     * @return executable node action constrained by the pinned capacity
     */
    NodeAction create(NodeConfiguration configuration, NodePackageServices services,
                      NodeExternalIoCapacity capacity);
}
