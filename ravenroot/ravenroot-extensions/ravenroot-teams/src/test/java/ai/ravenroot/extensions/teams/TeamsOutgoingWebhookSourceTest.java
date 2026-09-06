package ai.ravenroot.extensions.teams;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TeamsOutgoingWebhookSourceTest {
    @TempDir Path directory;

    @Test void relaySignatureIsVerifiedBeforeParsingAndAuthorizationNeverReachesPackage() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress);
        byte[] malformed = "not-json".getBytes(StandardCharsets.UTF_8);
        assertEquals(400, route.handler.handle(request(malformed, "not-base64")).toCompletableFuture().join().status());
        IngressResponse rejected = route.handler.handle(request(malformed, java.util.Base64.getEncoder().encodeToString(new byte[32]))).toCompletableFuture().join();
        assertEquals(401, rejected.status()); assertEquals(0, ingress.offers.get());
        byte[] valid = activity("activity-1", TeamsTestSupport.NOW, TeamsTestSupport.CHANNEL, "hello");
        IngressRequest projected = request(valid, TeamsTestSupport.signature(valid));
        assertFalse(projected.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase("authorization")));
        IngressResponse accepted = route.handler.handle(projected).toCompletableFuture().join();
        assertEquals(200, accepted.status());
        assertEquals(Map.of("type", "message", "text", "Accepted."), TeamsValues.json(accepted.body()));
    }

    @Test void synchronousCredentialFailureAndTimedOutResolutionFailClosedAndCancelWork() {
        DurableIngress ingress = new DurableIngress();
        NodePackageServices throwing = credentialServices((context, reference, deadline) -> {
            throw new IllegalStateException("private resolver failure");
        });
        CaptureRoute failed = source(ingress, throwing);
        byte[] body = activity("activity-failure", TeamsTestSupport.NOW, TeamsTestSupport.CHANNEL, "hello");
        assertEquals(503, signed(failed, body).status());

        CompletableFuture<CredentialLease> pending = new CompletableFuture<>();
        AtomicInteger cancellations = new AtomicInteger();
        OutboundCall<CredentialLease> call = new OutboundCall<>() {
            @Override public java.util.concurrent.CompletionStage<CredentialLease> completion() { return pending; }
            @Override public boolean cancel() { cancellations.incrementAndGet(); return pending.cancel(true); }
        };
        CaptureRoute timedOut = source(ingress, credentialServices((context, reference, deadline) -> call));
        var window = new IngressRequestContext(Instant.now().plusMillis(40), new NeverCancelled());
        assertEquals(503, timedOut.handler.handle(request(body, TeamsTestSupport.signature(body)), window)
                .toCompletableFuture().join().status());
        assertEquals(1, cancellations.get());
        assertEquals(0, ingress.offers.get());
    }

    @Test void durableIdentitySurvivesRestartAndChangedBodyIsRejected() {
        DurableIngress ingress = new DurableIngress(); byte[] body = activity("activity-2", TeamsTestSupport.NOW,
                TeamsTestSupport.CHANNEL, "hello");
        CaptureRoute first = source(ingress);
        assertEquals(200, signed(first, body).status()); first.source.stop().toCompletableFuture().join();
        CaptureRoute restarted = source(ingress);
        assertEquals(200, signed(restarted, body).status());
        assertEquals(2, ingress.offers.get()); assertEquals(1, ingress.keys.size());
        assertEquals(403, signed(restarted, activity("activity-2", TeamsTestSupport.NOW,
                TeamsTestSupport.CHANNEL, "changed")).status());
        assertEquals(2, ingress.offers.get());
    }

    @Test void timestampTenantTeamAndChannelAuthorityFailClosed() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress);
        assertEquals(401, signed(route, activity("old", TeamsTestSupport.NOW.minusSeconds(301),
                TeamsTestSupport.CHANNEL, "old")).status());
        assertEquals(403, signed(route, activity("channel", TeamsTestSupport.NOW,
                "19:other@thread.tacv2", "blocked")).status());
        assertEquals(0, ingress.offers.get());
    }

    @Test void durableReceiptControlsSynchronousAcknowledgement() {
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress);
        byte[] body = activity("activity-3", TeamsTestSupport.NOW, TeamsTestSupport.CHANNEL, "hello");
        ingress.receipt = new IngressReceipt.Refused("buffer full");
        assertEquals(429, signed(route, body).status());
        ingress.receipt = new IngressReceipt.VolatileCustody();
        assertEquals(503, signed(route, body).status());
        ingress.receipt = new IngressReceipt.Duplicate("duplicate");
        assertEquals(200, signed(route, body).status());
    }

    @Test void credentialResolutionUsesInboundContextAndBoundedDeadline() {
        AtomicReference<Duration> requested = new AtomicReference<>();
        DurableIngress ingress = new DurableIngress(); CaptureRoute route = source(ingress, requested);
        byte[] body = activity("activity-4", TeamsTestSupport.NOW, TeamsTestSupport.CHANNEL, "hello");
        assertEquals(200, signed(route, body).status());
        assertNotNull(requested.get()); assertTrue(requested.get().compareTo(Duration.ofMillis(4_500)) <= 0);
    }

    private CaptureRoute source(DurableIngress ingress) { return source(ingress, new AtomicReference<>()); }
    private CaptureRoute source(DurableIngress ingress, AtomicReference<Duration> requested) {
        return source(ingress, credentials(requested));
    }
    private CaptureRoute source(DurableIngress ingress, NodePackageServices services) {
        TeamsNodePackage nodePackage = TeamsTestSupport.nodePackage(directory.resolve("deliveries.db"));
        InboundSourceCapable capable = (InboundSourceCapable) TeamsTestSupport.behavior(
                nodePackage, TeamsBehaviorDescriptors.OUTGOING_WEBHOOK);
        Context context = new Context(ingress);
        InboundSource source = capable.createSource(TeamsTestSupport.node(TeamsBehaviorDescriptors.OUTGOING_WEBHOOK),
                context, services);
        source.start(context).toCompletableFuture().join();
        CaptureRoute capture = new CaptureRoute((ManagedIngressSource) source);
        ((ManagedIngressSource) source).activateManagedIngress(capture).toCompletableFuture().join();
        return capture;
    }

    private static NodePackageServices credentials(AtomicReference<Duration> requested) {
        return credentialServices((context, reference, deadline) -> {
            assertEquals("teams-signing-secret", reference); requested.set(deadline);
            return OutboundCall.completed(new CredentialLease(TeamsTestSupport.SECRET.toCharArray()));
        });
    }
    private static NodePackageServices credentialServices(SourceResolver resolver) {
        return new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() {
                return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION);
            }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return new ai.ravenroot.api.node.service.NodeCredentialService() {
                    @Override public OutboundCall<CredentialLease> resolve(ai.ravenroot.api.execution.NodeMessage message,
                                                                          String reference, Duration deadline) {
                        return OutboundCall.failed(new AssertionError("message credential path not expected"));
                    }
                    @Override public OutboundCall<CredentialLease> resolve(InboundSourceContext context,
                                                                          String reference, Duration deadline) {
                        return resolver.resolve(context, reference, deadline);
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
    @FunctionalInterface private interface SourceResolver {
        OutboundCall<CredentialLease> resolve(InboundSourceContext context, String reference, Duration deadline);
    }

    private static IngressResponse signed(CaptureRoute route, byte[] body) {
        return route.handler.handle(request(body, TeamsTestSupport.signature(body))).toCompletableFuture().join();
    }
    private static IngressRequest request(byte[] body, String signature) {
        return new IngressRequest(new IngressPrincipal(TeamsTestSupport.TENANT, "relay", "test", "WORKLOAD"),
                "POST", "", Map.of("content-type", "application/json",
                "x-ravenroot-teams-signature", signature), body);
    }
    private static byte[] activity(String id, Instant timestamp, String channel, String text) {
        return TeamsValues.jsonBytes(Map.of("type", "message", "id", id, "timestamp", timestamp.toString(),
                "channelData", Map.of("tenant", Map.of("id", TeamsTestSupport.MICROSOFT_TENANT),
                        "team", Map.of("id", TeamsTestSupport.TEAM), "channel", Map.of("id", channel)),
                "from", Map.of("id", TeamsTestSupport.USER), "recipient", Map.of("id", "28:webhook"),
                "conversation", Map.of("id", "19:conversation@thread.tacv2"), "text", text));
    }

    private static final class CaptureRoute implements ai.ravenroot.api.ingress.IngressRouteAuthority {
        final ManagedIngressSource source; IngressRouteHandler handler;
        CaptureRoute(ManagedIngressSource source) { this.source = source; }
        @Override public IngressRouteLease acquire(String routeId, String path, Set<String> methods,
                                                   IngressRouteHandler handler) {
            assertEquals("/outgoing", path); assertEquals(Set.of("POST"), methods); this.handler = handler;
            return new IngressRouteLease() {
                @Override public String routeId() { return routeId; }
                @Override public IngressRouteOwner owner() {
                    return new IngressRouteOwner(TeamsConfiguration.PACKAGE_ID, TeamsTestSupport.TENANT,
                            "deployment", "teams", 1);
                }
                @Override public void release() { }
            };
        }
    }
    private static final class Context implements InboundSourceContext {
        private final DurableIngress ingress;
        private final SecurityContext identity = new SecurityContext("request", TeamsTestSupport.TENANT,
                "relay", PrincipalType.WORKLOAD, "test");
        Context(DurableIngress ingress) { this.ingress = ingress; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment"); }
        @Override public String nodeId() { return "teams"; }
        @Override public SecurityContext identity() { return identity; }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { fail(reason); }
        @Override public void reportHealthy() { }
    }
    private static final class DurableIngress implements TrustedIngress {
        final Set<String> keys = new HashSet<>(); final AtomicInteger offers = new AtomicInteger();
        final List<Object> payloads = new ArrayList<>(); volatile IngressReceipt receipt;
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
    private static final class NeverCancelled implements ai.ravenroot.api.execution.CancellationSignal {
        @Override public boolean cancelled() { return false; }
        @Override public void onCancel(Runnable listener) { }
    }
}
