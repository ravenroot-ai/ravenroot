package ai.ravenroot.extensions.slack;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class SlackRuntime {
    private final Supplier<SlackConfiguration> resolver;
    private volatile SlackConfiguration configuration;
    private volatile SlackDeliveryStore store;
    final Clock clock;
    final SlackRateLimiter rates;
    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    SlackRuntime(Supplier<SlackConfiguration> resolver) { this(resolver, Clock.systemUTC()); }
    SlackRuntime(SlackConfiguration configuration, SlackDeliveryStore store, Clock clock) {
        this(() -> configuration, clock); this.configuration = java.util.Objects.requireNonNull(configuration);
        this.store = java.util.Objects.requireNonNull(store);
    }
    private SlackRuntime(Supplier<SlackConfiguration> resolver, Clock clock) {
        this.resolver = java.util.Objects.requireNonNull(resolver); this.clock = java.util.Objects.requireNonNull(clock);
        this.rates = new SlackRateLimiter(clock);
    }

    SlackConfiguration configuration() {
        SlackConfiguration value = configuration;
        if (value == null) synchronized (this) {
            value = configuration;
            if (value == null) configuration = value = java.util.Objects.requireNonNull(resolver.get());
        }
        return value;
    }
    SlackProfile profile(String tenant, String name) {
        return configuration().profile(tenant, name)
                .orElseThrow(() -> new SlackException(SlackException.Code.CONFIGURATION));
    }
    SlackDeliveryStore store() {
        SlackDeliveryStore value = store;
        if (value == null) synchronized (this) {
            value = store;
            if (value == null) store = value = new SqliteSlackDeliveryStore(configuration().store());
        }
        return value;
    }
    Semaphore gate(SlackProfile profile) {
        return gates.computeIfAbsent(profile.tenantId() + "\u0000" + profile.name(),
                ignored -> new Semaphore(profile.maxConcurrency()));
    }
}
