package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.TraversalStatus;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Schedule real graph publication around a real HTTP read, without fabricating its result. */
class ExecutionResultReadCoherenceHttpTest {
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
            <key id="kind" for="node" attr.name="kind" attr.type="string"/>
            <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
            <graph id="result-read" edgedefault="directed">
            <node id="error"><data key="kind">ERROR</data></node>
            <node id="start"><data key="kind">START</data></node>
            <node id="gate"><data key="kind">BEHAVIOR</data><data key="behavior">result-gate</data></node>
            <node id="end"><data key="kind">END</data></node>
            <edge source="start" target="gate"/><edge source="gate" target="end"/>
            </graph></graphml>
            """;

    private static void reached(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "fixture did not reach the requested publication boundary");
    }

    private static final class Fixture implements AutoCloseable {
        final CountDownLatch nodeEntered = new CountDownLatch(1);
        final CountDownLatch aggregateRead = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch aggregateCommitted = new CountDownLatch(1);
        final CountDownLatch releaseCommit = new CountDownLatch(1);
        final CountDownLatch resultWritten = new CountDownLatch(1);
        final CompletableFuture<NodeResult> nodeGate = new CompletableFuture<>();
        final InMemoryExecutionStore backing = new InMemoryExecutionStore();
        final PekkoExecutionEngine engine = new PekkoExecutionEngine("http-result-" + UUID.randomUUID());
        final DefaultRavenrootApplication app;
        final RavenrootServer server;
        final HttpClient client = HttpClient.newHttpClient();
        final UUID id = UUID.randomUUID();
        final URI uri;

        Fixture(boolean beforeResultPublication) throws Exception {
            var readGate = new AtomicBoolean(!beforeResultPublication);
            var commitGate = new AtomicBoolean(beforeResultPublication);
            ExecutionStore observed = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(),
                    new Class<?>[]{ExecutionStore.class}, (proxy, method, args) -> {
                        boolean lifecycleRead = method.getName().equals("load") &&
                                Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                                        frame.getMethodName().equals("reconcileDurableExecutionLifecycle"));
                        if (lifecycleRead && readGate.compareAndSet(true, false)) {
                            aggregateRead.countDown();
                            reached(releaseRead);
                        }
                        Object result;
                        try { result = method.invoke(backing, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                        // The real store has committed the terminal aggregate, but GraphRunner has
                        // not received the completion that permits its normal result publication.
                        if (method.getName().equals("apply") && args[0] instanceof ExecutionBatch batch
                                && batch.transitions().stream().anyMatch(transition ->
                                transition instanceof ExecutionTransition.TraversalTransitioned ended
                                        && ended.next() == TraversalStatus.COMPLETED)
                                && commitGate.compareAndSet(true, false)) {
                            ((CompletionStage<?>) result).toCompletableFuture().join();
                            aggregateCommitted.countDown();
                            reached(releaseCommit);
                        }
                        if (method.getName().equals("recordExecutionResult"))
                            ((CompletionStage<?>) result).thenRun(resultWritten::countDown);
                        return result;
                    });
            var behaviors = new BehaviorRegistry().register("result-gate", message -> {
                nodeEntered.countDown();
                return nodeGate;
            });
            app = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), behaviors,
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                    ExecutionIdentitySource.randomUuids(), observed);
            server = new RavenrootServer(app, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                    headers -> new AuthenticatedPrincipal("reader", AuthenticatedPrincipal.Type.USER,
                            "fixture", "tenant", Set.of(Role.OPERATOR), Arrays.stream(AuthorizationAction.values())
                            .filter(AuthorizationAction::available).map(AuthorizationAction::requiredScope)
                            .collect(Collectors.toSet())));
            server.start();
            uri = URI.create("http://127.0.0.1:" + server.port() + "/v1/executions/" + id);
            app.startGraphMl(new SecurityContext("reader", "tenant", "subject", PrincipalType.WORKLOAD, "fixture"),
                    id, new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)), "hello");
            reached(nodeEntered);
        }
        HttpRequest request() { return HttpRequest.newBuilder(uri).GET().build(); }
        HttpResponse<String> read() throws Exception { return client.send(request(), HttpResponse.BodyHandlers.ofString()); }
        void complete(Object payload) { Thread.startVirtualThread(() -> nodeGate.complete(NodeResult.continueWith(payload))); }
        @Override public void close() {
            releaseRead.countDown();
            releaseCommit.countDown();
            nodeGate.complete(NodeResult.continueWith("cleanup"));
            server.close();
            app.close();
            engine.close();
            backing.close();
            client.close();
        }
    }

    private static void assertCompleted(HttpResponse<String> response, boolean nullPayload) {
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"status\":\"COMPLETED\""), response.body());
        assertTrue(response.body().contains("\"visitedNodes\":[\"end\",\"gate\",\"start\"]"), response.body());
        if (nullPayload) assertFalse(response.body().contains("\"payload\":"), response.body());
        else assertTrue(response.body().contains("\"payload\":\"hello\""), response.body());
    }

    @Test void readRefreshesAnEarlierRunningLookupAfterRealResultPublication() throws Exception {
        publishedResultRace(false);
    }

    @Test void publishedNullPayloadIsStillCompleteWithItsActualNodeEvidence() throws Exception {
        publishedResultRace(true);
    }

    private void publishedResultRace(boolean nullPayload) throws Exception {
        try (var f = new Fixture(false)) {
            var read = f.client.sendAsync(f.request(), HttpResponse.BodyHandlers.ofString());
            reached(f.aggregateRead);
            f.complete(nullPayload ? null : "hello");
            reached(f.resultWritten);
            assertTrue(f.backing.loadExecutionResult("tenant", f.id).toCompletableFuture().join().isPresent());
            f.releaseRead.countDown();
            assertCompleted(read.get(10, TimeUnit.SECONDS), nullPayload);
            assertCompleted(f.read(), nullPayload);
        }
    }

    @Test void committedAggregateCannotInventSuccessBeforeActualResultPublication() throws Exception {
        try (var f = new Fixture(true)) {
            f.complete("hello");
            reached(f.aggregateCommitted);
            assertTrue(f.backing.loadExecutionResult("tenant", f.id).toCompletableFuture().join().isEmpty());
            var pending = f.read();
            assertEquals(200, pending.statusCode(), pending.body());
            assertTrue(pending.body().contains("\"status\":\"RUNNING\""), pending.body());
            assertFalse(pending.body().contains("\"payload\":"), pending.body());
            assertEquals(1, f.resultWritten.getCount(), "read must not wait for the blocked result publication");
            f.releaseCommit.countDown();
            reached(f.resultWritten);
            assertCompleted(f.read(), false);
        }
    }
}
