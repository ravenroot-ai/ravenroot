package ai.ravenroot.server;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.history.*;
import ai.ravenroot.api.payload.*;
import ai.ravenroot.api.persistence.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.core.programming.*;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.server.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentHistoryHttpTest {
    @TempDir Path directory;
    private static final SecurityContext IDENTITY = new SecurityContext("request", "tenant", "operator", PrincipalType.USER, "issuer");
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <graph id="history" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="end"><data key="kind">END</data></node>
                <node id="error"><data key="kind">ERROR</data></node>
                <edge source="start" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void thousandVisitsAreBoundedBeforeHttpSerializationAndSelectionsCannotBroadenScope() throws Exception {
        try (var fixture = new Fixture()) {
            var binding = fixture.binding;
            var key = new ExecutionKey("tenant", UUID.randomUUID());
            UUID traversal = UUID.randomUUID(), otherTraversal = UUID.randomUUID();
            var visits = new LinkedHashMap<UUID, NodeInvocation>(); UUID parent = null;
            for (int i = 0; i < 1000; i++) {
                UUID id = UUID.randomUUID();
                visits.put(id, new NodeInvocation(id, "loop", parent == null ? Set.of() : Set.of(parent), NodeInvocationStatus.COMPLETED,
                        List.of(new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.FAILED, null, null),
                                new NodeAttempt(UUID.randomUUID(), 2, NodeAttemptStatus.COMPLETED, NodeAttemptCompletion.SUCCEEDED, null))));
                parent = id;
            }
            var state = new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.COMPLETED,
                    Map.of(traversal, new Traversal(traversal, "loop", TraversalStatus.COMPLETED, visits),
                            otherTraversal, new Traversal(otherTraversal, "other", TraversalStatus.COMPLETED, Map.of())));
            fixture.store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .recordOrigin(ExecutionOrigin.of("document", "prior-incarnation", null, "transport-request"))
                    .apply(new ExecutionTransition.ProcessCreated(state, new GraphVersionPin(binding.graphVersion()))).build()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            fixture.rejectAggregateLoads.set(true);
            var source = "processInstanceId=" + key.processInstanceId() + "&traversalId=" + traversal;
            String path = "view=visits&" + source + "&limit=37";
            var ids = new HashSet<String>(); String cursor = null; int pages = 0, largest = 0;
            long began = System.nanoTime();
            do {
                var response = fixture.history(path + (cursor == null ? "" : "&cursor=" + encode(cursor)), "tenant", binding.incarnationId());
                assertEquals(200, response.statusCode(), response.body());
                assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElseThrow());
                largest = Math.max(largest, response.body().getBytes(StandardCharsets.UTF_8).length);
                var body = object(response.body());
                var items = ((PayloadValue.ListValue) body.entries().get("items")).values();
                assertTrue(items.size() <= 37);
                for (var item : items) {
                    var row = (PayloadValue.MapValue) item;
                    assertTrue(ids.add(text(row, "invocationId")));
                    assertEquals("2", PayloadJson.write(row.entries().get("attemptCount")));
                    assertInstanceOf(PayloadValue.NullValue.class, row.entries().get("startedAt"));
                }
                var next = body.entries().get("nextCursor");
                cursor = next instanceof PayloadValue.TextValue value ? value.value() : null; pages++;
            } while (cursor != null);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertEquals(1000, ids.size()); assertEquals(28, pages); assertTrue(largest < 40_000, "metadata response size");
            assertTrue(elapsed < 30_000, "bounded HTTP pagination elapsed " + elapsed);
            System.out.println("history-http rows=1000 pages=" + pages + " maxPageBytes=" + largest + " elapsedMs=" + elapsed);
            var traversals = fixture.history("view=process-traversals&processInstanceId=" + key.processInstanceId(), "tenant", binding.incarnationId());
            assertEquals(200, traversals.statusCode(), traversals.body());
            assertTrue(traversals.body().contains(traversal.toString())); assertTrue(traversals.body().contains(otherTraversal.toString()));
            assertTrue(traversals.body().contains("\"applicationContext\":null"), "legacy records stay uncorrelated");
            UUID invocation = visits.keySet().iterator().next();
            var attempts = fixture.history("view=attempts&" + source + "&invocationId=" + invocation, "tenant", binding.incarnationId());
            assertEquals(200, attempts.statusCode(), attempts.body()); assertTrue(attempts.body().contains("FAILED")); assertTrue(attempts.body().contains("SUCCEEDED"));
            var wrongVisit = fixture.history("view=attempts&processInstanceId=" + key.processInstanceId() + "&traversalId=" + otherTraversal + "&invocationId=" + invocation, "tenant", binding.incarnationId());
            assertEquals(404, wrongVisit.statusCode(), wrongVisit.body());
            assertEquals(404, fixture.history(path, "foreign", binding.incarnationId()).statusCode());
            assertEquals(409, fixture.history(path, "tenant", "stale").statusCode());
            assertEquals(400, fixture.history(path + "&tenantId=foreign", "tenant", binding.incarnationId()).statusCode());
            assertEquals(403, fixture.history(path, "denied", binding.incarnationId()).statusCode());
            var first = object(fixture.history(path, "tenant", binding.incarnationId()).body());
            String boundCursor = text(first, "nextCursor");
            assertEquals(400, fixture.history(path + "&nodeId=loop&cursor=" + encode(boundCursor), "tenant", binding.incarnationId()).statusCode());
            assertEquals(400, fixture.history(path.replace("limit=37", "limit=101"), "tenant", binding.incarnationId()).statusCode());
        }
    }

    @Test void configuredMaximumPageProjectsAllThousandRowsAndRefusesAnOversizeRequest() throws Exception {
        var d = ai.ravenroot.api.correlation.BusinessHistoryPolicy.DEFAULTS;
        var policy = new ai.ravenroot.api.correlation.BusinessHistoryPolicy(d.maxHistoriesPerScope(), d.maxOpenJoinsPerHistory(),
                d.maxRetainedJoinsPerHistory(), d.maxContributionsPerJoin(), d.maxJoinPayloadBytes(), 1000,
                d.historyRetention(), d.joinDeadline(), d.selection(), d.maxJoinCasAttempts(), d.readTimeout(), d.response());
        try (var fixture = new Fixture(policy)) {
            var key = new ExecutionKey("tenant", UUID.randomUUID()); UUID traversal = UUID.randomUUID();
            var visits = new LinkedHashMap<UUID, NodeInvocation>();
            for (int i = 0; i < 1000; i++) {
                UUID id = UUID.randomUUID(); visits.put(id, new NodeInvocation(id, "loop", Set.of(), NodeInvocationStatus.COMPLETED,
                        List.of(new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.COMPLETED, NodeAttemptCompletion.SUCCEEDED, null))));
            }
            fixture.store.apply(ExecutionBatch.to(key).expecting(RevisionExpectation.notPresent())
                    .recordOrigin(ExecutionOrigin.of("document", null, null))
                    .apply(new ExecutionTransition.ProcessCreated(new ProcessInstance(key.processInstanceId(), ProcessInstanceStatus.COMPLETED,
                            Map.of(traversal, new Traversal(traversal, "loop", TraversalStatus.COMPLETED, visits))),
                            new GraphVersionPin(fixture.binding.graphVersion()))).build()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            fixture.rejectAggregateLoads.set(true);
            String query = "view=visits&processInstanceId=" + key.processInstanceId() + "&traversalId=" + traversal + "&limit=1000";
            var response = fixture.history(query, "tenant", fixture.binding.incarnationId());
            assertEquals(200, response.statusCode(), response.body());
            int bytes = response.body().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes > PayloadLimits.DEFAULTS.maxEncodedBytes(), "metadata response has its own operator-owned budget");
            assertTrue(bytes < policy.response().maxEncodedBytes());
            var page = (PayloadValue.MapValue) PayloadJson.read(response.body().getBytes(StandardCharsets.UTF_8), policy.response());
            assertEquals(1000, ((PayloadValue.ListValue) page.entries().get("items")).values().size());
            assertInstanceOf(PayloadValue.NullValue.class, page.entries().get("nextCursor"));
            assertEquals(400, fixture.history(query.replace("limit=1000", "limit=1001"), "tenant", fixture.binding.incarnationId()).statusCode());
            System.out.println("history-http configuredMaxPage=1000 rows=1000 bytes=" + bytes);
        }
    }

    @Test void initialStartHttpReconcilesOriginalIdentitiesAcrossDistinctClientRequestIds() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.initial("one", "{\"value\":\"original\"}");
            assertEquals(202, first.statusCode(), first.body());
            var second = fixture.initial("two", "{\"value\":\"replacement\"}");
            assertEquals(200, second.statusCode(), second.body());
            assertEquals(text(object(first.body()), "processInstanceId"), text(object(second.body()), "processInstanceId"));
            assertEquals(text(object(first.body()), "traversalId"), text(object(second.body()), "traversalId"));
            assertEquals("DUPLICATE", text(object(second.body()), "outcome"));
        }
    }
    private final class Fixture implements AutoCloseable {
        final PekkoExecutionEngine engine = new PekkoExecutionEngine("history-http");
        final SqliteExecutionStore store;
        final AtomicBoolean rejectAggregateLoads = new AtomicBoolean();
        final DefaultRavenrootApplication application;
        final RavenrootServer server;
        final ai.ravenroot.api.application.DeploymentViewerView.Source binding;
        final HttpClient client = HttpClient.newHttpClient();
        Fixture() throws Exception { this(ai.ravenroot.api.correlation.BusinessHistoryPolicy.DEFAULTS); }
        Fixture(ai.ravenroot.api.correlation.BusinessHistoryPolicy policy) throws Exception {
            store = new SqliteExecutionStore(directory.resolve(UUID.randomUUID() + ".db"), Clock.systemUTC(),
                    ai.ravenroot.persistence.sqlite.SqliteStoreConfig.defaults().withBusinessHistories(policy));
            ExecutionStore observed = (ExecutionStore) java.lang.reflect.Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                    (proxy, method, arguments) -> {
                        if (rejectAggregateLoads.get() && Set.of("load", "correlatedJoin").contains(method.getName()))
                            throw new AssertionError("metadata read loaded aggregate/contribution payload");
                        try { return method.invoke(store, arguments); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
            application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), new BehaviorRegistry(),
                    new InMemoryArtifactRegistry(), new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), observed, 8, UnknownBehaviorPolicy.passThrough());
            application.registerLocalDeployment(IDENTITY, "document", new java.io.ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8)));
            application.startLocalDeployment(IDENTITY, "document").toCompletableFuture().get(5, TimeUnit.SECONDS);
            binding = application.localDeploymentView("tenant", "document").orElseThrow().source();
            var security = new HttpSecurityConfiguration(new BrowserOriginPolicy(Set.of("https://editor.example")), new SecurityHeadersPolicy(false), Duration.ofSeconds(30));
            server = new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, true,
                    headers -> {
                        String identity = headers.getFirst("X-Test-Tenant");
                        if (identity == null) throw new AuthenticationException("missing tenant");
                        return new AuthenticatedPrincipal(identity, AuthenticatedPrincipal.Type.USER, "issuer", identity.equals("denied") ? "tenant" : identity,
                                Set.of(Role.OPERATOR), identity.equals("denied") ? Set.of() : Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available).map(AuthorizationAction::requiredScope).collect(java.util.stream.Collectors.toSet()));
                    }, security);
            server.start();
        }
        HttpResponse<String> history(String query, String tenant, String incarnation) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/deployments/document/history?graphVersion=" + encode(binding.graphVersion()) + "&" + query))
                    .header("X-Test-Tenant", tenant).header("X-Ravenroot-Deployment-Incarnation", incarnation).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> initial(String id, String input) throws Exception {
            String body = "{\"incarnationId\":\"" + binding.incarnationId() + "\",\"graphVersion\":\"" + binding.graphVersion() + "\",\"requestId\":\"" + id + "\",\"input\":" + input + "}";
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/deployments/document/initial-start"))
                    .header("X-Test-Tenant", "tenant").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { rejectAggregateLoads.set(false); server.close(); engine.close(); store.close(); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static PayloadValue.MapValue object(String json) { return (PayloadValue.MapValue) PayloadJson.read(json.getBytes(StandardCharsets.UTF_8), PayloadLimits.DEFAULTS); }
    private static String text(PayloadValue.MapValue object, String name) { return ((PayloadValue.TextValue) object.entries().get(name)).value(); }
}
