package ai.ravenroot.extensions.github;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GithubEventsSourceTest {
    @TempDir Path directory;

    @Test void signatureIsVerifiedBeforeParsingOrDurableOffer() throws Exception {
        DurableIngress ingress = new DurableIngress();
        CaptureRoute route = source(ingress);
        byte[] malformed = "not-json".getBytes(StandardCharsets.UTF_8);
        IngressResponse response = route.handler.handle(request(malformed, "sha256=" + "0".repeat(64), "delivery-1",
                GithubTestSupport.TENANT)).toCompletableFuture().join();
        assertEquals(401, response.status());
        assertEquals(0, ingress.offers.get());
    }

    @Test void validDeliveryCommitsOnceAndIsDuplicateAfterSourceRestart() throws Exception {
        DurableIngress ingress = new DurableIngress();
        byte[] body = GithubValues.jsonBytes(Map.of("action", "opened", "repository", Map.of("id", 1234L),
                "installation", Map.of("id", 5678L)));
        String signature = signature("test-secret", body);
        CaptureRoute first = source(ingress);
        assertEquals(202, first.handler.handle(request(body, signature, "delivery-2", GithubTestSupport.TENANT))
                .toCompletableFuture().join().status());
        first.source.stop().toCompletableFuture().join();
        CaptureRoute restarted = source(ingress);
        IngressResponse replay = restarted.handler.handle(request(body, signature, "delivery-2", GithubTestSupport.TENANT))
                .toCompletableFuture().join();
        assertEquals(202, replay.status());
        assertTrue(new String(replay.body(), StandardCharsets.UTF_8).contains("duplicate"));
        assertEquals(2, ingress.offers.get());
        assertEquals(1, ingress.keys.size());

        byte[] collision = GithubValues.jsonBytes(Map.of("action", "synchronize",
                "repository", Map.of("id", 1234L), "installation", Map.of("id", 5678L)));
        IngressResponse rejected = restarted.handler.handle(request(collision, signature("test-secret", collision),
                "delivery-2", GithubTestSupport.TENANT)).toCompletableFuture().join();
        assertEquals(409, rejected.status());
        assertEquals(2, ingress.offers.get());
    }

    @Test void emptyActionAllowlistAcceptsOnlyEventsWithoutAction() throws Exception {
        DurableIngress ingress = new DurableIngress();
        GithubConfiguration base = GithubTestSupport.configuration(directory.resolve("empty-action.db"));
        GithubProfile old = base.profile(GithubTestSupport.TENANT, GithubTestSupport.PROFILE).orElseThrow();
        GithubProfile narrowed = new GithubProfile(old.name(), old.tenantId(), old.apiOrigin(), old.owner(),
                old.repository(), old.repositoryId(), old.installationId(), old.reviewerLogin(),
                old.credentialBindingId(), old.credentialReference(), old.webhookSecretReference(), old.route(),
                Map.of("pull_request", Set.of()), old.project(), old.workflowIds(), old.release(), old.timeoutMs(),
                old.maxRequestBytes(), old.maxResponseBytes(), old.maxConcurrency(), old.maxPolls(), old.pollIntervalMs());
        GithubNodePackage nodePackage = new GithubNodePackage(new GithubConfiguration(base.authority(), base.projection(),
                base.store(), Map.of(GithubTestSupport.TENANT + "\u0000" + GithubTestSupport.PROFILE, narrowed)));
        CaptureRoute route = source(ingress, nodePackage);
        byte[] absent = GithubValues.jsonBytes(Map.of("repository", Map.of("id", 1234L),
                "installation", Map.of("id", 5678L)));
        assertEquals(202, route.handler.handle(request(absent, signature("test-secret", absent), "no-action",
                GithubTestSupport.TENANT)).toCompletableFuture().join().status());
        byte[] present = GithubValues.jsonBytes(Map.of("action", "opened", "repository", Map.of("id", 1234L),
                "installation", Map.of("id", 5678L)));
        assertEquals(403, route.handler.handle(request(present, signature("test-secret", present), "has-action",
                GithubTestSupport.TENANT)).toCompletableFuture().join().status());
    }

    @Test void trustedTenantCannotBeOverriddenByRelayPrincipalOrSignedBody() throws Exception {
        DurableIngress ingress = new DurableIngress();
        byte[] body = GithubValues.jsonBytes(Map.of("action", "opened", "repository", Map.of("id", 1234L),
                "installation", Map.of("id", 5678L), "tenantId", "tenant-b"));
        CaptureRoute route = source(ingress);
        IngressResponse response = route.handler.handle(request(body, signature("test-secret", body), "delivery-3", "tenant-b"))
                .toCompletableFuture().join();
        assertEquals(503, response.status());
        assertEquals(0, ingress.offers.get());
    }

    private CaptureRoute source(DurableIngress ingress) {
        return source(ingress, GithubTestSupport.nodePackage(directory.resolve("operations.db")));
    }

    private CaptureRoute source(DurableIngress ingress, GithubNodePackage nodePackage) {
        InboundSourceCapable behavior = (InboundSourceCapable) GithubTestSupport.behavior(nodePackage, "github-events-source");
        Context context = new Context(ingress);
        InboundSource source = behavior.createSource(GithubTestSupport.node("github-events-source"), context, credentials());
        source.start(context).toCompletableFuture().join();
        CaptureRoute capture = new CaptureRoute((ManagedIngressSource) source);
        ((ManagedIngressSource) source).activateManagedIngress(capture).toCompletableFuture().join();
        return capture;
    }

    private static NodePackageServices credentials() {
        return new NodePackageServices() {
            @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.CREDENTIAL_RESOLUTION); }
            @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
                return new ai.ravenroot.api.node.service.NodeCredentialService() {
                    @Override public OutboundCall<CredentialLease> resolve(ai.ravenroot.api.execution.NodeMessage message,
                                                                          String reference, java.time.Duration deadline) {
                        return OutboundCall.failed(new AssertionError("message credential path not expected"));
                    }
                    @Override public OutboundCall<CredentialLease> resolve(InboundSourceContext context, String reference,
                                                                          java.time.Duration deadline) {
                        assertEquals("github-webhook-secret", reference);
                        assertEquals(GithubTestSupport.TENANT, context.identity().tenantId());
                        return OutboundCall.completed(new CredentialLease("test-secret".toCharArray()));
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

    private static IngressRequest request(byte[] body, String signature, String delivery, String tenant) {
        return new IngressRequest(new IngressPrincipal(tenant, "relay", "test", "WORKLOAD"), "POST", "",
                Map.of("x-hub-signature-256", signature, "x-github-delivery", delivery,
                        "x-github-event", "pull_request"), body);
    }

    private static String signature(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static final class CaptureRoute implements ai.ravenroot.api.ingress.IngressRouteAuthority {
        final ManagedIngressSource source; IngressRouteHandler handler;
        CaptureRoute(ManagedIngressSource source) { this.source = source; }
        @Override public IngressRouteLease acquire(String routeId, String path, Set<String> methods, IngressRouteHandler handler) {
            assertEquals("/automation", path); assertEquals(Set.of("POST"), methods); this.handler = handler;
            return new IngressRouteLease() {
                @Override public String routeId() { return routeId; }
                @Override public IngressRouteOwner owner() { return new IngressRouteOwner(GithubConfiguration.PACKAGE_ID,
                        GithubTestSupport.TENANT, "deployment", "github", 1); }
                @Override public void release() { }
            };
        }
    }

    private static final class Context implements InboundSourceContext {
        private final DurableIngress ingress;
        private final SecurityContext identity = new SecurityContext("request", GithubTestSupport.TENANT,
                "relay", PrincipalType.WORKLOAD, "test");
        Context(DurableIngress ingress) { this.ingress = ingress; }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("deployment"); }
        @Override public String nodeId() { return "github"; }
        @Override public SecurityContext identity() { return identity; }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { fail(reason); }
        @Override public void reportHealthy() { }
    }

    private static final class DurableIngress implements TrustedIngress {
        final Set<String> keys = new HashSet<>(); final AtomicInteger offers = new AtomicInteger();
        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            throw new AssertionError("volatile offer not expected");
        }
        @Override public int bufferCapacity() { return 8; }
        @Override public ai.ravenroot.api.deployment.IngressOverflowPolicy overflowPolicy() {
            return ai.ravenroot.api.deployment.IngressOverflowPolicy.REJECT;
        }
        @Override public synchronized IngressReceipt offerDurably(SecurityContext security, IngressTarget target,
                                                                  Object payload, String sourceId, String key) {
            offers.incrementAndGet();
            return keys.add(key) ? new IngressReceipt.DurablyCommitted(key) : new IngressReceipt.Duplicate(key);
        }
    }
}
