package ai.ravenroot.extensions.amqp091;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AmqpConsumerLeaseRegistryTest {
    @Test void sharedHoldersCoexistAndMixedModesConflictInEitherOrder() {
        var leases = new AmqpConsumerLeaseRegistry();
        var first = leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.SHARED);
        var second = leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.SHARED);
        assertNotNull(first);
        assertNotNull(second);
        assertNull(leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.EXCLUSIVE));
        assertEquals(2, leases.activeLeases());
        first.close();
        assertEquals(1, leases.activeLeases());
        second.close();

        var exclusive = leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.EXCLUSIVE);
        assertNotNull(exclusive);
        assertNull(leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.SHARED));
        exclusive.close();
        assertEquals(0, leases.activeResources());
    }

    @Test void concurrentExclusiveAcquisitionHasOneWinnerAndClosedTokensCannotRemoveANewerLease() throws Exception {
        var leases = new AmqpConsumerLeaseRegistry();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(ignored -> executor.submit(() -> {
                        gate.await();
                        return leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.EXCLUSIVE);
                    })).toList();
            gate.countDown();
            List<AmqpConsumerLeaseRegistry.Lease> acquired = futures.stream()
                    .map(future -> {
                        try { return future.get(); }
                        catch (Exception failure) { throw new AssertionError(failure); }
                    }).filter(java.util.Objects::nonNull).toList();
            assertEquals(1, acquired.size());
            var first = acquired.getFirst();
            first.close();
            var replacement = leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.EXCLUSIVE);
            assertNotNull(replacement);
            first.close();
            assertNull(leases.tryAcquire("queue", AmqpConsumerLeaseRegistry.Mode.EXCLUSIVE));
            replacement.close();
            assertEquals(0, leases.activeResources());
        }
    }
}
