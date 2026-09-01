package ai.ravenroot.api.node.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A cancellable outbound operation whose result is delivered asynchronously.
 * @param <T> the {@code T} value.
 */
public interface OutboundCall<T> {
    /**
     * Obtains the stage completed by the managed transport once the call terminates.
     *
     * @return the terminal result stage, including sanitized failure or cancellation
     */
    CompletionStage<T> completion();

/**
 * Requests cancellation. Completion is terminal even when a transport needs time to unwind.
     * @return whether this invocation changed the call to cancelled
 */
    boolean cancel();

    /**
     * Adapts an already available value to the managed-operation shape.
     *
     * @param <T> result type
     * @param value result, including {@code null} when that result type permits it
     * @return a call whose completion is already successful
     */
    static <T> OutboundCall<T> completed(T value) {
        return from(CompletableFuture.completedFuture(value));
    }

    /**
     * Adapts a known failure to the managed-operation shape.
     *
     * @param <T> result type
     * @param failure non-null cause delivered through the completion stage
     * @return a call whose completion is already failed
     */
    static <T> OutboundCall<T> failed(Throwable failure) {
        return from(CompletableFuture.failedFuture(Objects.requireNonNull(failure, "failure")));
    }

    private static <T> OutboundCall<T> from(CompletableFuture<T> future) {
        return new OutboundCall<>() {
            @Override
            public CompletionStage<T> completion() {
                return future;
            }

            @Override
            public boolean cancel() {
                return future.cancel(true);
            }
        };
    }
}
