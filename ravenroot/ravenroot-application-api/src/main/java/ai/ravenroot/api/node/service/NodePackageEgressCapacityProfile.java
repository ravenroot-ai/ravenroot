package ai.ravenroot.api.node.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Closed quantitative description of one package's managed external-I/O service view.
 *
 * <p>The profile deliberately excludes destinations, header grants, credential references and
 * credentials. Those remain live authorization and revocation inputs. These values only describe
 * the finite byte, time and concurrency policy that affects repeatable execution.</p>
 */
public final class NodePackageEgressCapacityProfile {
    private static final NodePackageEgressCapacityProfile NONE = new NodePackageEgressCapacityProfile(null);
    private final Limits limits;

    private NodePackageEgressCapacityProfile(Limits limits) {
        this.limits = limits;
    }

    /** A service view with no managed external I/O. */
    public static NodePackageEgressCapacityProfile noManagedEgress() {
        return NONE;
    }

    /** A service view with every quantitative limit explicitly resolved. */
    public static NodePackageEgressCapacityProfile bounded(
            long maximumRequestBytes, long maximumResponseBytes, long maximumWebSocketMessageBytes,
            int maximumWebSocketFragments, int maximumConcurrentOperations,
            int maximumConcurrentPerTenant, int maximumQueuedWebSocketSends,
            Duration maximumDeadline, Duration maximumWebSocketLifetime,
            Duration maximumWebSocketIdle) {
        return new NodePackageEgressCapacityProfile(new Limits(maximumRequestBytes,
                maximumResponseBytes, maximumWebSocketMessageBytes, maximumWebSocketFragments,
                maximumConcurrentOperations, maximumConcurrentPerTenant,
                maximumQueuedWebSocketSends, maximumDeadline, maximumWebSocketLifetime,
                maximumWebSocketIdle));
    }

    /** Empty means an explicitly deny-only service view. */
    public Optional<Limits> limits() {
        return Optional.ofNullable(limits);
    }

    @Override public boolean equals(Object other) {
        return other instanceof NodePackageEgressCapacityProfile profile
                && Objects.equals(limits, profile.limits);
    }

    @Override public int hashCode() {
        return Objects.hashCode(limits);
    }

    /** Finite managed external-I/O capacities. */
    public record Limits(long maximumRequestBytes, long maximumResponseBytes,
                         long maximumWebSocketMessageBytes, int maximumWebSocketFragments,
                         int maximumConcurrentOperations, int maximumConcurrentPerTenant,
                         int maximumQueuedWebSocketSends, Duration maximumDeadline,
                         Duration maximumWebSocketLifetime, Duration maximumWebSocketIdle) {
        public Limits {
            bytes(maximumRequestBytes, "maximumRequestBytes");
            bytes(maximumResponseBytes, "maximumResponseBytes");
            bytes(maximumWebSocketMessageBytes, "maximumWebSocketMessageBytes");
            positive(maximumWebSocketFragments, "maximumWebSocketFragments");
            positive(maximumConcurrentOperations, "maximumConcurrentOperations");
            positive(maximumConcurrentPerTenant, "maximumConcurrentPerTenant");
            positive(maximumQueuedWebSocketSends, "maximumQueuedWebSocketSends");
            if (maximumConcurrentPerTenant > maximumConcurrentOperations) {
                throw new IllegalArgumentException(
                        "maximumConcurrentPerTenant exceeds package capacity");
            }
            duration(maximumDeadline, "maximumDeadline");
            duration(maximumWebSocketLifetime, "maximumWebSocketLifetime");
            duration(maximumWebSocketIdle, "maximumWebSocketIdle");
            if (maximumWebSocketIdle.compareTo(maximumWebSocketLifetime) > 0) {
                throw new IllegalArgumentException("maximumWebSocketIdle exceeds lifetime");
            }
        }

        private static void bytes(long value, String name) {
            if (value < 1 || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(name + " is out of range");
            }
        }

        private static void positive(int value, String name) {
            if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        }

        private static void duration(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            try {
                value.toNanos();
            } catch (ArithmeticException tooLarge) {
                throw new IllegalArgumentException(name + " is too large", tooLarge);
            }
        }
    }
}
