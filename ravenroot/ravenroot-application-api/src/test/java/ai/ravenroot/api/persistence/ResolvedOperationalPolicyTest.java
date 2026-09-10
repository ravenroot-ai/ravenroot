package ai.ravenroot.api.persistence;

import ai.ravenroot.api.node.service.NodePackageEgressCapacityProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
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

    private static NodePackageEgressCapacityProfile capacity() {
        return NodePackageEgressCapacityProfile.bounded(1, 2, 3, 4, 5, 5, 6,
                Duration.ofSeconds(7), Duration.ofSeconds(8), Duration.ofSeconds(7));
    }

    private static ResolvedOperationalPolicy.GraphLimits graph() {
        return new ResolvedOperationalPolicy.GraphLimits(1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
    }
}
