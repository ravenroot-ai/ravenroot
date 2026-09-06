package ai.ravenroot.extensions.teams;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class TeamsRuntime {
    private final Supplier<TeamsConfiguration> resolver;
    private volatile TeamsConfiguration configuration;
    private volatile TeamsDeliveryStore store;
    final Clock clock;
    final TeamsRateLimiter rates;
    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    TeamsRuntime(Supplier<TeamsConfiguration> resolver) { this(resolver, Clock.systemUTC()); }
    TeamsRuntime(TeamsConfiguration configuration, TeamsDeliveryStore store, Clock clock) {
        this(() -> configuration, clock); this.configuration = java.util.Objects.requireNonNull(configuration);
        this.store = java.util.Objects.requireNonNull(store);
    }
    private TeamsRuntime(Supplier<TeamsConfiguration> resolver, Clock clock) {
        this.resolver = java.util.Objects.requireNonNull(resolver); this.clock = java.util.Objects.requireNonNull(clock);
        this.rates = new TeamsRateLimiter(clock);
    }

    TeamsConfiguration configuration() {
        TeamsConfiguration value = configuration;
        if (value == null) synchronized (this) {
            value = configuration;
            if (value == null) configuration = value = java.util.Objects.requireNonNull(resolver.get());
        }
        return value;
    }
    TeamsProfile profile(String tenant, String name) {
        return configuration().profile(tenant, name)
                .orElseThrow(() -> new TeamsException(TeamsException.Code.CONFIGURATION));
    }
    TeamsDeliveryStore store() {
        TeamsDeliveryStore value = store;
        if (value == null) synchronized (this) {
            value = store;
            if (value == null) store = value = new SqliteTeamsDeliveryStore(configuration().store());
        }
        return value;
    }
    Semaphore gate(TeamsProfile profile) {
        return gates.computeIfAbsent(profile.tenantId() + "\u0000" + profile.name(),
                ignored -> new Semaphore(profile.maxConcurrency()));
    }
}
