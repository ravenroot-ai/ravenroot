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
}
