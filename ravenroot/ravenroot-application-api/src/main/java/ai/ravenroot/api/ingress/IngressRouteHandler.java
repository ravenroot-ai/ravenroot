package ai.ravenroot.api.ingress;

import java.util.concurrent.CompletionStage;

/** Receives one bounded, authenticated request; it cannot reach a listener or alter its route. */
@FunctionalInterface
public interface IngressRouteHandler {
/**
 * Handles one authenticated bounded request.
 * @param request request projection supplied by the managed adapter.
 * @return stage yielding the bounded response.
 */
    CompletionStage<IngressResponse> handle(IngressRequest request);

    /**
     * Receives the same request together with its runtime-issued handling window.
     *
     * <p>The managed adapter always calls this method. An implementation that does not override it
     * gets the default, which drops the context and calls {@link #handle(IngressRequest)} — the exact
     * behaviour it had before this method existed. That is why the default <em>delegates</em> rather
     * than denying, unlike the fail-closed defaults elsewhere in this package: ignoring a deadline is
     * not a security decision a handler could get wrong, it only wastes work whose result the adapter
     * discards anyway, and the requirement here is that every handler compiled against the earlier
     * surface keeps working unchanged.</p>
     *
     * <h4>Why an added arity and not a new name</h4>
     * <p>{@code IngressRouteHandler} is a functional interface whose whole ergonomic value is the
     * lambda {@code request -> …}. A second abstract method would destroy that; a differently named
     * default method would leave the adapter guessing which one a package meant. An overload of
     * {@code handle} differs in arity, so no existing call site and no existing lambda can become
     * ambiguous — unlike an earlier candidate overload, which had
     * the same arity and an unrelated middle parameter, does not arise here.</p>
     *
     * <p>Overriding this method and {@link #handle(IngressRequest)} both remains legal: the
     * single-argument form stays the abstract method, so an implementation that wants the context
     * overrides this one and typically implements the other by delegating with a context of its own
     * choosing, which is only ever observed in its own tests.</p>
     *
     * @param request the bounded projection of the admitted request
     * @param context the absolute deadline and cancellation signal owned by the managed adapter
 * @return stage yielding the bounded response; the default delegates without consuming context.
     */
    default CompletionStage<IngressResponse> handle(IngressRequest request, IngressRequestContext context) {
        return handle(request);
    }
}
