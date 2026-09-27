package ai.ravenroot.server.embed;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression contract for the deployment-observation stream's coalescing wakeup. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class EmbedObservationWakeupTest {
    @Test
    void aBurstNeverBlocksAndLeavesOnlyOnePendingWakeup() throws Exception {
        var wakeup = new EmbedBrowserHttpHandler.ObservationWakeup();
        long started = System.nanoTime();
        for (int index = 0; index < 100_000; index++) {
            wakeup.signal(event());
        }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10),
                "observation notification must never block the runtime publisher");
        assertTrue(wakeup.await(0), "one hint must remain after the burst");
        assertFalse(wakeup.await(50),
                "the burst must not leave permits that cause redundant reads and keepalive spin");
    }

    private static ExecutionEvent event() {
        return new ExecutionEvent(1, Instant.EPOCH, "tenant", "request", "deployment", "version",
                UUID.randomUUID(), UUID.randomUUID(), null, null,
                ExecutionEventType.NODE_STARTED, null, 0, false, "poke");
    }
}
