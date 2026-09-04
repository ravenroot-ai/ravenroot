package ai.ravenroot.extensions.slack;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;
import ai.ravenroot.api.ingress.IngressRouteLease;
import ai.ravenroot.api.ingress.IngressRouteOwner;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.ManagedIngressSource;
import ai.ravenroot.api.node.service.CredentialLease;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SlackIngressSourceTest {
    @TempDir Path directory;

    @Test void signatureAndPastOrFutureSkewFailBeforeParsingAndDurability() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(SlackBehaviorDescriptors.EVENTS, ingress);
        byte[] malformed = "not-json".getBytes(StandardCharsets.UTF_8);
        assertEquals(401, route.handler.handle(request(malformed, SlackTestSupport.timestamp(), "v0=" + "0".repeat(64), false))
                .toCompletableFuture().join().status());
        for (long skew : List.of(-301L, 301L)) {
            String timestamp = Long.toString(SlackTestSupport.NOW.getEpochSecond() + skew);
            assertEquals(401, route.handler.handle(request(malformed, timestamp,
                    SlackTestSupport.signature(timestamp, malformed), false)).toCompletableFuture().join().status());
        }
        assertEquals(0, ingress.offers.get());
    }

    @Test void eventCommitsThenDeduplicatesAcrossSourceRestartAndRejectsCollision() {
        DurableIngress ingress = new DurableIngress(); byte[] body = event("Ev01234567", "hello");
        CaptureRoute first = source(SlackBehaviorDescriptors.EVENTS, ingress);
        assertEquals(200, signed(first, body, false).status()); first.source.stop().toCompletableFuture().join();
        CaptureRoute restarted = source(SlackBehaviorDescriptors.EVENTS, ingress);
        assertEquals(200, signed(restarted, body, false).status());
        assertEquals(2, ingress.offers.get()); assertEquals(1, ingress.keys.size());
        assertEquals(409, signed(restarted, event("Ev01234567", "changed"), false).status());
        assertEquals(2, ingress.offers.get());
        assertFalse(ingress.payloads.toString().contains("fixture-signing-secret"));
    }

    @Test void commandIsDurableBeforeAckAndDropsBearerAndHumanFields() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(SlackBehaviorDescriptors.COMMANDS, ingress);
        byte[] body = form(Map.ofEntries(Map.entry("team_id", SlackTestSupport.TEAM),
                Map.entry("api_app_id", SlackTestSupport.APPLICATION), Map.entry("channel_id", SlackTestSupport.CHANNEL),
                Map.entry("channel_name", "private-channel"), Map.entry("user_id", SlackTestSupport.USER),
                Map.entry("user_name", "private-user"), Map.entry("command", "/deploy"), Map.entry("text", "staging"),
                Map.entry("token", "deprecated-secret"), Map.entry("response_url", "https://hooks.slack.invalid/bearer"),
                Map.entry("trigger_id", "trigger-secret")));
        assertEquals(200, signed(route, body, true).status());
        assertEquals(1, ingress.offers.get());
        String payload = ingress.payloads.getFirst().toString();
        assertTrue(payload.contains("/deploy")); assertTrue(payload.contains("staging"));
        for (String forbidden : List.of("deprecated-secret", "hooks.slack", "trigger-secret", "private-user", "private-channel"))
            assertFalse(payload.contains(forbidden));
        assertEquals(200, signed(route, body, true).status()); assertEquals(2, ingress.offers.get());
    }

    @Test void signedUrlVerificationDoesNotEnterGraph() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(SlackBehaviorDescriptors.EVENTS, ingress);
        byte[] body = SlackValues.jsonBytes(Map.of("type", "url_verification",
                "challenge", "bounded-challenge", "token", "deprecated-token"));
        IngressResponse response = signed(route, body, false);
        assertEquals(200, response.status());
        assertEquals(Map.of("challenge", "bounded-challenge"), SlackValues.json(response.body()));
        assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("deprecated-token"));
        assertEquals(0, ingress.offers.get());
    }

    @Test void eventChannelAuthorityIsTypeAwareAndNonChannelEventsAreExplicit() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(SlackBehaviorDescriptors.EVENTS, ingress);
        assertEquals(403, signed(route, event("EvUnauthorized", "message", "C99999999", "blocked"), false).status());
        assertEquals(400, signed(route, event("EvMissing", "message", null, "missing"), false).status());
        assertEquals(403, signed(route, event("EvForgedScope", "team_join", SlackTestSupport.CHANNEL, "blocked"), false).status());
        byte[] teamJoin = SlackValues.jsonBytes(Map.of("type", "event_callback", "team_id", SlackTestSupport.TEAM,
                "api_app_id", SlackTestSupport.APPLICATION, "event_id", "EvTeamJoin",
                "event", Map.of("type", "team_join", "user", Map.of("id", SlackTestSupport.USER))));
        assertEquals(200, signed(route, teamJoin, false).status());
        assertEquals(1, ingress.offers.get());
        assertFalse(ingress.payloads.getFirst().toString().contains("channelId"));
    }

    @Test void signedSslCheckIsAcknowledgedWithoutDeprecatedTokenAuthority() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(SlackBehaviorDescriptors.COMMANDS, ingress);
        byte[] body = form(Map.of("ssl_check", "1", "token", "deprecated-token"));
        IngressResponse response = signed(route, body, true);
        assertEquals(200, response.status()); assertEquals(0, response.body().length);
        assertEquals(0, ingress.offers.get());
    }

    @Test void lazyStoreInitializationOccursDuringSourceCreationNotFirstRequest() {
        Path database = directory.resolve("source-initialized.db");
        SlackConfiguration configuration = SlackTestSupport.configuration(database);
        SlackRuntime runtime = new SlackRuntime(() -> configuration);
        assertFalse(Files.exists(database));
        DurableIngress ingress = new DurableIngress(); Context context = new Context(ingress);
        new SlackEventsSourceBehavior(runtime).createSource(SlackTestSupport.node(SlackBehaviorDescriptors.EVENTS),
                context, credentials(new AtomicReference<>()));
        assertTrue(Files.exists(database)); assertEquals(0, ingress.offers.get());
    }

    @Test void sqliteContentionCompletesWithinAckWindowWithoutLateSuccess() throws Exception {
        Path database = directory.resolve("contended.db"); DurableIngress ingress = new DurableIngress();
        CaptureRoute route = source(SlackBehaviorDescriptors.EVENTS, ingress, new AtomicReference<>(),
                SlackTestSupport.nodePackage(database));
        try (Connection blocker = lock(database)) {
            byte[] body = event("EvContended", "blocked"); String timestamp = SlackTestSupport.timestamp();
            long started = System.nanoTime();
            IngressResponse response = route.handler.handle(
                    request(body, timestamp, SlackTestSupport.signature(timestamp, body), false),
                    new IngressRequestContext(Instant.now().plusMillis(200), new NeverCancelled()))
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(503, response.status());
            assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(800));
            assertEquals(0, ingress.offers.get());
        }
        Thread.sleep(25);
        assertEquals(0, ingress.offers.get());
    }

    @Test void cancellationInterruptsSqliteContentionWithoutLateSuccess() throws Exception {
        Path database = directory.resolve("cancelled.db"); DurableIngress ingress = new DurableIngress();
        CaptureRoute route = source(SlackBehaviorDescriptors.EVENTS, ingress, new AtomicReference<>(),
                SlackTestSupport.nodePackage(database));
        try (Connection blocker = lock(database)) {
            byte[] body = event("EvCancelled", "blocked"); String timestamp = SlackTestSupport.timestamp();
            TestCancellation cancellation = new TestCancellation();
            var future = route.handler.handle(request(body, timestamp, SlackTestSupport.signature(timestamp, body), false),
                    new IngressRequestContext(Instant.now().plusSeconds(5), cancellation)).toCompletableFuture();
            Thread.sleep(50); assertFalse(future.isDone());
            long cancelled = System.nanoTime(); cancellation.cancel();
            assertEquals(503, future.get(1, TimeUnit.SECONDS).status());
            assertTrue(System.nanoTime() - cancelled < TimeUnit.MILLISECONDS.toNanos(800));
            assertEquals(0, ingress.offers.get());
        }
        Thread.sleep(25);
        assertEquals(0, ingress.offers.get());
    }

    @Test void contextualCredentialResolutionIsCappedAndOversizedBodyFailsBeforeResolution() {
        DurableIngress ingress = new DurableIngress(); AtomicReference<Duration> requested = new AtomicReference<>();
        CaptureRoute route = source(SlackBehaviorDescriptors.EVENTS, ingress, requested, SlackTestSupport.nodePackage(directory.resolve("deadline.db")));
        byte[] body = event("EvDeadline", "hello"); String timestamp = SlackTestSupport.timestamp();
        IngressResponse response = route.handler.handle(request(body, timestamp, SlackTestSupport.signature(timestamp, body), false),
                new IngressRequestContext(Instant.now().plusSeconds(30), new NeverCancelled())).toCompletableFuture().join();
        assertEquals(200, response.status()); assertNotNull(requested.get());
        assertTrue(requested.get().compareTo(Duration.ofMillis(2_800)) <= 0);

        SlackNodePackage tiny = SlackTestSupport.nodePackage(directory.resolve("tiny.db"), 32);
        AtomicReference<Duration> untouched = new AtomicReference<>(); CaptureRoute tinyRoute = source(
                SlackBehaviorDescriptors.EVENTS, new DurableIngress(), untouched, tiny);
        assertEquals(400, signed(tinyRoute, body, false).status()); assertNull(untouched.get());
    }

    private CaptureRoute source(String behavior, DurableIngress ingress) {
        return source(behavior, ingress, new AtomicReference<>(), SlackTestSupport.nodePackage(directory.resolve("deliveries.db")));
    }
    private CaptureRoute source(String behavior, DurableIngress ingress, AtomicReference<Duration> requested,
                                SlackNodePackage nodePackage) {
        InboundSourceCapable capable = (InboundSourceCapable) SlackTestSupport.behavior(nodePackage, behavior);
        Context context = new Context(ingress); InboundSource source = capable.createSource(SlackTestSupport.node(behavior), context,
                credentials(requested)); source.start(context).toCompletableFuture().join();
        CaptureRoute capture = new CaptureRoute((ManagedIngressSource) source,
                behavior.equals(SlackBehaviorDescriptors.EVENTS) ? "/events" : "/commands");
        ((ManagedIngressSource) source).activateManagedIngress(capture).toCompletableFuture().join(); return capture;
    }
    private static NodePackageServices credentials(AtomicReference<Duration> requested) {
        return new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION); }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return new ai.ravenroot.api.node.service.NodeCredentialService() {
                    @Override public OutboundCall<CredentialLease> resolve(ai.ravenroot.api.execution.NodeMessage message,
                                                                          String reference, Duration deadline) {
                        return OutboundCall.failed(new AssertionError("message credential path not expected"));
                    }
                    @Override public OutboundCall<CredentialLease> resolve(InboundSourceContext context, String reference,
                                                                          Duration deadline) {
                        assertEquals("slack-signing-secret", reference); requested.set(deadline);
                        return OutboundCall.completed(new CredentialLease(SlackTestSupport.SECRET.toCharArray()));
                    }
                };
            }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() { return NodePackageServices.unavailable().outboundHttp(); }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() { return NodePackageServices.unavailable().outboundWebSocket(); }
        };
    }
    private static IngressResponse signed(CaptureRoute route, byte[] body, boolean command) {
        String timestamp = SlackTestSupport.timestamp();
        return route.handler.handle(request(body, timestamp, SlackTestSupport.signature(timestamp, body), command))
                .toCompletableFuture().join();
    }
    private static IngressRequest request(byte[] body, String timestamp, String signature, boolean command) {
        return new IngressRequest(new IngressPrincipal(SlackTestSupport.TENANT, "relay", "test", "WORKLOAD"),
                "POST", "", Map.of("content-type", command ? "application/x-www-form-urlencoded" : "application/json",
                "x-slack-signature", signature, "x-slack-request-timestamp", timestamp,
                "x-slack-retry-num", "1", "x-slack-retry-reason", "http_timeout"), body);
    }
    private static byte[] event(String id, String text) {
        return event(id, "message", SlackTestSupport.CHANNEL, text);
    }
    private static byte[] event(String id, String type, String channel, String text) {
        Map<String, Object> inner = new java.util.LinkedHashMap<>(); inner.put("type", type); inner.put("text", text);
        if (channel != null) inner.put("channel", channel);
        return SlackValues.jsonBytes(Map.of("type", "event_callback", "team_id", SlackTestSupport.TEAM,
                "api_app_id", SlackTestSupport.APPLICATION, "event_id", id,
                "event", inner));
    }
    private static byte[] form(Map<String, String> values) {
        return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry ->
                URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.UTF_8);
    }
    private static Connection lock(Path database) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        connection.createStatement().execute("BEGIN IMMEDIATE"); return connection;
    }
    private static final class CaptureRoute implements ai.ravenroot.api.ingress.IngressRouteAuthority {
        final ManagedIngressSource source; final String expectedPath; IngressRouteHandler handler;
        CaptureRoute(ManagedIngressSource source, String expectedPath) { this.source = source; this.expectedPath = expectedPath; }
        @Override public IngressRouteLease acquire(String routeId, String path, Set<String> methods, IngressRouteHandler handler) {
            assertEquals(expectedPath, path); assertEquals(Set.of("POST"), methods); this.handler = handler;
            return new IngressRouteLease() {
                @Override public String routeId() { return routeId; }
                @Override public IngressRouteOwner owner() { return new IngressRouteOwner(SlackConfiguration.PACKAGE_ID,
                        SlackTestSupport.TENANT, "deployment", "slack", 1); }
                @Override public void release() { }
            };
        }
    }
    private static final class Context implements InboundSourceContext {
        private final DurableIngress ingress;
        private final SecurityContext identity = new SecurityContext("request", SlackTestSupport.TENANT,
                "relay", PrincipalType.WORKLOAD, "test");
        Context(DurableIngress ingress) { this.ingress = ingress; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment"); }
        @Override public String nodeId() { return "slack"; }
        @Override public SecurityContext identity() { return identity; }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { fail(reason); }
        @Override public void reportHealthy() { }
    }
    private static final class DurableIngress implements TrustedIngress {
        final Set<String> keys = new HashSet<>(); final AtomicInteger offers = new AtomicInteger();
        final List<Object> payloads = new ArrayList<>();
        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            throw new AssertionError("volatile offer not expected");
        }
        @Override public int bufferCapacity() { return 8; }
        @Override public ai.ravenroot.api.deployment.IngressOverflowPolicy overflowPolicy() {
            return ai.ravenroot.api.deployment.IngressOverflowPolicy.REJECT;
        }
        @Override public synchronized IngressReceipt offerDurably(SecurityContext security, IngressTarget target,
                                                                  Object payload, String sourceId, String key) {
            offers.incrementAndGet(); payloads.add(payload);
            return keys.add(key) ? new IngressReceipt.DurablyCommitted(key) : new IngressReceipt.Duplicate(key);
        }
    }
    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
    private static final class TestCancellation implements CancellationSignal {
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>(); private volatile boolean cancelled;
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) { if (cancelled) listener.run(); else listeners.add(listener); }
        void cancel() { cancelled = true; listeners.forEach(Runnable::run); }
    }
}
