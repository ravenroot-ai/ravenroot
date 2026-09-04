package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.core.security.nodepackage.StorageManagedServicesHarness;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StorageManagedServicesIntegrationTest {
    @Test void realManagedPublicSeamSignsExactS3RequestAndResolvesPerTenantInvocation() {
        StorageManagedServicesHarness.Fixture fixture = StorageManagedServicesHarness.create();
        StorageProfile profile = new StorageProfile("assets", URI.create("https://localhost"), "eu-west-1",
                "bucket-a", "", StorageProfile.AddressingStyle.PATH, "assets-s3",
                Set.of(StorageProfile.Operation.GET), Set.of(), false, false, 1024, 2000, 2, 10);
        NodeAction action = new ObjectGetNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("get", ObjectGetNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "folder/object.txt")), fixture.services());

        action.handle(StorageTestSupport.message("tenant-a", Map.of("version", "object.get.v1")))
                .toCompletableFuture().join();
        String first = fixture.client().request().headers().firstValue("Authorization").orElseThrow();
        assertEquals("https://localhost/bucket-a/folder/object.txt", fixture.client().request().uri().toASCIIString());
        assertTrue(first.contains("Credential=AKIDtenant-a1/20130524/eu-west-1/s3/aws4_request"));
        assertTrue(fixture.client().request().headers().firstValue("x-amz-content-sha256").isPresent());
        assertEquals("ai.ravenroot.extensions.storage/tenant-a/credential/storage", fixture.resolution().get());

        fixture.rotate();
        action.handle(StorageTestSupport.message("tenant-b", Map.of("version", "object.get.v1")))
                .toCompletableFuture().join();
        String rotated = fixture.client().request().headers().firstValue("Authorization").orElseThrow();
        assertTrue(rotated.contains("Credential=AKIDtenant-b2/20130524/eu-west-1/s3/aws4_request"));
        assertNotEquals(first, rotated);
        assertEquals("ai.ravenroot.extensions.storage/tenant-b/credential/storage", fixture.resolution().get());
    }

    @Test void managedS3ProtocolPaginatesAcrossRuntimeRestartAndDeletesIdempotently() {
        StorageManagedServicesHarness.Fixture fixture = StorageManagedServicesHarness.create();
        StorageProfile profile = new StorageProfile("assets", URI.create("https://localhost"), "eu-west-1",
                "bucket-a", "tenant-data", StorageProfile.AddressingStyle.PATH, "assets-s3",
                Set.of(StorageProfile.Operation.LIST, StorageProfile.Operation.DELETE,
                        StorageProfile.Operation.DELETE_VERSION), Set.of(), false, false,
                4096, 2000, 2, 10);
        Map<String, Object> listProperties = Map.of("storageProfile", "assets", "prefix", "folder",
                "maxResults", "1", "projection", "size");
        NodeAction pageOneAction = new ObjectListNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(profile))).create(new ai.ravenroot.api.node.NodeConfiguration(
                        "list", ObjectListNodeBehavior.BEHAVIOR, listProperties), fixture.services());
        Map<?, ?> pageOne = (Map<?, ?>) pageOneAction.handle(StorageTestSupport.message("tenant-a",
                Map.of("version", "object.list.v1"))).toCompletableFuture().join().payload();
        String cursor = (String) pageOne.get("cursor");
        assertEquals("folder/first.txt", ((Map<?, ?>) ((java.util.List<?>) pageOne.get("objects")).getFirst())
                .get("key"));

        NodeAction restarted = new ObjectListNodeBehavior(new StorageRuntime(name ->
                java.util.Optional.of(profile))).create(new ai.ravenroot.api.node.NodeConfiguration(
                        "list", ObjectListNodeBehavior.BEHAVIOR, listProperties), fixture.services());
        Map<?, ?> pageTwo = (Map<?, ?>) restarted.handle(StorageTestSupport.message("tenant-a",
                Map.of("version", "object.list.v1", "cursor", cursor))).toCompletableFuture().join().payload();
        assertEquals("folder/second.txt", ((Map<?, ?>) ((java.util.List<?>) pageTwo.get("objects")).getFirst())
                .get("key"));
        assertFalse(pageTwo.containsKey("cursor"));

        NodeAction delete = new ObjectDeleteNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(profile)))
                .create(new ai.ravenroot.api.node.NodeConfiguration("delete", ObjectDeleteNodeBehavior.BEHAVIOR,
                        Map.of("storageProfile", "assets", "key", "folder/object.txt")), fixture.services());
        Map<?, ?> deleted = (Map<?, ?>) delete.handle(StorageTestSupport.message("tenant-a",
                Map.of("version", "object.delete.v1", "versionId", "version+/=one")))
                .toCompletableFuture().join().payload();
        Map<?, ?> alreadyAbsent = (Map<?, ?>) delete.handle(StorageTestSupport.message("tenant-a",
                Map.of("version", "object.delete.v1", "versionId", "version+/=one")))
                .toCompletableFuture().join().payload();
        assertEquals("DELETED", deleted.get("status"));
        assertEquals("NOT_FOUND", alreadyAbsent.get("status"));

        assertEquals(4, fixture.client().requests().size());
        assertTrue(fixture.client().requests().get(1).uri().getRawQuery()
                .contains("continuation-token=managed%2B%2F%3Dcursor"));
        assertEquals("versionId=version%2B%2F%3Done",
                fixture.client().requests().get(2).uri().getRawQuery());
        fixture.client().requests().forEach(request -> {
            assertTrue(request.headers().firstValue("Authorization").orElseThrow()
                    .contains("Credential=AKIDtenant-a1/"));
            assertFalse(request.uri().toASCIIString().contains("remote detail"));
        });

        StorageProfile denied = new StorageProfile("assets", URI.create("https://localhost"), "eu-west-1",
                "bucket-a", "tenant-data", StorageProfile.AddressingStyle.PATH, "assets-s3",
                Set.of(StorageProfile.Operation.LIST), Set.of(), false, false, 4096, 2000, 2, 10);
        assertEquals(StorageException.Code.CONFIGURATION, assertThrows(StorageException.class, () ->
                new ObjectDeleteNodeBehavior(new StorageRuntime(name -> java.util.Optional.of(denied))).create(
                        new ai.ravenroot.api.node.NodeConfiguration("delete", ObjectDeleteNodeBehavior.BEHAVIOR,
                                Map.of("storageProfile", "assets", "key", "folder/object.txt")),
                        fixture.services())).code());
        assertEquals(4, fixture.client().requests().size(), "denied operation never reaches managed HTTP");
    }
}
