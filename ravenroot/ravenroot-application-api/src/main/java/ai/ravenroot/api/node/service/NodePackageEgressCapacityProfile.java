package ai.ravenroot.api.node.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

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
        // Before format v4, the managed bridge accepted whatever ratio the caller selected under
        // ExternalIoLimits' absolute 1,000x ceiling. Retaining 1,000 here preserves that exact SDK
        // contract; new compositions use the overload below to choose an explicit tighter profile.
        return bounded(maximumRequestBytes, maximumResponseBytes, maximumWebSocketMessageBytes,
                maximumWebSocketFragments, maximumConcurrentOperations, maximumConcurrentPerTenant,
                maximumQueuedWebSocketSends, 1_000, maximumDeadline, maximumWebSocketLifetime,
                maximumWebSocketIdle);
    }

    /** A service view with every quantitative limit, including decompression, explicitly resolved. */
    public static NodePackageEgressCapacityProfile bounded(
            long maximumRequestBytes, long maximumResponseBytes, long maximumWebSocketMessageBytes,
            int maximumWebSocketFragments, int maximumConcurrentOperations,
            int maximumConcurrentPerTenant, int maximumQueuedWebSocketSends,
            int maximumDecompressionRatio,
            Duration maximumDeadline, Duration maximumWebSocketLifetime,
            Duration maximumWebSocketIdle) {
        return new NodePackageEgressCapacityProfile(new Limits(maximumRequestBytes,
                maximumResponseBytes, maximumWebSocketMessageBytes, maximumWebSocketFragments,
                maximumConcurrentOperations, maximumConcurrentPerTenant,
                maximumQueuedWebSocketSends, OptionalInt.of(maximumDecompressionRatio),
                maximumDeadline, maximumWebSocketLifetime,
                maximumWebSocketIdle));
    }

    /** Historical v2/v3 capacity whose decompression authority was not represented. */
    public static NodePackageEgressCapacityProfile boundedWithoutDecompressionRatio(
            long maximumRequestBytes, long maximumResponseBytes, long maximumWebSocketMessageBytes,
            int maximumWebSocketFragments, int maximumConcurrentOperations,
            int maximumConcurrentPerTenant, int maximumQueuedWebSocketSends,
            Duration maximumDeadline, Duration maximumWebSocketLifetime,
            Duration maximumWebSocketIdle) {
        return new NodePackageEgressCapacityProfile(new Limits(maximumRequestBytes,
                maximumResponseBytes, maximumWebSocketMessageBytes, maximumWebSocketFragments,
                maximumConcurrentOperations, maximumConcurrentPerTenant,
                maximumQueuedWebSocketSends, OptionalInt.empty(), maximumDeadline,
                maximumWebSocketLifetime, maximumWebSocketIdle));
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
                         int maximumQueuedWebSocketSends, OptionalInt maximumDecompressionRatio,
                         Duration maximumDeadline,
                         Duration maximumWebSocketLifetime, Duration maximumWebSocketIdle) {
        /** Source-compatible v2/v3 constructor with the historical 1,000x absolute ratio ceiling. */
        public Limits(long maximumRequestBytes, long maximumResponseBytes,
                      long maximumWebSocketMessageBytes, int maximumWebSocketFragments,
                      int maximumConcurrentOperations, int maximumConcurrentPerTenant,
                      int maximumQueuedWebSocketSends, Duration maximumDeadline,
                      Duration maximumWebSocketLifetime, Duration maximumWebSocketIdle) {
            this(maximumRequestBytes, maximumResponseBytes, maximumWebSocketMessageBytes,
                    maximumWebSocketFragments, maximumConcurrentOperations,
                    maximumConcurrentPerTenant, maximumQueuedWebSocketSends, OptionalInt.of(1_000),
                    maximumDeadline, maximumWebSocketLifetime, maximumWebSocketIdle);
        }

        public Limits {
            bytes(maximumRequestBytes, "maximumRequestBytes");
            bytes(maximumResponseBytes, "maximumResponseBytes");
            bytes(maximumWebSocketMessageBytes, "maximumWebSocketMessageBytes");
            positive(maximumWebSocketFragments, "maximumWebSocketFragments");
            positive(maximumConcurrentOperations, "maximumConcurrentOperations");
            positive(maximumConcurrentPerTenant, "maximumConcurrentPerTenant");
            positive(maximumQueuedWebSocketSends, "maximumQueuedWebSocketSends");
            Objects.requireNonNull(maximumDecompressionRatio, "maximumDecompressionRatio");
            if (maximumDecompressionRatio.isPresent()
                    && (maximumDecompressionRatio.getAsInt() < 1
                    || maximumDecompressionRatio.getAsInt() > 1_000)) {
                throw new IllegalArgumentException("maximumDecompressionRatio is out of range");
            }
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
