package ai.ravenroot.extensions.spel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpelRuntimeTest {
    @Test
    void deadlineDoesNotReleaseTheNodePermitUntilTheIgnoringWorkerActuallyExits() throws Exception {
        Semaphore node = new Semaphore(1);
        long stop = System.nanoTime() + SpelBounds.DEADLINE.plusMillis(350).toNanos();
        var first = SpelRuntime.evaluate(node, () -> {
            while (System.nanoTime() - stop < 0) {
                Thread.interrupted();
                Thread.onSpinWait();
            }
            return "late";
        });

        SpelNodeException timeout = SpelTestSupport.failure(assertThrows(RuntimeException.class, first::join));
        assertEquals(SpelNodeException.Code.DEADLINE_EXCEEDED, timeout.code());

        var duringCleanup = SpelRuntime.evaluate(node, () -> "replacement");
        SpelNodeException capacity = SpelTestSupport.failure(
                assertThrows(RuntimeException.class, duringCleanup::join));
        assertEquals(SpelNodeException.Code.CAPACITY_UNAVAILABLE, capacity.code());

        long permitDeadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (node.availablePermits() == 0 && System.nanoTime() - permitDeadline < 0) {
            Thread.sleep(10);
        }
        assertEquals("replacement", SpelRuntime.evaluate(node, () -> "replacement").join());
    }
}
