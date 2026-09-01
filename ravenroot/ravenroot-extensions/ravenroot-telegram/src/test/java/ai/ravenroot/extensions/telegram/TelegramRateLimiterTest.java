package ai.ravenroot.extensions.telegram;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TelegramRateLimiterTest {
    @Test void delayedSamplesCannotEraseNewerAcceptanceAcrossSkews() throws Exception {
        long newer = Duration.ofSeconds(2).toNanos();
        for (long skew : new long[]{Duration.ofMillis(100).toNanos(),
                TelegramRateLimiter.WINDOW_NANOS, TelegramRateLimiter.WINDOW_NANOS + 1}) {
            ReorderedTicker ticker = new ReorderedTicker(
                    Duration.ofSeconds(1).toNanos(), newer - skew, newer);
            TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 16);
            assertTrue(limiter.allow("tenant", "profile", 1));

            try (var executor = Executors.newFixedThreadPool(2)) {
                var delayed = executor.submit(() -> limiter.allow("tenant", "profile", 1));
                ticker.awaitDelayedSample();
                var accepted = executor.submit(() -> {
                    boolean result = limiter.allow("tenant", "profile", 1);
                    ticker.newerCompleted.countDown();
                    return result;
                });

                assertTrue(accepted.get(2, TimeUnit.SECONDS));
                assertFalse(delayed.get(2, TimeUnit.SECONDS));
            }

            assertEquals(java.util.List.of(newer), limiter.acceptedTimestamps("tenant", "profile"));
            ticker.set(newer + TelegramRateLimiter.WINDOW_NANOS - 1);
            assertFalse(limiter.allow("tenant", "profile", 1),
                    "a delayed sample must not create capacity before the newer acceptance is one second old");
            assertEquals(java.util.List.of(newer), limiter.acceptedTimestamps("tenant", "profile"));
            ticker.set(newer + TelegramRateLimiter.WINDOW_NANOS);
            assertTrue(limiter.allow("tenant", "profile", 1));
            assertEquals(java.util.List.of(newer + TelegramRateLimiter.WINDOW_NANOS),
                    limiter.acceptedTimestamps("tenant", "profile"));
        }
    }

    @Test void rollingWindowDoesNotDoubleAdmitAcrossACalendarSecondBoundary() {
        MutableTicker ticker = new MutableTicker(999_999_999L);
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 16);
        assertTrue(limiter.allow("tenant", "profile", 2));
        assertTrue(limiter.allow("tenant", "profile", 2));

        ticker.set(1_000_000_000L);
        assertFalse(limiter.allow("tenant", "profile", 2), "one nanosecond later must still be saturated");
        ticker.set(1_999_999_998L);
        assertFalse(limiter.allow("tenant", "profile", 2), "just before one elapsed second must still be saturated");
        ticker.set(1_999_999_999L);
        assertTrue(limiter.allow("tenant", "profile", 2), "exactly one elapsed second releases the oldest request");
        ticker.set(2_000_000_000L);
        assertTrue(limiter.allow("tenant", "profile", 2), "just after one elapsed second may use the remaining burst slot");
        assertFalse(limiter.allow("tenant", "profile", 2), "the burst remains bounded just after the boundary");
    }

    @Test void clockDiscontinuitiesAndOverflowAdjacentValuesNeverRefill() {
        MutableTicker ticker = new MutableTicker(10_000_000_000L);
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 16);
        assertTrue(limiter.allow("tenant", "profile", 1));

        ticker.set(9_999_999_999L);
        assertFalse(limiter.allow("tenant", "profile", 1), "backward time must fail closed");
        assertEquals(java.util.List.of(10_000_000_000L), limiter.acceptedTimestamps("tenant", "profile"),
                "backward time must retain the greatest trusted observation");
        ticker.set(Long.MAX_VALUE - 4);
        assertFalse(limiter.allow("tenant", "profile", 1), "an implausibly large forward step must fail closed");
        assertEquals(java.util.List.of(Long.MAX_VALUE - 4), limiter.acceptedTimestamps("tenant", "profile"));
        ticker.set(Long.MIN_VALUE + 4);
        assertFalse(limiter.allow("tenant", "profile", 1), "overflow-adjacent time must fail closed");
        assertEquals(java.util.List.of(Long.MIN_VALUE + 4), limiter.acceptedTimestamps("tenant", "profile"));
        ticker.set(Long.MIN_VALUE + 4 + TelegramRateLimiter.WINDOW_NANOS);
        assertTrue(limiter.allow("tenant", "profile", 1), "one stable elapsed second recovers from quarantine");
    }

    @Test void backwardQuarantineStartsAtTheGreatestTrustedObservation() {
        long accepted = Duration.ofSeconds(10).toNanos();
        long observed = accepted + Duration.ofMillis(500).toNanos();
        MutableTicker ticker = new MutableTicker(accepted);
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 16);
        assertTrue(limiter.allow("tenant", "profile", 1));
        ticker.set(observed);
        assertFalse(limiter.allow("tenant", "profile", 1));

        ticker.set(observed - 1);
        assertFalse(limiter.allow("tenant", "profile", 1));
        assertEquals(java.util.List.of(observed), limiter.acceptedTimestamps("tenant", "profile"));
        ticker.set(observed + TelegramRateLimiter.WINDOW_NANOS - 1);
        assertFalse(limiter.allow("tenant", "profile", 1));
        ticker.set(observed + TelegramRateLimiter.WINDOW_NANOS);
        assertTrue(limiter.allow("tenant", "profile", 1));
    }

    @Test void delayedDiscontinuitiesNeverReanchorOrReplaceNewerAcceptance() throws Exception {
        long initial = Duration.ofSeconds(1).toNanos();
        long newer = Duration.ofSeconds(2).toNanos();
        long[] delayedValues = {
                newer - 1,
                newer + TelegramRateLimiter.MAX_TRUSTED_STEP_NANOS + 1,
                Long.MAX_VALUE - 4,
                Long.MIN_VALUE + 4
        };
        for (long delayedValue : delayedValues) {
            ReorderedTicker ticker = new ReorderedTicker(initial, delayedValue, newer);
            TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 16);
            assertTrue(limiter.allow("tenant", "profile", 1));

            try (var executor = Executors.newFixedThreadPool(2)) {
                var delayed = executor.submit(() -> limiter.allow("tenant", "profile", 1));
                ticker.awaitDelayedSample();
                var accepted = executor.submit(() -> {
                    boolean result = limiter.allow("tenant", "profile", 1);
                    ticker.newerCompleted.countDown();
                    return result;
                });
                assertTrue(accepted.get(2, TimeUnit.SECONDS));
                assertFalse(delayed.get(2, TimeUnit.SECONDS));
            }

            assertEquals(java.util.List.of(newer), limiter.acceptedTimestamps("tenant", "profile"));
            ticker.set(newer + TelegramRateLimiter.WINDOW_NANOS - 1);
            assertFalse(limiter.allow("tenant", "profile", 1));
            assertEquals(java.util.List.of(newer), limiter.acceptedTimestamps("tenant", "profile"));
        }
    }

    @Test void refillEvictionAndTenantProfileIsolationAreDeterministic() {
        MutableTicker ticker = new MutableTicker(0L);
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 2);
        assertTrue(limiter.allow("tenant-a", "profile", 1));
        assertFalse(limiter.allow("tenant-a", "profile", 1));
        assertTrue(limiter.allow("tenant-b", "profile", 1));
        assertFalse(limiter.allow("tenant-b", "profile", 1));
        assertFalse(limiter.allow("tenant-c", "profile", 1), "the bounded key table is full");

        ticker.set(Duration.ofMinutes(5).toNanos());
        assertTrue(limiter.allow("tenant-c", "profile", 1), "idle keys are evicted by the same monotonic clock");
        assertEquals(1, limiter.size());
    }

    @Test void concurrentCallsCannotExceedTheConfiguredBurst() throws Exception {
        MutableTicker ticker = new MutableTicker(123L);
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 16);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int index = 0; index < 64; index++)
                tasks.add(() -> limiter.allow("tenant", "profile", 7));
            long admitted = executor.invokeAll(tasks).stream().filter(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).count();
            assertEquals(7, admitted);
        }
    }

    @Test void discontinuitiesCannotEvictAKeyAndCreateFreshCapacity() {
        MutableTicker forward = new MutableTicker(0L);
        TelegramRateLimiter forwardLimiter = new TelegramRateLimiter(forward, 1);
        assertTrue(forwardLimiter.allow("tenant-a", "profile", 1));
        forward.set(Long.MAX_VALUE);
        assertFalse(forwardLimiter.allow("tenant-b", "profile", 1));
        assertEquals(1, forwardLimiter.size());

        MutableTicker overflow = new MutableTicker(Long.MAX_VALUE - 4);
        TelegramRateLimiter overflowLimiter = new TelegramRateLimiter(overflow, 1);
        assertTrue(overflowLimiter.allow("tenant-a", "profile", 1));
        overflow.set(Long.MIN_VALUE + 4);
        assertFalse(overflowLimiter.allow("tenant-b", "profile", 1));
        assertEquals(1, overflowLimiter.size());
    }

    @Test void inFlightAllowPreventsRetirementAndReplacement() throws Exception {
        AllowEvictionTicker ticker = new AllowEvictionTicker();
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 1);
        assertTrue(limiter.allow("tenant-a", "profile", 1));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var existing = executor.submit(() -> limiter.allow("tenant-a", "profile", 1));
            ticker.awaitAllowSample();
            var replacement = executor.submit(() -> {
                boolean result = limiter.allow("tenant-b", "profile", 1);
                ticker.evictionAttempted.countDown();
                return result;
            });
            assertFalse(replacement.get(2, TimeUnit.SECONDS));
            assertTrue(existing.get(2, TimeUnit.SECONDS));
        }

        assertEquals(1, limiter.size());
        assertEquals(java.util.List.of(TelegramRateLimiter.WINDOW_NANOS),
                limiter.acceptedTimestamps("tenant-a", "profile"));
        assertTrue(limiter.acceptedTimestamps("tenant-b", "profile").isEmpty());
    }

    @Test void staleRetirementSampleCannotLoseNewerEventOrReplaceLiveWindow() throws Exception {
        RetirementReorderTicker ticker = new RetirementReorderTicker();
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 1);
        assertTrue(limiter.allow("tenant-a", "profile", 1));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var replacement = executor.submit(() -> limiter.allow("tenant-b", "profile", 1));
            ticker.awaitRetirementSample();
            var existing = executor.submit(() -> {
                boolean result = limiter.allow("tenant-a", "profile", 1);
                ticker.newerCompleted.countDown();
                return result;
            });
            assertTrue(existing.get(2, TimeUnit.SECONDS));
            assertFalse(replacement.get(2, TimeUnit.SECONDS));
        }

        assertEquals(1, limiter.size());
        assertEquals(java.util.List.of(TelegramRateLimiter.WINDOW_NANOS),
                limiter.acceptedTimestamps("tenant-a", "profile"));
        assertTrue(limiter.acceptedTimestamps("tenant-b", "profile").isEmpty());
        ticker.set(2 * TelegramRateLimiter.WINDOW_NANOS - 1);
        assertFalse(limiter.allow("tenant-a", "profile", 1));
        ticker.set(2 * TelegramRateLimiter.WINDOW_NANOS);
        assertTrue(limiter.allow("tenant-a", "profile", 1));
    }

    @Test void concurrentDistinctKeysRemainStrictlyBounded() throws Exception {
        MutableTicker ticker = new MutableTicker(0L);
        TelegramRateLimiter limiter = new TelegramRateLimiter(ticker, 4);
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Boolean>>();
            for (int index = 0; index < 100; index++) {
                String tenant = "tenant-" + index;
                tasks.add(() -> limiter.allow(tenant, "profile", 1));
            }
            long admitted = executor.invokeAll(tasks).stream().filter(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).count();
            assertTrue(admitted <= 4);
        }
        assertEquals(4, limiter.size());
    }

    private static final class MutableTicker implements java.util.function.LongSupplier {
        private final AtomicLong value;
        private MutableTicker(long initial) { value = new AtomicLong(initial); }
        void set(long next) { value.set(next); }
        @Override public long getAsLong() { return value.get(); }
    }

    private static final class ReorderedTicker implements java.util.function.LongSupplier {
        private final long initial;
        private final long delayed;
        private final long newer;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong current;
        private final CountDownLatch delayedSampled = new CountDownLatch(1);
        private final CountDownLatch newerCompleted = new CountDownLatch(1);

        private ReorderedTicker(long initial, long delayed, long newer) {
            this.initial = initial;
            this.delayed = delayed;
            this.newer = newer;
            this.current = new AtomicLong(newer);
        }

        void awaitDelayedSample() throws InterruptedException {
            assertTrue(delayedSampled.await(2, TimeUnit.SECONDS));
        }

        void set(long value) { current.set(value); }

        @Override public long getAsLong() {
            return switch (calls.getAndIncrement()) {
                case 0 -> initial;
                case 1 -> {
                    delayedSampled.countDown();
                    try { assertTrue(newerCompleted.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    yield delayed;
                }
                case 2 -> newer;
                default -> current.get();
            };
        }
    }

    private static final class AllowEvictionTicker implements java.util.function.LongSupplier {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch allowSampled = new CountDownLatch(1);
        private final CountDownLatch evictionAttempted = new CountDownLatch(1);

        void awaitAllowSample() throws InterruptedException {
            assertTrue(allowSampled.await(2, TimeUnit.SECONDS));
        }

        @Override public long getAsLong() {
            return switch (calls.getAndIncrement()) {
                case 0 -> 0L;
                case 1 -> {
                    allowSampled.countDown();
                    try { assertTrue(evictionAttempted.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    yield TelegramRateLimiter.WINDOW_NANOS;
                }
                default -> TelegramRateLimiter.WINDOW_NANOS;
            };
        }
    }

    private static final class RetirementReorderTicker implements java.util.function.LongSupplier {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong current = new AtomicLong(TelegramRateLimiter.WINDOW_NANOS);
        private final CountDownLatch retirementSampled = new CountDownLatch(1);
        private final CountDownLatch newerCompleted = new CountDownLatch(1);

        void awaitRetirementSample() throws InterruptedException {
            assertTrue(retirementSampled.await(2, TimeUnit.SECONDS));
        }

        void set(long value) { current.set(value); }

        @Override public long getAsLong() {
            return switch (calls.getAndIncrement()) {
                case 0 -> 0L;
                case 1 -> {
                    retirementSampled.countDown();
                    try { assertTrue(newerCompleted.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    yield TelegramRateLimiter.EVICT_AFTER_NANOS;
                }
                case 2 -> TelegramRateLimiter.WINDOW_NANOS;
                default -> current.get();
            };
        }
    }
}
