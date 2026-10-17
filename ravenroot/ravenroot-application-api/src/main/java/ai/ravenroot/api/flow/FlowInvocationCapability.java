package ai.ravenroot.api.flow;

import ai.ravenroot.api.execution.NodeMessage;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Engine-neutral capability shared by call-flow, start-flow and await-flow.
 *
 * <p>The interface deliberately exposes no deployment installation or graph-definition mutation.
 * Implementations resolve only registered versions and mint every handle themselves.</p>
 */
public interface FlowInvocationCapability {
    /**
     * Commits an invocation intent and starts its fresh child.
     * @param caller authenticated caller node message
     * @param target exact registered target version
     * @param input bounded child input
     * @param deadline maximum time allowed for the child
     * @return stage yielding the runtime-issued handle after durable admission
     */
    CompletionStage<FlowHandle> start(NodeMessage caller, FlowTarget target, Object input, Duration deadline);

    /**
     * Waits for a child or durably parks the caller until settlement.
     * @param caller authenticated caller node message
     * @param handle runtime-issued child reference
     * @return stage yielding the terminal child result
     */
    CompletionStage<FlowInvocationResult> await(NodeMessage caller, FlowHandle handle);

    /**
     * Starts a fresh child and awaits its terminal result.
     * @param caller authenticated caller node message
     * @param target exact registered target version
     * @param input bounded child input
     * @param deadline maximum time allowed for the child
     * @return stage yielding the terminal child result
     */
    default CompletionStage<FlowInvocationResult> invoke(NodeMessage caller, FlowTarget target,
                                                          Object input, Duration deadline) {
        return start(caller, target, input, deadline).thenCompose(handle -> await(caller, handle));
    }

    /**
     * Requests cancellation and records an explicit terminal disposition.
     * @param caller authenticated caller node message
     * @param handle runtime-issued child reference
     * @param reason bounded operator-safe cancellation reason
     * @return stage yielding the recorded terminal result
     */
    CompletionStage<FlowInvocationResult> cancel(NodeMessage caller, FlowHandle handle, String reason);

    /**
     * Provides payload-free internal observability for relation, wait and settlement state.
     * @param tenantId tenant boundary for the lookup
     * @param handle runtime-issued child reference
     * @return stage yielding the relation when it belongs to the tenant
     */
    CompletionStage<Optional<FlowInvocationObservation>> observe(String tenantId, FlowHandle handle);
}
