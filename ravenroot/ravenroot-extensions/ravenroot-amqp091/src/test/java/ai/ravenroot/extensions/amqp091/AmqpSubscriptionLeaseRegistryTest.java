package ai.ravenroot.extensions.amqp091;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AmqpSubscriptionLeaseRegistryTest {
    @Test
    void enforcesProfileTenantAndGlobalCeilingsAndReleasesExactlyOnce() {
        var registry = new AmqpSubscriptionLeaseRegistry(3, 2);
        var first = registry.tryAcquire("tenant-a", "profile-a", 1);
        assertNotNull(first);
        assertNull(registry.tryAcquire("tenant-a", "profile-a", 1));
        var second = registry.tryAcquire("tenant-a", "profile-b", 2);
        assertNotNull(second);
        assertNull(registry.tryAcquire("tenant-a", "profile-b", 2), "tenant ceiling");
        var third = registry.tryAcquire("tenant-b", "profile-c", 2);
        assertNotNull(third);
        assertNull(registry.tryAcquire("tenant-c", "profile-d", 2), "global ceiling");

        first.close();
        first.close();
        assertNotNull(registry.tryAcquire("tenant-a", "profile-a", 1));
        second.close();
        third.close();
    }
}
