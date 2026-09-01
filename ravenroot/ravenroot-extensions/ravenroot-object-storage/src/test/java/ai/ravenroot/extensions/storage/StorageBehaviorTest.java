package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import ai.ravenroot.api.node.service.OutboundHttpResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class StorageBehaviorTest {
    @Test void getUsesManagedS3SigningAndProjectsBoundedTextMetadata() {
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        ObjectGetNodeBehavior behavior = new ObjectGetNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10))));
        NodeAction action = behavior.create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);

        NodeResult result = action.handle(StorageTestSupport.message(Map.of("version", "object.get.v1")))
                .toCompletableFuture().join();

        assertEquals("https://s3.example.test/bucket-a/tenant-data/folder/object.txt",
                http.request.get().destination().toASCIIString());
        assertEquals("GET", http.request.get().method());
        assertEquals("assets-s3", http.request.get().signing().orElseThrow().bindingId());
        assertTrue(http.request.get().credential().isEmpty());
        assertFalse(http.request.get().headers().containsKey("authorization"));
        assertEquals("tenant-a", http.message.get().tenantId());
        Map<?, ?> output = (Map<?, ?>) result.payload();
        assertEquals("object.get.result.v1", output.get("version"));
        assertEquals(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)), output.get("base64"));
        assertEquals(5L, output.get("bytes"));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", output.get("sha256"));
        assertEquals("\"etag-1\"", output.get("etag"));
        assertEquals("version-1", output.get("versionId"));
    }

    @Test void putSendsOneBodyAndOnlyProfileAuthorizedConditionals() {
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(201, new byte[0]));
        ObjectPutNodeBehavior behavior = new ObjectPutNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(StorageTestSupport.profile(Set.of(StorageProfile.Operation.PUT), 2, 10))));
        NodeAction action = behavior.create(StorageTestSupport.configuration(ObjectPutNodeBehavior.BEHAVIOR), http);
        NodeResult result = action.handle(StorageTestSupport.message(Map.of("version", "object.put.v1",
                        "text", "hello", "contentType", "text/plain", "ifMatch", "\"old\"",
                        "ifNoneMatch", "*"))).toCompletableFuture().join();

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), http.request.get().body());
        assertEquals(List.of("text/plain"), http.request.get().headers().get("content-type"));
        assertEquals(List.of("\"old\""), http.request.get().headers().get("if-match"));
        assertEquals(List.of("*"), http.request.get().headers().get("if-none-match"));
        Map<?, ?> output = (Map<?, ?>) result.payload();
        assertEquals("object.put.result.v1", output.get("version"));
        assertEquals(5L, output.get("bytes"));
    }

    @Test void inputAndResponseCeilingsRefuseWithoutLeakingRemoteData() {
        StorageProfile profile = new StorageProfile("assets", java.net.URI.create("https://s3.example.test"),
                "eu-west-1", "bucket-a", "", StorageProfile.AddressingStyle.PATH, "sign",
                Set.of(StorageProfile.Operation.GET, StorageProfile.Operation.PUT), Set.of(), false, false,
                4, 1000, 2, 10);
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(profile));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction put = new ObjectPutNodeBehavior(runtime).create(
                new ai.ravenroot.api.node.NodeConfiguration("put", ObjectPutNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "key")), http);
        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(200, new byte[0]));
        assertDoesNotThrow(() -> put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "base64", "MTIzNA=="))).toCompletableFuture().join());
        assertEquals(StorageException.Code.REQUEST_TOO_LARGE, failure(put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "12345")))).code());
        assertEquals(1, http.calls.get());

        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(200, "12345".getBytes(StandardCharsets.UTF_8)));
        NodeAction get = new ObjectGetNodeBehavior(runtime).create(
                new ai.ravenroot.api.node.NodeConfiguration("get", ObjectGetNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "key")), http);
        StorageException response = failure(get.handle(StorageTestSupport.message(Map.of("version", "object.get.v1"))));
        assertEquals(StorageException.Code.RESPONSE_TOO_LARGE, response.code());
        assertFalse(response.getMessage().contains("12345"));
    }

    @Test void managedSecurityFailuresRemainStableAndSanitized() {
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(
                StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10)));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        http.synchronousFailure = new NodePackageServiceException(NodePackageServiceException.Reason.TLS_REFUSED);
        NodeAction action = new ObjectGetNodeBehavior(runtime).create(
                StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);
        StorageException failure = failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.get.v1"))));
        assertEquals(StorageException.Code.TLS_REFUSED, failure.code());
        assertEquals("Object storage failed: TLS_REFUSED", failure.getMessage());
    }

    @Test void redirectAndMalformedMetadataAreRejected() {
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(
                StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10)));
        NodeAction action;
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        action = new ObjectGetNodeBehavior(runtime).create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);
        http.response = CompletableFuture.completedFuture(StorageTestSupport.response(307, new byte[0]));
        assertEquals(StorageException.Code.REDIRECT_REFUSED, failure(action.handle(
                StorageTestSupport.message(Map.of("version", "object.get.v1")))).code());
        http.response = CompletableFuture.completedFuture(new OutboundHttpResponse(200, Map.of(), new byte[0]));
        assertEquals(StorageException.Code.RESPONSE_INVALID, failure(action.handle(
                StorageTestSupport.message(Map.of("version", "object.get.v1")))).code());
    }

    @Test void putTransportUncertaintyAndDirectCancellationAreAlwaysAmbiguous() {
        StorageRuntime runtime = new StorageRuntime(name -> java.util.Optional.of(
                StorageTestSupport.profile(Set.of(StorageProfile.Operation.PUT), 2, 10)));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction action = new ObjectPutNodeBehavior(runtime).create(StorageTestSupport.configuration(
                ObjectPutNodeBehavior.BEHAVIOR), http);
        http.response = CompletableFuture.failedFuture(new NodePackageServiceException(
                NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertEquals(StorageException.Code.AMBIGUOUS, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value")))).code());

        http.response = new CompletableFuture<>();
        CompletableFuture<NodeResult> result = action.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value"))).toCompletableFuture();
        assertTrue(result.cancel(true));
        assertEquals(StorageException.Code.AMBIGUOUS, failure(result).code());
        assertEquals(1, http.cancellations.get());
    }

    @Test void sharedProfileConcurrencyCoversGetAndPutAndReleasesAfterSettlement() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET,
                StorageProfile.Operation.PUT), 1, 10);
        StorageNodePackage nodePackage = new StorageNodePackage(name -> java.util.Optional.of(profile));
        StorageTestSupport.HttpDouble firstHttp = new StorageTestSupport.HttpDouble();
        firstHttp.response = new CompletableFuture<>();
        StorageTestSupport.HttpDouble secondHttp = new StorageTestSupport.HttpDouble();
        secondHttp.response = CompletableFuture.completedFuture(StorageTestSupport.response(200, new byte[0]));
        NodeAction get = behavior(nodePackage, ObjectGetNodeBehavior.BEHAVIOR)
                .create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), firstHttp);
        NodeAction put = behavior(nodePackage, ObjectPutNodeBehavior.BEHAVIOR)
                .create(StorageTestSupport.configuration(ObjectPutNodeBehavior.BEHAVIOR), secondHttp);

        CompletionStage<NodeResult> pending = get.handle(StorageTestSupport.message(Map.of("version", "object.get.v1")));
        assertEquals(StorageException.Code.CAPACITY_UNAVAILABLE, failure(put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value")))).code());
        assertEquals(0, secondHttp.calls.get());
        firstHttp.response.complete(StorageTestSupport.response(200, "ok".getBytes(StandardCharsets.UTF_8)));
        pending.toCompletableFuture().join();
        assertDoesNotThrow(() -> put.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "value"))).toCompletableFuture().join());
    }

    @Test void profileRateLimitAppliesAcrossSequentialOperations() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 1);
        ObjectGetNodeBehavior behavior = new ObjectGetNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)));
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction action = behavior.create(StorageTestSupport.configuration(ObjectGetNodeBehavior.BEHAVIOR), http);
        action.handle(StorageTestSupport.message(Map.of("version", "object.get.v1"))).toCompletableFuture().join();
        assertEquals(StorageException.Code.RATE_LIMITED, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.get.v1")))).code());
        assertEquals(1, http.calls.get());
    }

    @Test void disallowedOperationAndConditionalsFailBeforeTransport() {
        StorageProfile getOnly = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10);
        assertEquals(StorageException.Code.CONFIGURATION, assertThrows(StorageException.class, () ->
                new ObjectPutNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(getOnly)))
                        .create(StorageTestSupport.configuration(ObjectPutNodeBehavior.BEHAVIOR),
                                new StorageTestSupport.HttpDouble())).code());

        StorageProfile noConditions = new StorageProfile("assets", java.net.URI.create("https://s3.example.test"),
                "eu-west-1", "bucket-a", "", StorageProfile.AddressingStyle.PATH, "sign",
                Set.of(StorageProfile.Operation.PUT), Set.of(), false, false, 1024, 1000, 2, 10);
        StorageTestSupport.HttpDouble http = new StorageTestSupport.HttpDouble();
        NodeAction action = new ObjectPutNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(noConditions)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("put", ObjectPutNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "key")), http);
        assertEquals(StorageException.Code.INVALID_INPUT, failure(action.handle(StorageTestSupport.message(
                Map.of("version", "object.put.v1", "text", "x", "ifMatch", "*")))).code());
        assertEquals(0, http.calls.get());
    }

    private static NodeBehavior behavior(StorageNodePackage nodePackage, String name) {
        return nodePackage.behaviors().stream().filter(item -> item.descriptor().behavior().equals(name)).findFirst().orElseThrow();
    }

    private static StorageException failure(CompletionStage<?> stage) {
        try { stage.toCompletableFuture().join(); throw new AssertionError("expected failure"); }
        catch (CompletionException failure) { return (StorageException) failure.getCause(); }
    }
}
