package ai.ravenroot.extensions.discord;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class DiscordRuntime {
    private final Supplier<DiscordConfiguration> resolver;
    private volatile DiscordConfiguration configuration;
    private volatile DiscordDeliveryStore store;
    final Clock clock;
    final DiscordRateLimiter rates;
    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    DiscordRuntime(Supplier<DiscordConfiguration> resolver) { this(resolver, Clock.systemUTC()); }
    DiscordRuntime(DiscordConfiguration configuration, DiscordDeliveryStore store, Clock clock) {
        this(() -> configuration, clock); this.configuration = java.util.Objects.requireNonNull(configuration);
        this.store = java.util.Objects.requireNonNull(store);
    }
    private DiscordRuntime(Supplier<DiscordConfiguration> resolver, Clock clock) {
        this.resolver = java.util.Objects.requireNonNull(resolver); this.clock = java.util.Objects.requireNonNull(clock);
        this.rates = new DiscordRateLimiter(clock);
    }

    DiscordConfiguration configuration() {
        DiscordConfiguration value = configuration;
        if (value == null) synchronized (this) {
            value = configuration;
            if (value == null) configuration = value = java.util.Objects.requireNonNull(resolver.get());
        }
        return value;
    }
    DiscordProfile profile(String tenant, String name) {
        return configuration().profile(tenant, name)
                .orElseThrow(() -> new DiscordException(DiscordException.Code.CONFIGURATION));
    }
    DiscordDeliveryStore store() {
        DiscordDeliveryStore value = store;
        if (value == null) synchronized (this) {
            value = store;
            if (value == null) store = value = new SqliteDiscordDeliveryStore(configuration().store());
        }
        return value;
    }
    Semaphore gate(DiscordProfile profile) {
        return gates.computeIfAbsent(profile.tenantId() + "\u0000" + profile.name(),
                ignored -> new Semaphore(profile.maxConcurrency()));
    }
}
