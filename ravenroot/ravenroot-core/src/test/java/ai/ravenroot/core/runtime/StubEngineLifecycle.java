package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeStatus;

/**
 * The CORE-04 supervision surface for the hand-written engine stubs in these core tests.
 *
 * <p>Those stubs exist to observe spawn counts, identity propagation and validation order on the
 * calling thread; none of them models drain or cancellation, and none of them should start to. The
 * supervision contract is asserted where it can actually be asserted — against the real adapters,
 * through {@code ExecutionEngineContract}. Collecting the trivial answers here keeps that fact
 * visible instead of scattering five plausible-looking implementations that no test verifies.</p>
 */
final class StubEngineLifecycle {
    /** A signal for stubs that never cancel anything. */
    static final CancellationSignal NEVER_CANCELLED = new CancellationSignal() {
        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public void onCancel(Runnable listener) {
            // Never fires: these stubs have no cancellation to report.
        }
    };

    private StubEngineLifecycle() {
    }

    /** The status of a node in a stub that only ever keeps nodes running. */
    static NodeStatus running(NodeRef node) {
        return new NodeStatus(node, NodeLifecycleState.RUNNING, null, 0);
    }
}
