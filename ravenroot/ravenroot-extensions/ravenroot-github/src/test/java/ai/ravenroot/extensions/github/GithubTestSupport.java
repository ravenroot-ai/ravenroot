package ai.ravenroot.extensions.github;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.ingress.IngressAuthorityDeclaration;
import ai.ravenroot.api.ingress.IngressRequestProjectionPolicy;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class GithubTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "automation";
    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private GithubTestSupport() { }

    static GithubConfiguration configuration(Path store) {
        var authority = new IngressAuthorityDeclaration(GithubConfiguration.PACKAGE_ID, "main", "/managed/github",
                Set.of("github:webhook"), 8, 32, 1_048_576, 4_096, Duration.ofSeconds(5));
        var projection = new IngressRequestProjectionPolicy(GithubConfiguration.PACKAGE_ID,
                Set.of("x-hub-signature-256", "x-github-delivery", "x-github-event"), "x-github-delivery",
                256, 1, 256, 3, 1_024, 512);
        var project = new GithubProfile.ProjectPolicy("PVT_example", "PVTSSF_status", "PVTF_attempts",
                "PVTF_generation", Map.of("Todo", "todo-id", "InProgress", "progress-id", "Done", "done-id"),
                Set.of("Todo->InProgress", "InProgress->Done", "InProgress->Todo"), "Todo->InProgress");
        var release = new GithubProfile.ReleasePolicy("main", "ravenroot/pom.xml", ".changes",
                Set.of("none", "patch", "minor", "major"), 256);
        var profile = new GithubProfile(PROFILE, TENANT, URI.create("https://api.github.com"), "example", "service",
                1234, 5678, "example-reviewer[bot]", "github-installation", "github-installation-token",
                "github-webhook-secret", "/automation", Map.of("pull_request", Set.of("opened", "synchronize"),
                "workflow_run", Set.of("completed")), project, Set.of(1001L, 1002L), release,
                5_000, 1_048_576, 1_048_576, 8, 4, 1);
        return new GithubConfiguration(authority, projection,
                new GithubConfiguration.StorePolicy(store, 100, 24, 1_000),
                Map.of(TENANT + "\u0000" + PROFILE, profile));
    }

    static GithubNodePackage nodePackage(Path store) { return new GithubNodePackage(configuration(store)); }

    static NodeBehavior behavior(GithubNodePackage nodePackage, String id) {
        return nodePackage.behaviors().stream().filter(value -> value.descriptor().behavior().equals(id)).findFirst().orElseThrow();
    }

    static NodeConfiguration node(String behavior) {
        return new NodeConfiguration("github", behavior, Map.of("githubProfile", PROFILE));
    }

    static NodeMessage message(Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", TENANT, "operator", PrincipalType.WORKLOAD, "test"),
                id, UUID.randomUUID(), "github", payload, Map.of());
    }

    static byte[] json(Object value) {
        return ai.ravenroot.api.payload.PayloadJson.write(
                ai.ravenroot.api.payload.PayloadValue.fromJava(value, GithubValues.LIMITS))
                .getBytes(StandardCharsets.UTF_8);
    }

    static final class HttpHarness implements NodePackageServices {
        final ArrayDeque<OutboundHttpResponse> replies = new ArrayDeque<>();
        final List<OutboundHttpRequest> requests = new ArrayList<>();
        final java.util.concurrent.CountDownLatch requestArrived = new java.util.concurrent.CountDownLatch(1);
        volatile OutboundCall<OutboundHttpResponse> pending;
        volatile int pendingAtRequest = -1;
        volatile Runnable onRequest;

        HttpHarness reply(int status, Object body) {
            replies.add(new OutboundHttpResponse(status, Map.of(), json(body))); return this;
        }

        HttpHarness reply(int status, Map<String, List<String>> headers, Object body) {
            replies.add(new OutboundHttpResponse(status, headers, json(body))); return this;
        }

        @Override public Set<NodePackageCapability> capabilities() { return Set.of(
                NodePackageCapability.OUTBOUND_HTTP, NodePackageCapability.CREDENTIAL_RESOLUTION); }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return (message, reference, deadline) -> OutboundCall.failed(new AssertionError("credential resolution not expected"));
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, request) -> {
                requests.add(request);
                requestArrived.countDown();
                Runnable hook = onRequest;
                if (hook != null) { onRequest = null; hook.run(); }
                if (pending != null && (pendingAtRequest < 0 || requests.size() == pendingAtRequest)) return pending;
                OutboundHttpResponse reply = replies.poll();
                return reply == null ? OutboundCall.failed(new AssertionError("unexpected " + request.method() + " " + request.destination()))
                        : OutboundCall.completed(reply);
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }
}
