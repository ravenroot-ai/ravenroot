package ai.ravenroot.api.persistence;

import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolvedOperationalPolicyTest {

    @Test
    void maximumSupportedPackageShapeRoundTrips() {
        var packages = new ArrayList<ResolvedOperationalPolicy.PackageCapacity>();
        for (int index = 0; index < ExecutionManifest.MAX_NODE_PACKAGES; index++) {
            String id = "p%04d".formatted(index) + "x".repeat(195);
            packages.add(new ResolvedOperationalPolicy.PackageCapacity(id, capacity()));
        }
        var policy = new ResolvedOperationalPolicy(graph(),
                new ResolvedOperationalPolicy.ResultLimits(true, 1_048_576),
                Optional.of(new ResolvedOperationalPolicy.BuiltInHttpCapacity(
                        1024, 2048, Duration.ofSeconds(30))), packages);

        assertEquals(policy, ResolvedOperationalPolicy.decode(policy.encode()));
    }

    @Test
    void formatThreePersistenceCapacityRoundTripsWithoutChangingFormatTwoCodec() {
        var legacy = new ResolvedOperationalPolicy(graph(),
                new ResolvedOperationalPolicy.ResultLimits(false, 4096), Optional.empty(), java.util.List.of());
        var current = new ResolvedOperationalPolicy(graph(), legacy.results(), Optional.empty(),
                java.util.List.of(), Optional.of(new ResolvedOperationalPolicy.PersistenceLimits(8192)));

        assertEquals(legacy, ResolvedOperationalPolicy.decodeForManifest(
                legacy.encodeForManifest(ExecutionManifest.FORMAT_VERSION_2),
                ExecutionManifest.FORMAT_VERSION_2));
        assertEquals(current, ResolvedOperationalPolicy.decodeForManifest(
                current.encodeForManifest(ExecutionManifest.FORMAT_VERSION_3),
                ExecutionManifest.FORMAT_VERSION_3));
        assertThrows(IllegalArgumentException.class,
                () -> ResolvedOperationalPolicy.decodeForManifest(
                        current.encodeForManifest(ExecutionManifest.FORMAT_VERSION_3),
                        ExecutionManifest.FORMAT_VERSION_2));
        assertThrows(IllegalStateException.class, current::encode);
    }

    @Test
    void formatTwoCanonicalBytesAndManifestDigestStayFixed() {
        var policy = new ResolvedOperationalPolicy(graph(),
                new ResolvedOperationalPolicy.ResultLimits(false, 4096), Optional.empty(), List.of());
        var manifest = new ExecutionManifest(ExecutionManifest.FORMAT_VERSION_2,
                new ExecutionKey("acme", UUID.fromString("00000000-0000-0000-0000-000000000001")),
                new GraphContentId("a".repeat(64)),
                new GraphDefinitionIdentity(GraphDefinitionIdentity.SUBMISSION_GRAPH_ID, "a".repeat(64)),
                new ResolvedRuntimeProfile(1, 1, "STANDARD", "pass-through", "1".repeat(64),
                        "2".repeat(64), "3".repeat(64), "4".repeat(64)),
                List.of(), Instant.EPOCH, policy);

        assertEquals("AAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAAB"
                        + "AAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAEAAAABAAAAAQAAAAAAAAAB"
                        + "AAAAAAAAAAEAAAAAAAAAAQAAAAEAAAAQAAAAAAAA",
                policy.encodeForManifest(ExecutionManifest.FORMAT_VERSION_2));
        assertEquals("8313dd235e1d5246faa6bfe98744a5209f8df556544ebbf40baa84c3c4f54544",
                manifest.digest().value());
    }

    @Test
    void oversizedEncodedInputIsRejectedBeforeBase64Allocation() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ResolvedOperationalPolicy.decode("A".repeat(700_000)));
        assertEquals("policy is too large", failure.getMessage());
    }

    @Test
    void durationNormalizationOverflowIsRejectedAsMalformedPolicy() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            for (int index = 0; index < 21; index++) out.writeInt(1);
            for (int index = 0; index < 3; index++) out.writeLong(1);
            out.writeInt(1);
            out.writeBoolean(true);
            out.writeInt(1);
            out.writeBoolean(true);
            out.writeLong(1);
            out.writeLong(1);
            out.writeLong(Long.MAX_VALUE);
            out.writeInt(1_000_000_000);
            out.writeInt(0);
        }
        String malformed = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());

        var failure = assertThrows(IllegalArgumentException.class,
                () -> ResolvedOperationalPolicy.decode(malformed));

        assertEquals("operational policy duration is out of range", failure.getMessage());
    }

    private static NodePackageEgressCapacityProfile capacity() {
        return NodePackageEgressCapacityProfile.bounded(1, 2, 3, 4, 5, 5, 6,
                Duration.ofSeconds(7), Duration.ofSeconds(8), Duration.ofSeconds(7));
    }

    private static ResolvedOperationalPolicy.GraphLimits graph() {
        return new ResolvedOperationalPolicy.GraphLimits(1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    }
}
