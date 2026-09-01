package ai.ravenroot.extensions.telegram;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TelegramCallbackRegistryTest {
    private static final long RETAIN_NANOS = Duration.ofHours(1).toNanos();

    @Test void expiresTheSameKeyBelowCapacityWithoutEvictingUnexpiredKeys() {
        AtomicLong ticker = new AtomicLong();
        var registry = new TelegramRuntimeControls.CallbackRegistry(ticker::get, 4);
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "same"));
        ticker.incrementAndGet();
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "unrelated"));

        ticker.set(RETAIN_NANOS - 1);
        assertEquals(TelegramRuntimeControls.CallbackReservation.DUPLICATE,
                registry.reserve("tenant", "profile", "same"));

        ticker.set(RETAIN_NANOS);
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "same"));
        assertEquals(TelegramRuntimeControls.CallbackReservation.DUPLICATE,
                registry.reserve("tenant", "profile", "unrelated"));
        assertEquals(2, registry.size());
    }

    @Test void failsClosedWhenTheTickerMovesBackwardOrOverflows() {
        AtomicLong ticker = new AtomicLong(100);
        var registry = new TelegramRuntimeControls.CallbackRegistry(ticker::get, 4);
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "backward"));
        ticker.set(99);
        assertEquals(TelegramRuntimeControls.CallbackReservation.DUPLICATE,
                registry.reserve("tenant", "profile", "backward"));

        ticker.set(Long.MAX_VALUE);
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "overflow"));
        ticker.set(Long.MIN_VALUE);
        assertEquals(TelegramRuntimeControls.CallbackReservation.DUPLICATE,
                registry.reserve("tenant", "profile", "overflow"));
    }

    @Test void reservesOneWinnerAcrossSixtyFourConcurrentCalls() throws Exception {
        var registry = new TelegramRuntimeControls.CallbackRegistry(() -> 0L, 4);
        ExecutorService executor = Executors.newFixedThreadPool(64);
        CountDownLatch ready = new CountDownLatch(64);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<TelegramRuntimeControls.CallbackReservation>> reservations = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                reservations.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return registry.reserve("tenant", "profile", "same");
                }));
            }
            ready.await();
            start.countDown();
            int accepted = 0;
            int duplicates = 0;
            for (Future<TelegramRuntimeControls.CallbackReservation> reservation : reservations) {
                if (reservation.get() == TelegramRuntimeControls.CallbackReservation.ACCEPTED) accepted++;
                else duplicates++;
            }
            assertEquals(1, accepted);
            assertEquals(63, duplicates);
            assertEquals(1, registry.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void boundsKeysAndRetiresExpiredReservationsAcrossLongProcessPauses() {
        AtomicLong ticker = new AtomicLong();
        var registry = new TelegramRuntimeControls.CallbackRegistry(ticker::get, 2);
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "one"));
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "two"));
        assertEquals(TelegramRuntimeControls.CallbackReservation.FULL,
                registry.reserve("tenant", "profile", "three"));

        ticker.set(Duration.ofHours(2).toNanos());
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "three"));
        assertEquals(1, registry.size());

        ticker.set(Duration.ofHours(27).toNanos());
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "four"));
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant", "profile", "five"));
    }

    @Test void scopesTheSameCallbackIdentifierByTenantAndProfile() {
        var registry = new TelegramRuntimeControls.CallbackRegistry(() -> 0L, 4);
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant-a", "profile", "same"));
        assertEquals(TelegramRuntimeControls.CallbackReservation.DUPLICATE,
                registry.reserve("tenant-a", "profile", "same"));
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant-b", "profile", "same"));
        assertEquals(TelegramRuntimeControls.CallbackReservation.ACCEPTED,
                registry.reserve("tenant-a", "other", "same"));
    }
}
