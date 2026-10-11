package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.execution.NodeMessage;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class SystemOneTestSupport {
    private SystemOneTestSupport() { }

    static final String SPECIFICATION = """
            {
              "contract":"ravenroot.system-one/specification/1",
              "id":"email-triage/v1",
              "questions":{
                "category":{"type":"choice","instructions":"Classify this email.",
                  "criteria":{"notify":"Needs prompt attention","archive":"No action","review":null}},
                "action_required":{"type":"noul","instructions":"Does this require an action?"},
                "importance":{"type":"score","instructions":"How important is this?",
                  "criteria":["low","medium","high"]}
              }
            }
            """;

    static SystemOneProfile profile(String model) {
        return profile(model, 255, 2);
    }

    static SystemOneProfile profile(String model, int choiceOptions, int concurrency) {
        return new SystemOneProfile("local", URI.create("https://system-one.example.test/v1/systemone"),
                model, Optional.empty(), 5_000, 1024 * 1024, 1024 * 1024, concurrency,
                256 * 1024, 32, 8 * 1024, choiceOptions, 10,
                SystemOneProfile.PORTABLE_TYPES, 0.001, null);
    }

    static SystemOneProfileResolver resolving(SystemOneProfile profile) {
        return name -> profile.name().equals(name) ? Optional.of(profile) : Optional.empty();
    }

    static NodeConfiguration configuration(String specification) {
        return new NodeConfiguration("evaluate", SystemOneEvaluateNodeBehavior.BEHAVIOR,
                Map.of("profile", "local", "specification", specification));
    }

    static NodeMessage message(Object payload) { return message(payload, Map.of()); }

    static NodeMessage message(Object payload, Map<String, Object> attributes) {
        return new NodeMessage(new SecurityContext("request", "tenant-a", "subject",
                PrincipalType.WORKLOAD, "issuer"), UUID.randomUUID(), UUID.randomUUID(),
                "evaluate", payload, attributes);
    }

    static byte[] fixture(String name) {
        try (var input = SystemOneTestSupport.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) throw new IllegalArgumentException(name);
            return input.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String encodedProfile(String json) {
        return java.util.Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static final class HttpDouble implements NodePackageServices {
        final AtomicReference<OutboundHttpRequest> request = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException synchronousFailure;
        volatile CompletableFuture<OutboundHttpResponse> response = CompletableFuture.completedFuture(
                response("jev-response.json"));

        static OutboundHttpResponse response(String fixture) {
            return new OutboundHttpResponse(200, Map.of("content-type", java.util.List.of("application/json")),
                    SystemOneTestSupport.fixture(fixture));
        }

        @Override public Set<NodePackageCapability> capabilities() {
            return Set.of(NodePackageCapability.OUTBOUND_HTTP);
        }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (message, sent) -> {
                calls.incrementAndGet();
                request.set(sent);
                if (synchronousFailure != null) throw synchronousFailure;
                return new OutboundCall<>() {
                    @Override public java.util.concurrent.CompletionStage<OutboundHttpResponse> completion() {
                        return response;
                    }
                    @Override public boolean cancel() { return response.cancel(true); }
                };
            };
        }
    }
}
