package ai.ravenroot.api.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delay schedule, which is deterministic so that every assertion about it can be exact.
 *
 * <h2>The overflow cell is the one that is not obvious</h2>
 * <p>{@link #aLargeOrdinalSaturatesAtTheCeilingRatherThanOverflowing()} is red under the natural
 * {@code Math.pow} implementation: a large ordinal drives the exponent to infinity, the narrowing to
 * {@code long} clamps silently, and the result is a wait of hundreds of millions of years that looks
 * exactly like a correct one. It is not a theoretical input — an author's {@code maxAttempts} may be
 * as high as a hundred, and a multiplier of two at ordinal a hundred is already past every finite
 * bound.</p>
 */
class RetryBackoffTest {

    @Test
    @DisplayName("the initial attempt is never preceded by a wait")
    void ordinalOneWaitsForNothing() {
        assertEquals(Duration.ZERO, RetryBackoff.DEFAULT.delayBefore(1),
                "ordinal one IS the initial attempt; a wait there would delay every node in the graph");
    }

    @Test
    void theSecondAttemptWaitsExactlyTheInitialDelayAndEachFurtherOneMultiplies() {
        var backoff = new RetryBackoff(Duration.ofMillis(100), 2.0, Duration.ofSeconds(30));

        assertEquals(Duration.ofMillis(100), backoff.delayBefore(2));
        assertEquals(Duration.ofMillis(200), backoff.delayBefore(3));
        assertEquals(Duration.ofMillis(400), backoff.delayBefore(4));
        assertEquals(Duration.ofMillis(800), backoff.delayBefore(5));
    }

    @Test
    void aMultiplierOfOneIsAFixedDelay() {
        var backoff = new RetryBackoff(Duration.ofMillis(50), 1.0, Duration.ofSeconds(5));

        assertEquals(Duration.ofMillis(50), backoff.delayBefore(2));
        assertEquals(Duration.ofMillis(50), backoff.delayBefore(9));
    }

    @Test
    void theCeilingCapsEachWaitAndIsNeverExceeded() {
        var backoff = new RetryBackoff(Duration.ofMillis(100), 10.0, Duration.ofMillis(250));

        assertEquals(Duration.ofMillis(100), backoff.delayBefore(2));
        assertEquals(Duration.ofMillis(250), backoff.delayBefore(3));
        assertEquals(Duration.ofMillis(250), backoff.delayBefore(4));
    }

    @Test
    @DisplayName("a very large ordinal saturates at the ceiling instead of overflowing into a lifetime")
    void aLargeOrdinalSaturatesAtTheCeilingRatherThanOverflowing() {
        var backoff = new RetryBackoff(Duration.ofMillis(100), 2.0, Duration.ofSeconds(30));

        Duration far = backoff.delayBefore(1_000);
        assertEquals(Duration.ofSeconds(30), far,
                "an exponent that overflows a double narrows to Long.MAX_VALUE millis, which is a "
                        + "wait of ~292 million years produced silently");
        assertTrue(far.compareTo(backoff.maxDelay()) <= 0);
    }

    @Test
    void aZeroInitialDelayNeverWaitsAtAnyOrdinal() {
        assertEquals(Duration.ZERO, RetryBackoff.NONE.delayBefore(7));
    }

    // ------------------------------------------------------------------ refused shapes

    @Test
    @DisplayName("a shrinking backoff is refused: it is a retry storm written as a policy")
    void aMultiplierBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(10)));
    }

    @Test
    void aNonFiniteMultiplierIsRefusedRatherThanProducingANonFiniteDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ofSeconds(1), Double.NaN, Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ofSeconds(1), Double.POSITIVE_INFINITY,
                        Duration.ofSeconds(10)));
    }

    @Test
    void aCeilingBelowTheFirstWaitIsIncoherentAndIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ofSeconds(10), 2.0, Duration.ofSeconds(1)));
    }

    @Test
    void negativeDurationsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ofSeconds(-1), 2.0, Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class,
                () -> new RetryBackoff(Duration.ZERO, 2.0, Duration.ofSeconds(-1)));
    }

    @Test
    void anOrdinalBelowOneIsRefusedRatherThanAnsweredWithZero() {
        assertThrows(IllegalArgumentException.class, () -> RetryBackoff.DEFAULT.delayBefore(0));
    }
}
