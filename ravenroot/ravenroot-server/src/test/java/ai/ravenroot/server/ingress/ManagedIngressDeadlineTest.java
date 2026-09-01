package ai.ravenroot.server.ingress;

import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.server.AuthenticatedPrincipalAttribute;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A deadline announces itself as a deadline.
 *
 * <p>Every refusal a managed route can produce used to name a party: 502 named the package, 408 named
 * the client, 413 named the request size. The one thing none of them named was the only thing that had
 * actually happened in three of those cases — the admitted window closed. These tests pin the
 * distinction from the outside, on the wire, where an operator reading a log sees it.</p>
 */
class ManagedIngressDeadlineTest {

    @BeforeAll static void initializeProductionHeaderGuardBeforeRawJdkServers() throws Exception {
        // RavenrootServer owns this process-wide JDK property; the raw HttpServer instances below
        // would otherwise freeze it first.
        Class.forName("ai.ravenroot.server.RavenrootServer", true,
                ManagedIngressDeadlineTest.class.getClassLoader());
    }

    @Test void deadlineSpentBeforeThePackageStartsRefusesWithFiveOhFourAndNeverInvokesIt()
            throws Exception {
        var timeout = Duration.ofMillis(120);
        var calls = new AtomicInteger();
        var burnt = new CountDownLatch(1);
        var hooks = new ManagedIngressRegistry.LifecycleHooks() {
            @Override public void afterPermitBeforePublication() {
                // Not a synchronisation sleep: no latch can make wall-clock time pass, and the point
                // under test is precisely that the window is already spent by the time admission
                // finishes. Waiting longer than the whole timeout makes expiry certain, not likely.
                long until = System.nanoTime() + timeout.toNanos() * 2;
                while (System.nanoTime() < until) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
                burnt.countDown();
            }
        };
        try (var harness = Harness.startWithHooks(timeout, 1, hooks, context((request, ignored) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[0]));
        }))) {
            assertEquals(504, harness.post().statusCode(),
                    "admission is inside the deadline, so a window spent before dispatch is a timeout");
            assertTrue(burnt.await(1, TimeUnit.SECONDS));
            assertEquals(0, calls.get(), "no package code runs for a request that can no longer answer");
        }
    }

    @Test void deadlineDuringHandlerWorkCancelsExactlyOnceReleasesThePermitAndAnswersFiveOhFour()
            throws Exception {
        var cancellations = new AtomicInteger();
        var observedCancelled = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var second = new AtomicBoolean();
        var handler = context((request, ctx) -> {
            if (!second.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0]));
            }
            ctx.cancellation().onCancel(() -> {
                cancellations.incrementAndGet();
                observedCancelled.set(ctx.cancellation().cancelled());
                cancelled.countDown();
            });
            entered.countDown();
            return new CompletableFuture<>();
        });
        try (var harness = Harness.start(Duration.ofMillis(120), 1, handler)) {
            assertEquals(504, harness.post().statusCode());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(cancelled.await(1, TimeUnit.SECONDS), "the deadline fires the cancellation signal");
            assertEquals(1, cancellations.get(), "one window, one cancellation");
            assertTrue(observedCancelled.get(), "cancelled() is already true when the listener runs");

            // maxConcurrentRequests is 1: this only answers if the timed-out request released its
            // route permit exactly once on its way out.
            assertEquals(202, harness.post().statusCode(), "no permit leaked on the deadline path");
        }
    }

    @Test void aHandlerThatFinishesInsideItsWindowIsNeverCancelledAndAnswersNormally()
            throws Exception {
        var cancellations = new AtomicInteger();
        var wasCancelled = new AtomicBoolean();
        var handler = context((request, ctx) -> {
            ctx.cancellation().onCancel(cancellations::incrementAndGet);
            var response = new IngressResponse(200, Map.of(), new byte[] {7});
            wasCancelled.set(ctx.cancellation().cancelled());
            assertFalse(ctx.remaining().isZero(), "budget is still open while the handler runs");
            return CompletableFuture.completedFuture(response);
        });
        try (var harness = Harness.start(Duration.ofSeconds(5), 1, handler)) {
            var response = harness.postForBytes();
            assertEquals(200, response.statusCode());
            assertArrayEquals(new byte[] {7}, response.body());
            assertFalse(wasCancelled.get());
            assertEquals(0, cancellations.get(),
                    "a request that answered in time never cancels; a false 504 would be as wrong as a false 502");
        }
    }

    @Test void retirementCancelsOnceAndALateResultCannotAnswerTheReplacementRequest()
            throws Exception {
        var cancellations = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var late = new CompletableFuture<IngressResponse>();
        var handler = context((request, ctx) -> {
            ctx.cancellation().onCancel(() -> { cancellations.incrementAndGet(); cancelled.countDown(); });
            entered.countDown();
            return late;
        });
        try (var harness = Harness.start(Duration.ofSeconds(30), 1, handler)) {
            var client = HttpClient.newHttpClient();
            var inFlight = client.sendAsync(harness.request(), HttpResponse.BodyHandlers.ofByteArray());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            harness.registry.retire(harness.owner);
            assertTrue(cancelled.await(2, TimeUnit.SECONDS), "retirement cancels the admitted request");
            assertEquals(1, cancellations.get(), "retirement and the deadline cannot both fire");

            // The package answers after its route is gone. It must reach nobody.
            late.complete(new IngressResponse(200, Map.of(), new byte[] {1}));
            try {
                assertNotEquals(200, inFlight.get(2, TimeUnit.SECONDS).statusCode());
            } catch (java.util.concurrent.ExecutionException closed) {
                assertInstanceOf(java.io.IOException.class, closed.getCause());
            }

            harness.acquireReplacement(context((request, ctx) ->
                    CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[] {2}))));
            var replacement = client.send(harness.request(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(202, replacement.statusCode());
            assertArrayEquals(new byte[] {2}, replacement.body(),
                    "the old generation's late answer cannot be delivered to a later request");
        }
    }

    @Test void aClientThatDisappearsMidBodyCancelsAndNeverReachesThePackage() throws Exception {
        var calls = new AtomicInteger();
        var handler = context((request, ctx) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new IngressResponse(200, Map.of(), new byte[0]));
        });
        try (var harness = Harness.start(Duration.ofSeconds(30), 1, handler)) {
            try (var socket = new Socket(InetAddress.getLoopbackAddress(), harness.port())) {
                socket.setSoTimeout(2_000);
                socket.getOutputStream().write(("POST /managed/example/orders HTTP/1.1\r\nHost: localhost\r\n"
                        + "Content-Length: 8\r\nConnection: close\r\n\r\nx")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                socket.setSoLinger(true, 0); // abort rather than a graceful half-close
            }
            // The deadline here is 30 seconds, so anything that unblocks quickly did so because the
            // failed read was observed, not because the request ran out of budget.
            long started = System.nanoTime();
            var replaced = new AtomicBoolean();
            while (!replaced.get()
                    && Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0) {
                var response = HttpClient.newHttpClient().send(harness.request(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) replaced.set(true);
                else LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
            assertTrue(replaced.get(),
                    "the abandoned exchange released its single route permit long before its deadline");
            assertEquals(1, calls.get(), "only the live request reached the package");
        }
    }

    @Test void admissionStaysBoundedWhileEveryAdmittedRequestIsLosingToItsDeadline() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<IngressResponse>();
        var slow = new AtomicBoolean(true);
        var handler = context((request, ctx) -> {
            if (slow.get()) { entered.countDown(); return release; }
            return CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0]));
        });
        try (var harness = Harness.start(Duration.ofMillis(400), 1, handler)) {
            var client = HttpClient.newHttpClient();
            var first = client.sendAsync(harness.request(), HttpResponse.BodyHandlers.discarding());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // The permit is held by the request that is still inside its window: overflow is 429,
            // never a timing status, because nothing about the second request timed out.
            assertEquals(429, client.send(harness.request(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(504, first.get(3, TimeUnit.SECONDS).statusCode());

            slow.set(false);
            assertEquals(202, harness.post().statusCode(), "the bound is restored, not consumed");
            assertEquals(202, harness.post().statusCode());
            assertEquals(1, harness.registry.inventory().size(), "the route survives its own timeouts");
        }
    }

    @Test void aPackageCompiledBeforeTheContextExistedStillReceivesEveryRequest() throws Exception {
        IngressRouteHandler legacy = request -> CompletableFuture.completedFuture(
                new IngressResponse(200, Map.of(), new byte[] {9}));
        try (var harness = Harness.start(Duration.ofSeconds(5), 1, legacy)) {
            var response = harness.postForBytes();
            assertEquals(200, response.statusCode());
            assertArrayEquals(new byte[] {9}, response.body(),
                    "the adapter always calls the two-argument form; the delegating default carries it");
        }
    }

    @Test void aBlockingCancellationListenerCannotStallRetirement() throws Exception {
        var allowExit = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var listenerRunning = new CountDownLatch(1);
        var handler = context((request, ctx) -> {
            ctx.cancellation().onCancel(() -> {
                listenerRunning.countDown();
                hostileWait(allowExit);
            });
            entered.countDown();
            return new CompletableFuture<>();
        });
        try (var harness = Harness.start(Duration.ofSeconds(30), 1, handler)) {
            var inFlight = HttpClient.newHttpClient()
                    .sendAsync(harness.request(), HttpResponse.BodyHandlers.discarding());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            long started = System.nanoTime();
            harness.registry.retire(harness.owner);
            var elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(listenerRunning.await(2, TimeUnit.SECONDS), "the listener did run");
            // The same bound ManagedIngressRegistryTest already asserts for a cancel-ignoring
            // callback. A cancellation listener is package code too, so running it on the retiring
            // thread put it outside MAX_RELEASE_DRAIN entirely, and N admitted requests multiplied
            // the wait because release() iterates them sequentially.
            assertTrue(elapsed.compareTo(Duration.ofMillis(500)) < 0,
                    () -> "a blocking cancellation listener stalled retirement for " + elapsed.toMillis() + "ms");
            assertNotNull(inFlight);
        } finally {
            allowExit.set(true);
        }
    }

    @Test void aBlockingCancellationListenerCannotExtendTheDeadlineOrHoldTheRoutePermit()
            throws Exception {
        var allowExit = new AtomicBoolean();
        var timeout = Duration.ofMillis(150);
        var first = new AtomicBoolean(true);
        var handler = context((request, ctx) -> {
            if (!first.compareAndSet(true, false)) {
                return CompletableFuture.completedFuture(new IngressResponse(202, Map.of(), new byte[0]));
            }
            ctx.cancellation().onCancel(() -> hostileWait(allowExit));
            return new CompletableFuture<>();
        });
        try (var harness = Harness.start(timeout, 1, handler)) {
            long started = System.nanoTime();
            assertEquals(504, harness.post().statusCode());
            var elapsed = Duration.ofNanos(System.nanoTime() - started);

            // The deadline is the adapter's, not the package's. Draining listeners on the dispatch
            // thread let a listener that ignores interruption hold the exchange open for as long as
            // it liked, which is exactly the immutability this boundary guarantees.
            assertTrue(elapsed.compareTo(timeout.multipliedBy(5)) < 0,
                    () -> "the package extended its own " + timeout.toMillis() + "ms deadline to "
                            + elapsed.toMillis() + "ms from a cancellation listener");
            // maxConcurrentRequests is 1, so this only answers if the permit was released rather
            // than held for the lifetime of the still-running listener.
            assertEquals(202, harness.post().statusCode(),
                    "a blocking listener must not keep the route's only permit");
        } finally {
            allowExit.set(true);
        }
    }

    /**
     * Package code that ignores interruption, the way retirement already assumes it might.
     *
     * <p>Bounded at three seconds rather than waiting only on {@code allowExit}: if the adapter runs
     * this on one of its own threads, an unbounded wait deadlocks the very test that is supposed to
     * report the stall, and a hang is not a measurement. Three seconds is far outside every bound
     * asserted here, so the assertion fails with a number instead of never returning.</p>
     */
    private static void hostileWait(AtomicBoolean allowExit) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!allowExit.get() && System.nanoTime() < until) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
            Thread.interrupted();
        }
    }

    private interface ContextHandler {
        CompletionStage<IngressResponse> handle(IngressRequest request, IngressRequestContext context);
    }

    /** A handler that overrides the context-aware entry point to observe the request deadline. */
    private static IngressRouteHandler context(ContextHandler delegate) {
        return new IngressRouteHandler() {
            @Override public CompletionStage<IngressResponse> handle(IngressRequest request) {
                return fail("the adapter must never call the context-free entry point");
            }

            @Override public CompletionStage<IngressResponse> handle(IngressRequest request,
                                                                     IngressRequestContext context) {
                return delegate.handle(request, context);
            }
        };
    }

    private static final class Harness implements AutoCloseable {
        private final ManagedIngressRegistry registry;
        private final HttpServer server;
        private final IngressRouteOwner owner;
        private final URI uri;

        private Harness(ManagedIngressRegistry registry, HttpServer server, IngressRouteOwner owner) {
            this.registry = registry;
            this.server = server;
            this.owner = owner;
            this.uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/managed/example/orders");
        }

        private static Harness start(Duration timeout, int maxConcurrent, IngressRouteHandler handler)
                throws Exception {
            return startWithHooks(timeout, maxConcurrent, ManagedIngressRegistry.LifecycleHooks.NONE, handler);
        }

        private static Harness startWithHooks(Duration timeout, int maxConcurrent,
                                              ManagedIngressRegistry.LifecycleHooks hooks,
                                              IngressRouteHandler handler) throws Exception {
            var authority = new IngressAuthorityDeclaration("example.oas", "main", "/managed/example",
                    Set.of("invoke"), 2, maxConcurrent, 64, 64, timeout);
            var registry = ManagedIngressRegistry.prepareWithLifecycleHooks(java.util.List.of(authority),
                    true, hooks);
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            registry.bind(server, next -> exchange -> {
                exchange.setAttribute(AuthenticatedPrincipalAttribute.NAME,
                        new AuthenticatedPrincipal("subject", AuthenticatedPrincipal.Type.USER, "issuer",
                                "tenant", Set.of(), Set.of("invoke")));
                next.handle(exchange);
            });
            // Without an executor the JDK server dispatches exchanges one at a time, which would make
            // every "second request" assertion below observe a queue rather than the route's own
            // admission bound. Production installs one for the same reason.
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            var owner = new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 1);
            registry.authorityFor(owner).acquire("route", "/orders", Set.of("POST"), handler);
            server.start();
            return new Harness(registry, server, owner);
        }

        private void acquireReplacement(IngressRouteHandler handler) {
            registry.authorityFor(new IngressRouteOwner("example.oas", "tenant", "deployment", "node", 2))
                    .acquire("replacement", "/orders", Set.of("POST"), handler);
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private HttpRequest request() {
            return HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
        }

        private HttpResponse<Void> post() throws Exception {
            return HttpClient.newHttpClient().send(request(), HttpResponse.BodyHandlers.discarding());
        }

        private HttpResponse<byte[]> postForBytes() throws Exception {
            return HttpClient.newHttpClient().send(request(), HttpResponse.BodyHandlers.ofByteArray());
        }

        @Override public void close() {
            registry.close();
            server.stop(0);
            if (server.getExecutor() instanceof java.util.concurrent.ExecutorService pool) pool.shutdownNow();
        }
    }
}
