package ai.ravenroot.testkit.api;

import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The reference implementation the published contract is run against.
 *
 * <p>This is what a package that honours its window looks like: it declines outright when there is no
 * budget left, and it unwinds the pending stage from the cancellation callback rather than waiting for
 * an answer nobody will read. An extension inherits the same assertions by extending
 * {@link ManagedIngressHandlerContract} and returning its own handler.</p>
 */
class DeadlineAwareIngressHandlerContractTest extends ManagedIngressHandlerContract {

    @Override protected IngressRouteHandler handler() {
        return new IngressRouteHandler() {
            @Override public CompletionStage<IngressResponse> handle(IngressRequest request) {
                return CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[0]));
            }

            @Override public CompletionStage<IngressResponse> handle(IngressRequest request,
                                                                     IngressRequestContext context) {
                if (context.remaining().isZero()) {
                    return CompletableFuture.failedFuture(new IllegalStateException("no budget left"));
                }
                var pending = new CompletableFuture<IngressResponse>();
                context.cancellation().onCancel(() ->
                        pending.completeExceptionally(new CancellationException("request abandoned")));
                return pending;
            }
        };
    }
}
