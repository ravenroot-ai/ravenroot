package ai.ravenroot.extensions.storage;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class StorageTestSupport {
    static StorageProfile profile(Set<StorageProfile.Operation> operations, int concurrency, int rate) {
        return new StorageProfile("assets", URI.create("https://s3.example.test"), "eu-west-1", "bucket-a",
                "tenant-data", StorageProfile.AddressingStyle.PATH, "assets-s3", operations,
                Set.of("text/plain", "application/octet-stream"), true, true,
                1024, 2000, concurrency, rate);
    }

    static NodeConfiguration configuration(String behavior) {
        return new NodeConfiguration("storage", behavior, Map.of("storageProfile", "assets",
                "key", "folder/object.txt"));
    }

    static NodeMessage message(Object payload) { return message("tenant-a", payload); }
    static NodeMessage message(String tenant, Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", tenant, "subject", PrincipalType.WORKLOAD, "issuer"),
                id, UUID.randomUUID(), "storage", payload, Map.of());
    }

    static OutboundHttpResponse response(int status, byte[] body) {
        return new OutboundHttpResponse(status,
                Map.of("ETag", List.of("\"etag-1\""), "x-amz-version-id", List.of("version-1")), body);
    }

    static final class HttpDouble implements NodePackageServices {
        final AtomicReference<OutboundHttpRequest> request = new AtomicReference<>();
        final AtomicReference<NodeMessage> message = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger cancellations = new AtomicInteger();
        volatile RuntimeException synchronousFailure;
        volatile CompletableFuture<OutboundHttpResponse> response = CompletableFuture.completedFuture(
                response(200, "hello".getBytes(StandardCharsets.UTF_8)));

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
                    @Override public boolean cancel() { cancellations.incrementAndGet(); return response.cancel(true); }
                };
            };
        }
        @Override public ai.ravenroot.api.node.service.OutboundWebSocketService outboundWebSocket() {
            return NodePackageServices.unavailable().outboundWebSocket();
        }
    }

    private StorageTestSupport() { }
}
