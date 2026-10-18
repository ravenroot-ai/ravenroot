package ai.ravenroot.extensions.amqp091;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local bounded admission for broker-owned ephemeral subscriptions. */
final class AmqpSubscriptionLeaseRegistry {
    static final int GLOBAL_LIMIT = 32;
    static final int TENANT_LIMIT = 16;

    private final int globalLimit;
    private final int tenantLimit;
    private int global;
    private final Map<String, Integer> tenants = new HashMap<>();
    private final Map<String, Integer> profiles = new HashMap<>();

    AmqpSubscriptionLeaseRegistry() { this(GLOBAL_LIMIT, TENANT_LIMIT); }

    AmqpSubscriptionLeaseRegistry(int globalLimit, int tenantLimit) {
        if (globalLimit < 1 || tenantLimit < 1 || tenantLimit > globalLimit) {
            throw new IllegalArgumentException("invalid subscription lease limits");
        }
        this.globalLimit = globalLimit;
        this.tenantLimit = tenantLimit;
    }

    synchronized Lease tryAcquire(String tenant, String profile, int profileLimit) {
        String profileKey = tenant + "\0" + profile;
        int tenantCount = tenants.getOrDefault(tenant, 0);
        int profileCount = profiles.getOrDefault(profileKey, 0);
        if (profileLimit < 1 || global >= globalLimit || tenantCount >= tenantLimit
                || profileCount >= profileLimit) {
            return null;
        }
        global++;
        tenants.put(tenant, tenantCount + 1);
        profiles.put(profileKey, profileCount + 1);
        return new Lease(this, tenant, profileKey);
    }

    private synchronized void release(String tenant, String profileKey) {
        global--;
        decrement(tenants, tenant);
        decrement(profiles, profileKey);
    }

    synchronized int active() { return global; }

    private static void decrement(Map<String, Integer> counts, String key) {
        int next = counts.getOrDefault(key, 0) - 1;
        if (next <= 0) counts.remove(key); else counts.put(key, next);
    }

    static final class Lease implements AutoCloseable {
        private final AmqpSubscriptionLeaseRegistry owner;
        private final String tenant;
        private final String profileKey;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(AmqpSubscriptionLeaseRegistry owner, String tenant, String profileKey) {
            this.owner = owner;
            this.tenant = tenant;
            this.profileKey = profileKey;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.release(tenant, profileKey);
        }
    }
}
