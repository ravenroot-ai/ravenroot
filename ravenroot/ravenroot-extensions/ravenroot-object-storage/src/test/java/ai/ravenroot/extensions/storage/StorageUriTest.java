package ai.ravenroot.extensions.storage;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StorageUriTest {
    @Test void pathStylePreservesOperatorAuthorityAndEncodesUtf8ExactlyOnce() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10);
        assertEquals("https://s3.example.test/bucket-a/tenant-data/caf%C3%A9/object.txt",
                StorageUri.destination(profile, "café/object.txt").toASCIIString());
    }

    @Test void virtualHostedRequiresAnAlreadyBucketScopedOrigin() {
        StorageProfile profile = new StorageProfile("assets", URI.create("https://bucket-a.s3.example.test"),
                "eu-west-1", "bucket-a", "", StorageProfile.AddressingStyle.VIRTUAL_HOSTED, "sign",
                Set.of(StorageProfile.Operation.GET), Set.of(), false, false, 100, 1000, 1, 1);
        assertEquals("https://bucket-a.s3.example.test/folder/key",
                StorageUri.destination(profile, "folder/key").toASCIIString());
        assertThrows(IllegalArgumentException.class, () -> new StorageProfile("assets",
                URI.create("https://s3.example.test"), "eu-west-1", "bucket-a", "",
                StorageProfile.AddressingStyle.VIRTUAL_HOSTED, "sign", Set.of(StorageProfile.Operation.GET),
                Set.of(), false, false, 100, 1000, 1, 1));
    }

    @Test void traversalAndEncodedSeparatorFamiliesAreRejectedBeforeTransport() {
        StorageProfile profile = StorageTestSupport.profile(Set.of(StorageProfile.Operation.GET), 2, 10);
        for (String key : Set.of("/absolute", "trailing/", "a//b", ".", "..", "a/../b", "a\\b",
                "a%2fb", "a%252fb", "a?b", "a#b", "a\u0000b")) {
            StorageException failure = assertThrows(StorageException.class, () -> StorageUri.destination(profile, key), key);
            assertEquals(StorageException.Code.INVALID_INPUT, failure.code());
        }
    }

    @Test void byteBoundaryIsAppliedToUtf8NotUtf16Characters() {
        StorageProfile profile = new StorageProfile("assets", URI.create("https://s3.example.test"),
                "eu-west-1", "bucket-a", "", StorageProfile.AddressingStyle.PATH, "sign",
                Set.of(StorageProfile.Operation.GET), Set.of(), false, false, 100, 1000, 1, 1);
        assertDoesNotThrow(() -> StorageUri.destination(profile, "é".repeat(512)));
        assertThrows(StorageException.class, () -> StorageUri.destination(profile, "é".repeat(513)));
    }

    @Test void prefixAndKeyShareTheSingleS3ObjectKeyByteBudget() {
        StorageProfile profile = new StorageProfile("assets", URI.create("https://s3.example.test"),
                "eu-west-1", "bucket-a", "a".repeat(1000), StorageProfile.AddressingStyle.PATH, "sign",
                Set.of(StorageProfile.Operation.GET), Set.of(), false, false, 100, 1000, 1, 1);
        assertDoesNotThrow(() -> StorageUri.destination(profile, "b".repeat(23)));
        assertThrows(StorageException.class, () -> StorageUri.destination(profile, "b".repeat(24)));
    }
}
