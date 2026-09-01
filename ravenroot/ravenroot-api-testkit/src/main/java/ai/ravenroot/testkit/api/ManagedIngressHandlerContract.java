package ai.ravenroot.testkit.api;

import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What a managed-ingress handler must do with the window it is given.
 *
 * <h2>What this asserts, and what it deliberately does not</h2>
 * <p>It does not assert a status code, a payload or a shutdown order: those belong to the extension.
 * It asserts the two properties the adapter cannot enforce on the handler's behalf, because they are
 * about work the adapter cannot reach into — that the handler <em>reads</em> the context-aware entry
 * point at all, and that it stops waiting when the answer has stopped being wanted.</p>
 *
 * <p>A handler that ignores its window is not broken; the adapter discards its late result and the
 * request is refused with a sanitized 504 either way. It is merely burning a route permit and a
 * virtual thread for an answer nobody will read, which is what this contract exists to catch before
 * production does.</p>
 */
public abstract class ManagedIngressHandlerContract {

    /** The handler under test, configured the way its package registers it on a route. */
    protected abstract IngressRouteHandler handler();

    /** The request to drive it with. Override when the handler needs a specific projection. */
    protected IngressRequest request() {
        return ManagedIngressExchangeFixture.request("POST", "/");
    }

    /** How long a cancelled or expired invocation may take to settle. */
    protected Duration settleWithin() {
        return Duration.ofSeconds(2);
    }

    @Test
    final void readsTheContextAwareEntryPoint() {
        assertTrue(overridesContextAwareHandle(handler().getClass()),
                () -> handler().getClass().getName() + " never overrides handle(IngressRequest, "
                        + "IngressRequestContext), so it receives the delegating default and can "
                        + "observe neither its deadline nor its cancellation");
    }

    @Test
    final void neverReturnsANullStage() {
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(5));
        assertNotNull(fixture.invoke(handler(), request()),
                "the adapter refuses a null stage as a handler failure, never as a timing refusal");
    }

    @Test
    final void settlesWhenTheRequestIsCancelledBeforeItCompletes() {
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofMinutes(5));
        CompletionStage<IngressResponse> stage = fixture.invoke(handler(), request());
        fixture.cancel();
        assertSettles(stage, "cancellation was fired well inside the deadline, so nothing but the "
                + "handler's own waiting can still be holding this stage open");
    }

    @Test
    final void settlesWhenItIsReachedWithNoBudgetLeft() {
        var fixture = ManagedIngressExchangeFixture.expired();
        assertTrue(fixture.context().remaining().isZero());
        assertSettles(fixture.invoke(handler(), request()),
                "a handler with no remaining budget must not start work whose result cannot be "
                        + "delivered");
    }

    @Test
    final void cancellationIsIdempotentAndRunsEachListenerOnce() {
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(5));
        IngressRequestContext context = fixture.context();
        context.cancellation().onCancel(() -> { });
        fixture.cancel();
        fixture.cancel();
        assertDoesNotThrow(fixture::cancel);
        assertTrue(fixture.cancelled());
        // Registering after the fact still runs, and neither listener runs twice.
        context.cancellation().onCancel(() -> { });
        assertEquals(2, fixture.listenerRuns(), "each listener runs exactly once, however it was registered");
    }

    private void assertSettles(CompletionStage<IngressResponse> stage, String why) {
        assertNotNull(stage, "handler stage");
        try {
            stage.toCompletableFuture().get(settleWithin().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillRunning) {
            fail(why);
        } catch (ExecutionException | java.util.concurrent.CancellationException settled) {
            // Declining is a settlement. How the handler declines is its own business.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for the handler stage to settle");
        }
    }

    /**
     * Resolves the implementation the JVM would actually invoke, rather than walking superclasses by
     * hand: {@code getMethod} already picks the most specific one, so a class override, an inherited
     * override on an abstract base and a narrowing default on a sub-interface are all recognised, and
     * only the delegating default declared on {@link IngressRouteHandler} itself is rejected.
     */
    private static boolean overridesContextAwareHandle(Class<?> type) {
        try {
            var resolved = type.getMethod("handle", IngressRequest.class, IngressRequestContext.class);
            return !resolved.isDefault() || !resolved.getDeclaringClass().equals(IngressRouteHandler.class);
        } catch (NoSuchMethodException impossible) {
            return false;
        }
    }
}
