package ai.ravenroot.api.node.service;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodePackageEgressCapacityProfileTest {
    private static final Duration SECOND = Duration.ofSeconds(1);

    @Test
    void knownDenyIsDistinctFromBoundedAndUnavailablePublishesKnownDeny() {
        var deny = NodePackageEgressCapacityProfile.noManagedEgress();
        assertTrue(deny.limits().isEmpty());
        assertEquals(deny, NodePackageServices.unavailable().egressCapacityProfile().orElseThrow());
        var bounded = NodePackageEgressCapacityProfile.bounded(1, 1, 1, 1, 1, 1, 1, SECOND, SECOND, SECOND, 1);
        assertNotEquals(deny, bounded);
        assertEquals(bounded, NodePackageEgressCapacityProfile.bounded(1, 1, 1, 1, 1, 1, 1, SECOND, SECOND, SECOND, 1));
        assertEquals(bounded.hashCode(), NodePackageEgressCapacityProfile.bounded(
                1, 1, 1, 1, 1, 1, 1, SECOND, SECOND, SECOND, 1).hashCode());
    }

    @Test
    void eachByteDimensionAndCountRequiresTheSameFiniteRepresentationAsManagedPolicy() {
        for (int dimension = 0; dimension < 3; dimension++) {
            for (long bad : new long[]{0, -1, (long) Integer.MAX_VALUE + 1}) {
                long[] bytes = {1, 1, 1}; bytes[dimension] = bad;
                assertThrows(IllegalArgumentException.class, () -> NodePackageEgressCapacityProfile.bounded(
                        bytes[0], bytes[1], bytes[2], 1, 1, 1, 1, SECOND, SECOND, SECOND, 1));
            }
        }
        for (int dimension = 0; dimension < 4; dimension++) {
            int[] counts = {1, 1, 1, 1}; counts[dimension] = 0;
            assertThrows(IllegalArgumentException.class, () -> NodePackageEgressCapacityProfile.bounded(
                    1, 1, 1, counts[0], counts[1], counts[2], counts[3], SECOND, SECOND, SECOND, 1));
        }
        assertDoesNotThrow(() -> NodePackageEgressCapacityProfile.bounded(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Duration.ofNanos(Long.MAX_VALUE), Duration.ofNanos(Long.MAX_VALUE), Duration.ofNanos(Long.MAX_VALUE), 1000));
    }

    @Test
    void eachDurationAndCrossFieldRelationIsValidatedWithoutAnOverflowCause() {
        for (int dimension = 0; dimension < 3; dimension++) {
            for (Duration bad : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
                Duration[] durations = {SECOND, SECOND, SECOND}; durations[dimension] = bad;
                var failure = assertThrows(IllegalArgumentException.class, () -> NodePackageEgressCapacityProfile.bounded(
                        1, 1, 1, 1, 1, 1, 1, durations[0], durations[1], durations[2], 1));
                assertNull(failure.getCause());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressCapacityProfile.bounded(
                1, 1, 1, 1, 1, 2, 1, SECOND, SECOND, SECOND, 1));
        assertThrows(IllegalArgumentException.class, () -> NodePackageEgressCapacityProfile.bounded(
                1, 1, 1, 1, 1, 1, 1, SECOND, SECOND, SECOND.plusNanos(1), 1));
        for (int ratio : new int[]{0, 1001}) assertThrows(IllegalArgumentException.class,
                () -> NodePackageEgressCapacityProfile.bounded(1, 1, 1, 1, 1, 1, 1, SECOND, SECOND, SECOND, ratio));
    }
}
