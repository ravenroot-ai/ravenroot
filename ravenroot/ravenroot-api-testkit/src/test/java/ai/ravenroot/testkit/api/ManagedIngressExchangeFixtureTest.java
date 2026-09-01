package ai.ravenroot.testkit.api;

import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The fixture must behave like the adapter, and the contract must fail a handler that ignores it. */
class ManagedIngressExchangeFixtureTest {

    @Test void cancellationFiresOnceHoweverManyTimesItIsRequested() {
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(10));
        var runs = new AtomicInteger();
        fixture.context().cancellation().onCancel(runs::incrementAndGet);
        assertFalse(fixture.cancelled());

        fixture.cancel();
        fixture.cancel();
        fixture.cancel();

        assertTrue(fixture.cancelled());
        assertEquals(1, runs.get(), "the adapter fires at most once; so does this");
    }

    @Test void aListenerRegisteredAfterTheFactStillRuns() {
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(10));
        fixture.cancel();
        var ran = new AtomicInteger();
        fixture.context().cancellation().onCancel(ran::incrementAndGet);
        assertEquals(1, ran.get());
    }

    @Test void aThrowingListenerNeitherStopsTheOthersNorFailsTheCancellation() {
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(10));
        var survivor = new AtomicInteger();
        var cancellation = fixture.context().cancellation();
        cancellation.onCancel(() -> { throw new IllegalStateException("package code misbehaved"); });
        cancellation.onCancel(survivor::incrementAndGet);
        assertDoesNotThrow(fixture::cancel);
        assertEquals(1, survivor.get());
    }

    @Test void everyContextFromOneFixtureSharesTheSameWindow() {
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(10));
        assertEquals(fixture.context().deadline(), fixture.context().deadline());
        fixture.cancel();
        assertTrue(fixture.context().cancellation().cancelled(),
                "a context handed out before cancellation and one handed out after report the same fact");
    }

    @Test void anExpiredWindowIsNotLiveAndHasNoRemainingBudget() {
        var expired = ManagedIngressExchangeFixture.expired().context();
        assertEquals(Duration.ZERO, expired.remaining());
        assertFalse(expired.live());
        assertFalse(expired.cancellation().cancelled(), "expiry is not cancellation; they are two facts");

        var open = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(10)).context();
        assertTrue(open.live());
        assertFalse(open.remaining().isZero());
    }

    @Test void invokeReachesTheContextAwareEntryPointAndNotTheLegacyOne() {
        var seen = new AtomicReference<Duration>();
        IngressRouteHandler handler = new IngressRouteHandler() {
            @Override public java.util.concurrent.CompletionStage<IngressResponse> handle(
                    ai.ravenroot.api.ingress.IngressRequest request) {
                return fail("the fixture must call the two-argument form");
            }

            @Override public java.util.concurrent.CompletionStage<IngressResponse> handle(
                    ai.ravenroot.api.ingress.IngressRequest request,
                    ai.ravenroot.api.ingress.IngressRequestContext context) {
                seen.set(context.remaining());
                return CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[0]));
            }
        };
        var fixture = ManagedIngressExchangeFixture.withBudget(Duration.ofSeconds(10));
        assertNotNull(fixture.invoke(handler, ManagedIngressExchangeFixture.request("POST", "/orders")));
        assertNotNull(seen.get());
        assertFalse(seen.get().isZero());
    }

    @Test void theContractRejectsAHandlerThatNeverLooksAtItsWindow() {
        // A lambda cannot override a default method, so this is the exact shape of every handler
        // written before the context-aware entry point existed — and the assertion that must not
        // quietly pass for it.
        var blind = new ManagedIngressHandlerContract() {
            @Override protected IngressRouteHandler handler() {
                return request -> CompletableFuture.completedFuture(
                        new IngressResponse(200, Map.of(), new byte[0]));
            }
        };
        AssertionError refused = assertThrows(AssertionError.class, blind::readsTheContextAwareEntryPoint);
        assertTrue(refused.getMessage().contains("delegating default"), refused.getMessage());
    }
}
