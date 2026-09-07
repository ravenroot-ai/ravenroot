package ai.ravenroot.api.node.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable quantitative description of the exact service view supplied to one node package.
 * This trusted declaration contains no credentials, destinations or live authorization decisions.
 * A provider must describe its actual enforced policy for the lifetime of that service instance.
 * Unknown is represented by the service port's empty Optional, never by this class's deny-only value.
 */
public final class NodePackageEgressCapacityProfile {
    private static final NodePackageEgressCapacityProfile NONE = new NodePackageEgressCapacityProfile(null);
    private final Limits limits;

    private NodePackageEgressCapacityProfile(Limits limits) { this.limits = limits; }

    /** Explicitly describes a service whose managed outbound ports are deny-only. */
    public static NodePackageEgressCapacityProfile noManagedEgress() { return NONE; }

    /** Describes a complete enforced tuple; every value must be supplied explicitly. */
    public static NodePackageEgressCapacityProfile bounded(long maximumRequestBytes, long maximumResponseBytes,
            long maximumWebSocketMessageBytes, int maximumWebSocketFragments, int maximumConcurrentOperations,
            int maximumConcurrentPerTenant, int maximumQueuedWebSocketSends, Duration maximumDeadline,
            Duration maximumWebSocketLifetime, Duration maximumWebSocketIdle, int maximumHttpDecompressionRatio) {
        return new NodePackageEgressCapacityProfile(new Limits(maximumRequestBytes, maximumResponseBytes,
                maximumWebSocketMessageBytes, maximumWebSocketFragments, maximumConcurrentOperations,
                maximumConcurrentPerTenant, maximumQueuedWebSocketSends, maximumDeadline,
                maximumWebSocketLifetime, maximumWebSocketIdle, maximumHttpDecompressionRatio));
    }

    /** Empty means known deny-only, not unknown; the enclosing profile itself is known. */
    public Optional<Limits> limits() { return Optional.ofNullable(limits); }

    @Override public boolean equals(Object other) {
        return other instanceof NodePackageEgressCapacityProfile profile && Objects.equals(limits, profile.limits);
    }
    @Override public int hashCode() { return Objects.hashCode(limits); }

    /** Explicit capacities, with the same finite representation and relations as managed egress. */
    public record Limits(long maximumRequestBytes, long maximumResponseBytes, long maximumWebSocketMessageBytes,
            int maximumWebSocketFragments, int maximumConcurrentOperations, int maximumConcurrentPerTenant,
            int maximumQueuedWebSocketSends, Duration maximumDeadline, Duration maximumWebSocketLifetime,
            Duration maximumWebSocketIdle, int maximumHttpDecompressionRatio) {
        public Limits {
            bytes(maximumRequestBytes, "maximumRequestBytes");
            bytes(maximumResponseBytes, "maximumResponseBytes");
            bytes(maximumWebSocketMessageBytes, "maximumWebSocketMessageBytes");
            positive(maximumWebSocketFragments, "maximumWebSocketFragments");
            positive(maximumConcurrentOperations, "maximumConcurrentOperations");
            positive(maximumConcurrentPerTenant, "maximumConcurrentPerTenant");
            positive(maximumQueuedWebSocketSends, "maximumQueuedWebSocketSends");
            if (maximumConcurrentPerTenant > maximumConcurrentOperations) {
                throw new IllegalArgumentException("maximumConcurrentPerTenant exceeds package capacity");
            }
            duration(maximumDeadline, "maximumDeadline");
            duration(maximumWebSocketLifetime, "maximumWebSocketLifetime");
            duration(maximumWebSocketIdle, "maximumWebSocketIdle");
            if (maximumWebSocketIdle.compareTo(maximumWebSocketLifetime) > 0) {
                throw new IllegalArgumentException("maximumWebSocketIdle exceeds lifetime");
            }
            if (maximumHttpDecompressionRatio < 1 || maximumHttpDecompressionRatio > 1000) {
                throw new IllegalArgumentException("maximumHttpDecompressionRatio must be from 1 through 1000");
            }
        }
        private static void bytes(long value, String name) {
            if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException(name + " is out of range");
        }
        private static void positive(int value, String name) {
            if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        }
        private static void duration(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
            try { value.toNanos(); }
            catch (ArithmeticException tooLarge) { throw new IllegalArgumentException(name + " is too large"); }
        }
    }
}
