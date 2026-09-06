package ai.ravenroot.extensions.matrix;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class MatrixRuntime {
    private final Supplier<MatrixConfiguration> resolver;
    private volatile MatrixConfiguration configuration;
    private volatile MatrixSyncStore store;
    final Clock clock;
    final MatrixRateLimiter rates;
    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    MatrixRuntime(Supplier<MatrixConfiguration> resolver) { this(resolver, Clock.systemUTC()); }
    MatrixRuntime(MatrixConfiguration configuration, MatrixSyncStore store, Clock clock) {
        this(() -> configuration, clock); this.configuration = java.util.Objects.requireNonNull(configuration);
        this.store = java.util.Objects.requireNonNull(store);
    }
    private MatrixRuntime(Supplier<MatrixConfiguration> resolver, Clock clock) {
        this.resolver = java.util.Objects.requireNonNull(resolver); this.clock = java.util.Objects.requireNonNull(clock);
        this.rates = new MatrixRateLimiter(clock);
    }

    MatrixConfiguration configuration() {
        MatrixConfiguration value = configuration;
        if (value == null) synchronized (this) {
            value = configuration;
            if (value == null) configuration = value = java.util.Objects.requireNonNull(resolver.get());
        }
        return value;
    }
    MatrixProfile profile(String tenant, String name) {
        return configuration().profile(tenant, name)
                .orElseThrow(() -> new MatrixException(MatrixException.Code.CONFIGURATION));
    }
    MatrixSyncStore store() {
        MatrixSyncStore value = store;
        if (value == null) synchronized (this) {
            value = store;
            if (value == null) store = value = new SqliteMatrixSyncStore(configuration().store(), clock);
        }
        return value;
    }
    Semaphore gate(MatrixProfile profile) {
        return gates.computeIfAbsent(profile.tenantId() + "\u0000" + profile.name(),
                ignored -> new Semaphore(profile.maxConcurrency()));
    }
}
