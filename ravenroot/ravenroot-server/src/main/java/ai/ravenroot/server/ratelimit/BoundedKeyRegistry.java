package ai.ravenroot.server.ratelimit;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A map of limiter state that cannot grow without bound, whatever the caller does.
 *
 * <p>This is the class the rest of the package exists around. A rate limiter allocates one entry per
 * distinct key, and its keys are client addresses, tenants and principals — all influenced, directly or
 * indirectly, by whoever is sending the traffic. A limiter that keeps an entry per key it has ever seen
 * is a memory-exhaustion vector wearing the costume of a defence, and it is one that a passing test
 * suite will never show, because every functional test uses a handful of keys.</p>
 *
 * <p>Two independent mechanisms bound it, and each covers the case the other misses:</p>
 *
 * <ul>
 *   <li><strong>Idle eviction</strong> reclaims entries that have returned to their initial state and
 *       have not been touched for {@code idleTtlNanos}. This is what keeps steady-state memory
 *       proportional to the <em>active</em> key count rather than the historical one. An entry is only
 *       eligible when {@link Evictable#evictable} says it carries no enforcement value, so forgetting
 *       it can never hand a caller a reset limit.</li>
 *   <li><strong>A hard ceiling</strong> caps the map at {@code maxEntries}. When admission would exceed
 *       it, a sweep runs first; if the map is still full — meaning every entry is actively holding a
 *       limit — the new key is <strong>refused rather than admitted</strong>. This is the fail-closed
 *       half: under a flood of distinct keys the limiter sheds load instead of allocating, and the
 *       ceiling holds even when no entry is idle enough to evict.</li>
 * </ul>
 *
 * <p>Refusing admission is a deliberate trade. It means that once the ceiling is reached a genuinely
 * new client is rejected until capacity frees up, which is a real availability cost. The alternative —
 * evicting an active entry to make room — lets an attacker with many source addresses evict the very
 * buckets that are throttling it, turning the registry into a limit-reset oracle. Between denying new
 * work and disabling the limiter, a security control must choose the former; the idle sweep and the
 * TTL are what make the condition self-clearing rather than permanent.</p>
 *
 * <p>A sweep also runs every {@code SWEEP_INTERVAL} admissions so that a registry which never reaches
 * its ceiling still returns memory, instead of resting at the ceiling forever.</p>
 */
final class BoundedKeyRegistry<V extends BoundedKeyRegistry.Evictable> {
    /** Amortises the O(n) sweep across admissions so no single request pays for the whole map. */
    private static final int SWEEP_INTERVAL = 1024;

    /** State that can say whether forgetting it would weaken the limit it enforces. */
    interface Evictable {
        boolean evictable(long nowNanos, long idleTtlNanos);
    }

    private final String name;
    private final int maxEntries;
    private final long idleTtlNanos;
    private final LongSupplier nanoTime;
    private final Map<String, V> entries = new HashMap<>();
    private long admissions;

    BoundedKeyRegistry(String name, int maxEntries, long idleTtlNanos, LongSupplier nanoTime) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException(name + " capacity must be positive");
        }
        if (idleTtlNanos < 1) {
            throw new IllegalArgumentException(name + " idle TTL must be positive");
        }
        this.name = Objects.requireNonNull(name, "name");
        this.maxEntries = maxEntries;
        this.idleTtlNanos = idleTtlNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Returns the state for {@code key}, creating it if there is room.
     *
     * @return {@code null} when the registry is full of entries that are all still enforcing something.
     *         The caller must treat this as a rejection, never as "no limit applies".
     */
    synchronized V obtain(String key, java.util.function.LongFunction<V> factory) {
        V existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        long now = nanoTime.getAsLong();
        if (++admissions % SWEEP_INTERVAL == 0 || entries.size() >= maxEntries) {
            sweep(now);
        }
        if (entries.size() >= maxEntries) {
            return null;
        }
        V created = factory.apply(now);
        entries.put(key, created);
        return created;
    }

    /** Drops every entry that no longer carries enforcement value. */
    synchronized void sweep() {
        sweep(nanoTime.getAsLong());
    }

    private void sweep(long nowNanos) {
        Iterator<Map.Entry<String, V>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().evictable(nowNanos, idleTtlNanos)) {
                iterator.remove();
            }
        }
    }

    /** Live entry count, so a test can assert the bound rather than trust the argument for it. */
    synchronized int size() {
        return entries.size();
    }

    /**
     * Reads existing state without admitting a key.
     *
     * <p>Observation must never allocate: a caller asking "how many slots are held" would otherwise
     * create the entry it was asking about, and could fill the registry by asking about keys that do
     * not exist.</p>
     */
    synchronized V peek(String key) {
        return entries.get(key);
    }

    int maxEntries() {
        return maxEntries;
    }

    String name() {
        return name;
    }

    /** Releases everything on shutdown; the limiter must not outlive the server that owns it. */
    synchronized void clear() {
        entries.clear();
    }
}
