package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one classifier five terminal paths depend on.
 *
 * <p>Four terminal handlers in the runner and the application's result-recording seam all ask this
 * the same question about the same throwable. If it answered differently for a wrapped cancellation
 * than for a bare one, the durable aggregate and the read-by-id registry would disagree about the
 * same run — and each would look correct on its own, which is what makes a divergence here expensive
 * to find and cheap to prevent.</p>
 */
class ExecutionTerminationTest {

    @Test
    @DisplayName("a cancellation is recognised through the wrappers the completion machinery adds")
    void aCancellationIsRecognisedThroughWrappers() {
        var cancellation = cancellation();
        assertEquals(ExecutionTerminationReason.CANCELLED, ExecutionTermination.reasonOf(cancellation));
        assertEquals(ExecutionTerminationReason.CANCELLED,
                ExecutionTermination.reasonOf(new CompletionException(cancellation)),
                "a cancellation reaches a terminal handler wrapped on most paths; testing only the "
                        + "outermost throwable would classify nearly every real stop as a fault");
        assertTrue(ExecutionTermination.isCancellation(
                new IllegalStateException("routed", new CompletionException(cancellation))));
    }

    @Test
    @DisplayName("an ordinary failure and an absent failure are both unqualified")
    void anOrdinaryFailureAndAnAbsentFailureAreBothUnqualified() {
        assertNull(ExecutionTermination.reasonOf(new IllegalStateException("boom")));
        assertNull(ExecutionTermination.reasonOf(new CompletionException(new RuntimeException("boom"))));
        assertNull(ExecutionTermination.reasonOf(null),
                "null means nothing distinguishes this termination, never 'not yet classified'");
        assertFalse(ExecutionTermination.isCancellation(new RuntimeException("boom")));
    }

    /**
     * A cause chain is not guaranteed acyclic, and this runs on a traversal's completion path where a
     * hang has no timeout above it. Both the self-referential case and a longer cycle must terminate.
     */
    @Test
    @DisplayName("a cyclic cause chain terminates instead of hanging the completion path")
    void aCyclicCauseChainTerminates() {
        // Throwable.initCause refuses self-causation, so the only way to produce it is an override --
        // which is exactly how a wrapper type could produce it by accident.
        var self = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertNull(ExecutionTermination.reasonOf(self));

        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);
        assertNull(ExecutionTermination.reasonOf(second));
    }

    private static TraversalCancelledException cancellation() {
        return new TraversalCancelledException(UUID.randomUUID(), "worker");
    }
}
