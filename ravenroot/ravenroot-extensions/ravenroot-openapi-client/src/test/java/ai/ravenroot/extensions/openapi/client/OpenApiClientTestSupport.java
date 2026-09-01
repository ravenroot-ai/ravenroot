package ai.ravenroot.extensions.openapi.client;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.node.service.OutboundCall;
import ai.ravenroot.api.node.service.OutboundHttpRequest;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class OpenApiClientTestSupport {
    static final String SPEC = """
            {
              "openapi":"3.0.3",
              "info":{"title":"Pets","version":"1"},
              "paths":{
                "/pets/{petId}":{
                  "get":{
                    "operationId":"getPet",
                    "parameters":[
                      {"name":"petId","in":"path","required":true,"schema":{"type":"string","minLength":1,"maxLength":32}},
                      {"name":"verbose","in":"query","schema":{"type":"boolean"}},
                      {"name":"X-Trace","in":"header","schema":{"type":"string","maxLength":64}}
                    ],
                    "responses":{
                      "200":{"description":"ok","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}},
                      "404":{"description":"missing"}
                    }
                  }
                },
                "/pets":{
                  "post":{
                    "operationId":"createPet",
                    "security":[{"bearerAuth":[]}],
                    "requestBody":{"required":true,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/PetInput"}}}},
                    "responses":{"201":{"description":"created","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Pet"}}}}}
                  }
                }
              },
              "components":{
                "securitySchemes":{"bearerAuth":{"type":"http","scheme":"bearer"}},
                "schemas":{
                  "PetInput":{"type":"object","required":["name"],"additionalProperties":false,"properties":{"name":{"type":"string","minLength":1,"maxLength":64}}},
                  "Pet":{"type":"object","required":["id","name"],"additionalProperties":false,"properties":{"id":{"type":"integer","minimum":1},"name":{"type":"string","minLength":1,"maxLength":64}}}
                }
              }
            }
            """;

    static OpenApiClientProfile profile(Set<String> operations, int concurrency) {
        return profile("pets", operations, concurrency);
    }

    static OpenApiClientProfile profile(String name, Set<String> operations, int concurrency) {
        byte[] bytes = SPEC.getBytes(StandardCharsets.UTF_8);
        return new OpenApiClientProfile(name, URI.create("https://api.example.test"), bytes, sha(bytes), operations,
                Map.of("accept", List.of("application/json")), Set.of("x-trace"), Set.of("content-type", "x-request-id"),
                "bearer", "pets-token", 4_096, 8_192, 2_000, concurrency);
    }

    static OpenApiClientProfile profile(String spec, Set<String> operations) {
        byte[] bytes = spec.getBytes(StandardCharsets.UTF_8);
        return new OpenApiClientProfile("pets", URI.create("https://api.example.test"), bytes, sha(bytes), operations,
                Map.of(), Set.of("x-trace"), Set.of(), "bearer", "pets-token", 4_096, 8_192, 2_000, 2);
    }

    static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }

    static NodeMessage message(Object payload) {
        return message("tenant-a", payload);
    }

    static NodeMessage message(String tenant, Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", tenant, "subject", PrincipalType.WORKLOAD, "issuer"),
                id, UUID.randomUUID(), "call", payload, Map.of());
    }

    static final class HttpDouble implements NodePackageServices {
        final AtomicReference<OutboundHttpRequest> request = new AtomicReference<>();
        final AtomicReference<NodeMessage> message = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger cancellations = new AtomicInteger();
        volatile boolean cancelCompletes = true;
        volatile RuntimeException synchronousFailure;
        volatile CompletableFuture<OutboundHttpResponse> response = CompletableFuture.completedFuture(
                new OutboundHttpResponse(200, Map.of("content-type", List.of("application/json")),
                        "{\"id\":1,\"name\":\"Milo\"}".getBytes(StandardCharsets.UTF_8)));

        @Override public Set<NodePackageCapability> capabilities() { return Set.of(NodePackageCapability.OUTBOUND_HTTP); }
        @Override public ai.ravenroot.api.node.service.NodeCredentialService credentials() {
            return NodePackageServices.unavailable().credentials();
        }
        @Override public ai.ravenroot.api.node.service.OutboundHttpService outboundHttp() {
            return (delivered, submitted) -> {
                if (synchronousFailure != null) throw synchronousFailure;
                calls.incrementAndGet(); message.set(delivered); request.set(submitted);
                return new OutboundCall<>() {
                    @Override public CompletionStage<OutboundHttpResponse> completion() { return response; }
                    @Override public boolean cancel() {
                        cancellations.incrementAndGet();
                        return !cancelCompletes || response.cancel(true);
                    }
                };
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }

    private OpenApiClientTestSupport() { }
}
