package ai.ravenroot.api.node.service;

import java.time.Duration;
import java.util.Objects;

/** Quantitative traversal-send policy resolved for one exact graph node binding. */
public record NodeExternalIoCapacity(int maximumMessageBytes, int maximumFragments,
                                     Duration maximumTimeout, int maximumConcurrency) {
    public NodeExternalIoCapacity {
        if (maximumMessageBytes < 1) throw new IllegalArgumentException("maximumMessageBytes must be positive");
        if (maximumFragments < 1) throw new IllegalArgumentException("maximumFragments must be positive");
        if (maximumConcurrency < 1) throw new IllegalArgumentException("maximumConcurrency must be positive");
        Objects.requireNonNull(maximumTimeout, "maximumTimeout");
        if (maximumTimeout.isZero() || maximumTimeout.isNegative()) {
            throw new IllegalArgumentException("maximumTimeout must be positive");
        }
        try {
            maximumTimeout.toNanos();
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("maximumTimeout is too large", tooLarge);
        }
    }
}
