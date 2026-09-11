package ai.ravenroot.core.security.nodepackage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodePackageEgressPolicyCapacityTest {

    @Test
    void decompressionRatioHasOneTypedDefaultAndClosedRange() {
        assertEquals(100, NodePackageEgressPolicy.builder().build().maximumDecompressionRatio());
        assertEquals(1, NodePackageEgressPolicy.builder().maximumDecompressionRatio(1).build()
                .maximumDecompressionRatio());
        assertEquals(1_000, NodePackageEgressPolicy.builder().maximumDecompressionRatio(1_000).build()
                .maximumDecompressionRatio());
        assertThrows(IllegalArgumentException.class,
                () -> NodePackageEgressPolicy.builder().maximumDecompressionRatio(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> NodePackageEgressPolicy.builder().maximumDecompressionRatio(1_001).build());
    }
}
