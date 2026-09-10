package ai.ravenroot.api.persistence;

import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Optional;

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
