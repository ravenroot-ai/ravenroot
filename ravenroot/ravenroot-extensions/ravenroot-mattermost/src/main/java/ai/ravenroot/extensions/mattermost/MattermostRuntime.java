package ai.ravenroot.extensions.mattermost;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class MattermostRuntime {
    private final Supplier<MattermostConfiguration> resolver;
    private volatile MattermostConfiguration configuration;
    private volatile MattermostDeliveryStore store;
    final Clock clock;
    final MattermostRateLimiter rates;
    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();

    MattermostRuntime(Supplier<MattermostConfiguration> resolver) { this(resolver, Clock.systemUTC()); }
    MattermostRuntime(MattermostConfiguration configuration, MattermostDeliveryStore store, Clock clock) {
        this(() -> configuration, clock); this.configuration = java.util.Objects.requireNonNull(configuration);
        this.store = java.util.Objects.requireNonNull(store);
    }
    private MattermostRuntime(Supplier<MattermostConfiguration> resolver, Clock clock) {
        this.resolver = java.util.Objects.requireNonNull(resolver); this.clock = java.util.Objects.requireNonNull(clock);
        rates = new MattermostRateLimiter(clock);
    }
    MattermostConfiguration configuration() {
        MattermostConfiguration value = configuration;
        if (value == null) synchronized (this) {
            value = configuration;
            if (value == null) configuration = value = java.util.Objects.requireNonNull(resolver.get());
        }
        return value;
    }
    MattermostProfile profile(String tenant, String name) {
        return configuration().profile(tenant, name)
                .orElseThrow(() -> new MattermostException(MattermostException.Code.CONFIGURATION));
    }
    MattermostDeliveryStore store() {
        MattermostDeliveryStore value = store;
        if (value == null) synchronized (this) {
            value = store;
            if (value == null) store = value = new SqliteMattermostDeliveryStore(configuration().store());
        }
        return value;
    }
    Semaphore gate(MattermostProfile profile) {
        return gates.computeIfAbsent(profile.tenantId() + "\u0000" + profile.name(),
                ignored -> new Semaphore(profile.maxConcurrency()));
    }
}
