package ai.ravenroot.extensions.mattermost;

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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MattermostOutgoingWebhookSourceTest {
    @TempDir Path directory;

    @Test void jsonCommitsThenDeduplicatesAcrossRestartAndRejectsChangedBody() {
        DurableIngress ingress = new DurableIngress(); byte[] body = json("hello", MattermostTestSupport.TOKEN);
        CaptureRoute first = source(ingress); assertEquals(200, call(first, body, "application/json").status());
        first.source.stop().toCompletableFuture().join();
        CaptureRoute restarted = source(ingress); assertEquals(200, call(restarted, body, "application/json").status());
        assertEquals(2, ingress.offers.get()); assertEquals(1, ingress.keys.size());
        assertEquals(409, call(restarted, json("changed", MattermostTestSupport.TOKEN), "application/json").status());
        assertEquals(2, ingress.offers.get());
    }

    @Test void acceptsOfficialFormAndDropsTokenBeforeDurableOffer() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress);
        assertEquals(200, call(route, form("deploy", MattermostTestSupport.TOKEN),
                "application/x-www-form-urlencoded").status());
        assertEquals(1, ingress.payloads.size()); String payload = ingress.payloads.getFirst().toString();
        assertTrue(payload.contains("deploy")); assertTrue(payload.contains(MattermostTestSupport.POST));
        assertFalse(payload.contains(MattermostTestSupport.TOKEN));
    }

    @Test void replayDigestIsCredentialFreeAndCanonicalAcrossOfficialEncodings() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress);
        assertEquals(200, call(route, json("same", MattermostTestSupport.TOKEN), "application/json").status());
        assertEquals(200, call(route, form("same", MattermostTestSupport.TOKEN),
                "application/x-www-form-urlencoded").status());
        assertEquals(2, ingress.offers.get()); assertEquals(1, ingress.keys.size());
    }

    @Test void replayStillConsumesAdmissionRateBeforeAnotherDurableOffer() {
        Path database = directory.resolve("rate.db");
        MattermostConfiguration configuration = MattermostTestSupport.configuration(database, 1_048_576, 100, 1);
        MattermostNodePackage nodePackage = new MattermostNodePackage(configuration,
                new SqliteMattermostDeliveryStore(configuration.store()), MattermostTestSupport.fixedClock());
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(
                ingress, "rate-source", new AtomicReference<>(), nodePackage);
        byte[] body = json("same", MattermostTestSupport.TOKEN);
        assertEquals(200, call(route, body, "application/json").status());
        assertEquals(429, call(route, body, "application/json").status());
        assertEquals(1, ingress.offers.get());
    }

    @Test void tokenTeamChannelAndClosedSchemaFailBeforeDurability() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress);
        assertEquals(401, call(route, json("hello", "wrong-token"), "application/json").status());
        Map<String, Object> wrongTeam = callback("hello", MattermostTestSupport.TOKEN);
        wrongTeam.put("team_id", "ffffffffffffffffffffffffff");
        assertEquals(403, call(route, MattermostValues.jsonBytes(wrongTeam), "application/json").status());
        Map<String, Object> unknown = callback("hello", MattermostTestSupport.TOKEN); unknown.put("secret", "forged");
        assertEquals(400, call(route, MattermostValues.jsonBytes(unknown), "application/json").status());
        assertEquals(0, ingress.offers.get());
    }

    @Test void onlyDurableAndDuplicateReceiptsAckAndCapacityIsDistinct() {
        for (var outcome : List.of(new Case(new IngressReceipt.DurablyCommitted("k"), 200),
                new Case(new IngressReceipt.Duplicate("k"), 200),
                new Case(new IngressReceipt.Refused("buffer full"), 429),
                new Case(new IngressReceipt.Refused("stopped"), 503),
                new Case(new IngressReceipt.VolatileCustody(), 503),
                new Case(new IngressReceipt.Ambiguous("k", "uncertain"), 503))) {
            DurableIngress ingress = new DurableIngress(); ingress.receipt = outcome.receipt;
            assertEquals(outcome.status, call(source(ingress, "node-" + outcome.status + "-"
                    + outcome.receipt.getClass().getSimpleName()), json("hello", MattermostTestSupport.TOKEN),
                    "application/json").status());
        }
    }

    @Test void staleHandlerAndTenantMismatchAreGenerationFenced() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress);
        route.source.stop().toCompletableFuture().join();
        assertEquals(503, call(route, json("hello", MattermostTestSupport.TOKEN), "application/json").status());
        IngressRequest wrongTenant = new IngressRequest(new IngressPrincipal("tenant-b", "relay", "test", "WORKLOAD"),
                "POST", "", Map.of("content-type", "application/json"), json("hello", MattermostTestSupport.TOKEN));
        assertEquals(503, route.handler.handle(wrongTenant).toCompletableFuture().join().status());
        assertEquals(0, ingress.offers.get());
    }

    @Test void credentialResolutionAndSqliteContentionHonorAckDeadline() throws Exception {
        DurableIngress ingress = new DurableIngress(); AtomicReference<Duration> requested = new AtomicReference<>();
        CaptureRoute route = source(ingress, "mattermost", requested);
        assertEquals(200, call(route, json("hello", MattermostTestSupport.TOKEN), "application/json").status());
        assertNotNull(requested.get()); assertTrue(requested.get().compareTo(Duration.ofMillis(2_800)) <= 0);

        Path database = directory.resolve("busy.db"); MattermostConfiguration configuration = MattermostTestSupport.configuration(database);
        MattermostNodePackage nodePackage = new MattermostNodePackage(configuration,
                new SqliteMattermostDeliveryStore(configuration.store()), MattermostTestSupport.fixedClock());
        CaptureRoute contended = source(new DurableIngress(), "contended", new AtomicReference<>(), nodePackage);
        try (var lock = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database)) {
            lock.createStatement().execute("BEGIN IMMEDIATE");
            long started = System.nanoTime();
            IngressResponse response = contended.handler.handle(request(json("busy", MattermostTestSupport.TOKEN),
                            "application/json"), new IngressRequestContext(Instant.now().plusMillis(150), new NeverCancelled()))
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(503, response.status()); assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
        }
    }

    private CaptureRoute source(DurableIngress ingress) { return source(ingress, "mattermost"); }
    private CaptureRoute source(DurableIngress ingress, String nodeId) {
        return source(ingress, nodeId, new AtomicReference<>());
    }
    private CaptureRoute source(DurableIngress ingress, String nodeId, AtomicReference<Duration> requested) {
        return source(ingress, nodeId, requested, MattermostTestSupport.nodePackage(directory.resolve("deliveries.db")));
    }
    private CaptureRoute source(DurableIngress ingress, String nodeId, AtomicReference<Duration> requested,
                                MattermostNodePackage nodePackage) {
        InboundSourceCapable capable = (InboundSourceCapable) MattermostTestSupport.behavior(nodePackage,
                MattermostBehaviorDescriptors.OUTGOING_WEBHOOK);
        Context context = new Context(ingress, nodeId); InboundSource inbound = capable.createSource(
                MattermostTestSupport.node(MattermostBehaviorDescriptors.OUTGOING_WEBHOOK), context, credentials(requested));
        inbound.start(context).toCompletableFuture().join(); CaptureRoute route = new CaptureRoute((ManagedIngressSource) inbound);
        ((ManagedIngressSource) inbound).activateManagedIngress(route).toCompletableFuture().join(); return route;
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
                    @Override public OutboundCall<CredentialLease> resolve(InboundSourceContext context,
                                                                          String reference, Duration deadline) {
                        assertEquals("mattermost-outgoing-token", reference); requested.set(deadline);
                        return OutboundCall.completed(new CredentialLease(MattermostTestSupport.TOKEN.toCharArray()));
                    }
                };
            }
            @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
                return NodePackageServices.unavailable().outboundHttp();
            }
            @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
                return NodePackageServices.unavailable().outboundWebSocket();
            }
        };
    }
    private static IngressResponse call(CaptureRoute route, byte[] body, String contentType) {
        return route.handler.handle(request(body, contentType)).toCompletableFuture().join();
    }
    private static IngressRequest request(byte[] body, String contentType) {
        return new IngressRequest(new IngressPrincipal(MattermostTestSupport.TENANT, "relay", "test", "WORKLOAD"),
                "POST", "", Map.of("content-type", contentType), body);
    }
    private static byte[] json(String text, String token) { return MattermostValues.jsonBytes(callback(text, token)); }
    private static Map<String, Object> callback(String text, String token) {
        return new java.util.LinkedHashMap<>(Map.ofEntries(Map.entry("token", token),
                Map.entry("team_id", MattermostTestSupport.TEAM), Map.entry("team_domain", "ops"),
                Map.entry("channel_id", MattermostTestSupport.CHANNEL), Map.entry("channel_name", "town-square"),
                Map.entry("timestamp", 1_725_451_200L), Map.entry("user_id", MattermostTestSupport.USER),
                Map.entry("user_name", "operator"), Map.entry("post_id", MattermostTestSupport.POST),
                Map.entry("text", text), Map.entry("trigger_word", "#deploy")));
    }
    private static byte[] form(String text, String token) {
        return callback(text, token).entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue().toString(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.UTF_8);
    }
    private record Case(IngressReceipt receipt, int status) { }
    private static final class CaptureRoute implements ai.ravenroot.api.ingress.IngressRouteAuthority {
        final ManagedIngressSource source; IngressRouteHandler handler;
        CaptureRoute(ManagedIngressSource source) { this.source = source; }
        @Override public IngressRouteLease acquire(String routeId, String path, Set<String> methods,
                                                   IngressRouteHandler handler) {
            assertEquals("/outgoing", path); assertEquals(Set.of("POST"), methods); this.handler = handler;
            return new IngressRouteLease() {
                @Override public String routeId() { return routeId; }
                @Override public IngressRouteOwner owner() {
                    return new IngressRouteOwner(MattermostConfiguration.PACKAGE_ID,
                            MattermostTestSupport.TENANT, "deployment", "mattermost", 1);
                }
                @Override public void release() { }
            };
        }
    }
    private static final class Context implements InboundSourceContext {
        private final DurableIngress ingress; private final String nodeId;
        private final SecurityContext identity = new SecurityContext("request", MattermostTestSupport.TENANT,
                "relay", PrincipalType.WORKLOAD, "test");
        Context(DurableIngress ingress, String nodeId) { this.ingress = ingress; this.nodeId = nodeId; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment"); }
        @Override public String nodeId() { return nodeId; }
        @Override public SecurityContext identity() { return identity; }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { fail(reason); }
        @Override public void reportHealthy() { }
    }
    private static final class DurableIngress implements TrustedIngress {
        final Set<String> keys = new HashSet<>(); final AtomicInteger offers = new AtomicInteger();
        final List<Object> payloads = new ArrayList<>(); IngressReceipt receipt;
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
            if (receipt != null) return receipt;
            return keys.add(key) ? new IngressReceipt.DurablyCommitted(key) : new IngressReceipt.Duplicate(key);
        }
    }
    private static final class NeverCancelled implements CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
