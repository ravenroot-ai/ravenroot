package ai.ravenroot.server.ratelimit;

import com.sun.net.httpserver.Headers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Tenant-aware limits for the HTTP adapter.
 *
 * <h2>What each limit is keyed on, and why</h2>
 *
 * <p>Before authentication the server knows exactly one thing about a caller: where the packets came
 * from. So the pre-authentication limit is keyed on the client address resolved by
 * {@link TrustedProxyConfiguration}, and nothing else — any claimed identity at that point is just a
 * header. This is also the only limit an unauthenticated caller can allocate state under, which is why
 * that registry is the one with the strictest ceiling.</p>
 *
 * <p>After authentication there are two keys, and both are needed. Keying only on the tenant lets one
 * runaway user inside a tenant consume that tenant's whole budget; keying only on the principal lets a
 * tenant with many principals consume the whole server. Checking tenant first and principal second
 * gives fair share <em>between</em> tenants and fair share <em>within</em> one.</p>
 *
 * <p>Held resources — in-flight submissions and open streams — use {@link ConcurrencyGate} rather than
 * a bucket, because their cost is duration, not frequency.</p>
 *
 * <h2>Retention</h2>
 *
 * <p>Every map here is a {@link BoundedKeyRegistry}: hard-capped, idle-evicted, and cleared on
 * shutdown. No collection in this class grows with the number of distinct keys ever seen. The ceilings
 * are configuration, and {@link #trackedClients()}, {@link #trackedTenants()} and
 * {@link #trackedPrincipals()} exist so a test can assert them instead of trusting this paragraph.</p>
 */
public final class RateLimiter implements AutoCloseable {
    /**
     * NUL separator for the composite principal key, written as an escape so it stays reviewable.
     *
     * <p>A printable separator would not be safe. With a space, {@code ("a b", "c")} and
     * {@code ("a", "b c")} both render as {@code "a b c"}: two different principals silently sharing
     * one bucket, so either can spend the other's budget. NUL cannot appear in a tenant or subject
     * claim that reached {@link ai.ravenroot.server.security.AuthenticatedPrincipal}, which makes the
     * encoding unambiguous.</p>
     */
    private static final char KEY_SEPARATOR = '\0';

    private final RateLimitConfiguration configuration;
    private final TrustedProxyConfiguration proxies;
    private final RateLimitAuditSink audit;
    private final LongSupplier nanoTime;

    private final BoundedKeyRegistry<TokenBucket> addressBuckets;
    private final BoundedKeyRegistry<TokenBucket> tenantBuckets;
    private final BoundedKeyRegistry<TokenBucket> principalBuckets;
    private final BoundedKeyRegistry<TokenBucket> submissionBuckets;
    private final BoundedKeyRegistry<ConcurrencyGate> tenantSubmissions;
    private final BoundedKeyRegistry<ConcurrencyGate> tenantStreams;
    private final BoundedKeyRegistry<ConcurrencyGate> principalStreams;
    private final ActiveExecutionRegistry activeExecutions;

    public RateLimiter(RateLimitConfiguration configuration, TrustedProxyConfiguration proxies,
                       RateLimitAuditSink audit) {
        this(configuration, proxies, audit, System::nanoTime);
    }

    public RateLimiter(RateLimitConfiguration configuration, TrustedProxyConfiguration proxies,
                       RateLimitAuditSink audit, LongSupplier nanoTime) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.proxies = Objects.requireNonNull(proxies, "proxies");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        long ttl = configuration.idleEntryTtl().toNanos();
        this.addressBuckets = new BoundedKeyRegistry<>("address-requests",
                configuration.maxTrackedClients(), ttl, nanoTime);
        this.tenantBuckets = new BoundedKeyRegistry<>("tenant-requests",
                configuration.maxTrackedTenants(), ttl, nanoTime);
        this.principalBuckets = new BoundedKeyRegistry<>("principal-requests",
                configuration.maxTrackedPrincipals(), ttl, nanoTime);
        this.submissionBuckets = new BoundedKeyRegistry<>("tenant-submissions",
                configuration.maxTrackedTenants(), ttl, nanoTime);
        this.tenantSubmissions = new BoundedKeyRegistry<>("tenant-submission-concurrency",
                configuration.maxTrackedTenants(), ttl, nanoTime);
        this.tenantStreams = new BoundedKeyRegistry<>("tenant-streams",
                configuration.maxTrackedTenants(), ttl, nanoTime);
        this.principalStreams = new BoundedKeyRegistry<>("principal-streams",
                configuration.maxTrackedPrincipals(), ttl, nanoTime);
        this.activeExecutions = new ActiveExecutionRegistry(configuration, audit, nanoTime);
    }

    /**
     * Per-tenant accounting of running executions.
     *
     * <p>It lives here rather than beside the server because it is limiter state: it shares this
     * limiter's configuration, clock and audit sink, and it is released by {@link #close()} along with
     * everything else, so it cannot outlive the server that owns it.</p>
     */
    public ActiveExecutionRegistry activeExecutions() {
        return activeExecutions;
    }

    public RateLimitConfiguration configuration() {
        return configuration;
    }

    public TrustedProxyConfiguration proxies() {
        return proxies;
    }

    public RateLimitAuditSink audit() {
        return audit;
    }

    /** Resolves the address every pre-authentication limit is keyed on. */
    public TrustedProxyConfiguration.Resolution resolveClient(java.net.InetSocketAddress peer,
                                                              Headers headers) {
        return proxies.resolve(peer, headers == null ? List.of() : TrustedProxyConfiguration.forwardedFor(headers));
    }

    /**
     * Shape limits on the request itself, checked before any work proportional to its size.
     *
     * <p>These are separate from the rate limits because they answer a different question. A rate limit
     * bounds how many requests a caller may make; these bound how expensive one request may be, which is
     * the part a caller can inflate without sending more requests.</p>
     */
    public RateLimitDecision checkRequestShape(Headers headers, String rawQuery) {
        int headerCount = 0;
        long headerBytes = 0;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            for (String value : header.getValue()) {
                headerCount++;
                int size = header.getKey().length() + (value == null ? 0 : value.length());
                if (value != null && value.length() > configuration.maxHeaderValueBytes()) {
                    return RateLimitDecision.rejected(431, "HEADER_VALUE_TOO_LARGE", "request");
                }
                headerBytes += size;
                if (headerCount > configuration.maxHeaderCount()) {
                    return RateLimitDecision.rejected(431, "TOO_MANY_HEADERS", "request");
                }
                if (headerBytes > configuration.maxHeaderBytes()) {
                    return RateLimitDecision.rejected(431, "HEADERS_TOO_LARGE", "request");
                }
            }
        }
        if (rawQuery != null) {
            if (rawQuery.length() > configuration.maxQueryBytes()) {
                return RateLimitDecision.rejected(414, "QUERY_TOO_LARGE", "request");
            }
            int parameters = rawQuery.isEmpty() ? 0 : 1;
            for (int index = 0; index < rawQuery.length(); index++) {
                if (rawQuery.charAt(index) == '&' && ++parameters > configuration.maxQueryParameters()) {
                    return RateLimitDecision.rejected(414, "TOO_MANY_QUERY_PARAMETERS", "request");
                }
            }
        }
        return RateLimitDecision.allowed();
    }

    /**
     * Pre-authentication request budget for one client address.
     *
     * <p>The bucket is keyed on {@link IpAddresses#rateLimitKey(String)} rather than on the address
     * itself, which for IPv6 is the /64 the address sits in. The caller keeps the full address: it is
     * what the audit record carries and what a trusted-peer match is made against. Only the bucket key
     * is aggregated.</p>
     */
    public RateLimitDecision checkAddress(String clientAddress) {
        return consume(addressBuckets, IpAddresses.rateLimitKey(clientAddress),
                configuration.addressRequestsPerSecond(),
                configuration.addressBurst(), "ADDRESS_RATE_LIMIT_EXCEEDED", "address");
    }

    /** Post-authentication request budget for the tenant, then for the principal inside it. */
    public RateLimitDecision checkIdentity(String tenantId, String subject) {
        RateLimitDecision tenant = consume(tenantBuckets, tenantId, configuration.tenantRequestsPerSecond(),
                configuration.tenantBurst(), "TENANT_RATE_LIMIT_EXCEEDED", "tenant");
        if (!tenant.isAllowed()) {
            return tenant;
        }
        return consume(principalBuckets, principalKey(tenantId, subject),
                configuration.principalRequestsPerSecond(), configuration.principalBurst(),
                "PRINCIPAL_RATE_LIMIT_EXCEEDED", "principal");
    }

    /** Execution-submission budget for one tenant. Tighter than the general request budget. */
    public RateLimitDecision checkSubmissionRate(String tenantId) {
        return consume(submissionBuckets, tenantId, configuration.submissionsPerSecond(),
                configuration.submissionBurst(), "SUBMISSION_RATE_LIMIT_EXCEEDED", "tenant");
    }

    /**
     * Reserves one in-flight submission slot for a tenant, or explains why it could not.
     *
     * <p>The returned {@link Lease} must be closed on every path. It is an {@link AutoCloseable} rather
     * than a pair of calls precisely so that a {@code try}-with-resources makes the release
     * unforgettable: a concurrency limit whose release can be skipped is a limit that ratchets down to
     * zero and never comes back.</p>
     */
    public Lease acquireSubmissionSlot(String tenantId) {
        return acquire(tenantSubmissions, tenantId, configuration.tenantConcurrentSubmissions(),
                "SUBMISSION_CONCURRENCY_EXCEEDED", "tenant");
    }

    /** Reserves one stream slot for the tenant and one for the principal, releasing both together. */
    public Lease acquireStreamSlot(String tenantId, String subject) {
        Lease tenant = acquire(tenantStreams, tenantId, configuration.tenantConcurrentStreams(),
                "STREAM_CONCURRENCY_EXCEEDED", "tenant");
        if (!tenant.granted()) {
            return tenant;
        }
        Lease principal = acquire(principalStreams, principalKey(tenantId, subject),
                configuration.principalConcurrentStreams(), "STREAM_CONCURRENCY_EXCEEDED", "principal");
        if (!principal.granted()) {
            // The tenant slot was taken a moment ago and must not be stranded by the second refusal.
            tenant.close();
            return principal;
        }
        return new Lease(null, () -> {
            principal.close();
            tenant.close();
        });
    }

    /**
     * Neither {@link TokenBucket} nor {@link ConcurrencyGate} is thread-safe on its own. Both are
     * mutated here under {@code synchronized (registry)}, which acquires the very monitor
     * {@link BoundedKeyRegistry}'s own methods synchronize on. One lock therefore guards both the map
     * and the state inside it, so an entry cannot be swept while a request is mutating it.
     */
    private RateLimitDecision consume(BoundedKeyRegistry<TokenBucket> registry, String key, int rate,
                                      int burst, String code, String scope) {
        synchronized (registry) {
            TokenBucket bucket = registry.obtain(key, created -> new TokenBucket(rate, burst, created));
            if (bucket == null) {
                return registryExhausted(scope);
            }
            long retryAfter = bucket.tryConsume(nanoTime.getAsLong());
            return retryAfter == 0 ? RateLimitDecision.allowed()
                    : RateLimitDecision.throttled(code, scope, retryAfter);
        }
    }

    private Lease acquire(BoundedKeyRegistry<ConcurrencyGate> registry, String key, int permits,
                          String code, String scope) {
        synchronized (registry) {
            ConcurrencyGate gate = registry.obtain(key, created -> new ConcurrencyGate(permits, created));
            if (gate == null) {
                return new Lease(registryExhausted(scope), () -> {
                });
            }
            if (!gate.tryAcquire(nanoTime.getAsLong())) {
                return new Lease(RateLimitDecision.throttled(code, scope, 1), () -> {
                });
            }
            return new Lease(null, () -> {
                synchronized (registry) {
                    gate.release(nanoTime.getAsLong());
                }
            });
        }
    }

    /**
     * The registry is full of state that is all still enforcing a limit.
     *
     * <p>Admitting the key anyway would make the ceiling advisory, so the request is refused instead.
     * The retry window is short because the condition clears as soon as any tracked key goes idle.</p>
     */
    private static RateLimitDecision registryExhausted(String scope) {
        return RateLimitDecision.throttled("LIMITER_CAPACITY_EXHAUSTED", scope, 1);
    }

    private static String principalKey(String tenantId, String subject) {
        return tenantId + KEY_SEPARATOR + subject;
    }

    /** Retained per-address entries. Bounded by {@code maxTrackedClients}. */
    public int trackedClients() {
        return addressBuckets.size();
    }

    /** Retained per-tenant entries, across all four tenant-keyed registries. */
    public int trackedTenants() {
        return Math.max(Math.max(tenantBuckets.size(), submissionBuckets.size()),
                Math.max(tenantSubmissions.size(), tenantStreams.size()));
    }

    /** Retained per-principal entries, across both principal-keyed registries. */
    public int trackedPrincipals() {
        return Math.max(principalBuckets.size(), principalStreams.size());
    }

    /**
     * Stream slots currently held by one principal.
     *
     * <p>This exists so that "the server returned the slot" is an observable fact rather than an
     * inference from a later request succeeding. A test can hold this value in a bounded await after a
     * handler returns, which is deterministic in outcome, instead of asserting immediately on a socket
     * close, which is a race.</p>
     */
    public int openPrincipalStreams(String tenantId, String subject) {
        return held(principalStreams, principalKey(tenantId, subject));
    }

    /** Stream slots currently held across one tenant. */
    public int openTenantStreams(String tenantId) {
        return held(tenantStreams, tenantId);
    }

    private static int held(BoundedKeyRegistry<ConcurrencyGate> registry, String key) {
        synchronized (registry) {
            ConcurrencyGate gate = registry.peek(key);
            return gate == null ? 0 : gate.held();
        }
    }

    /** Forces idle eviction now, so a test does not have to wait for the amortised sweep. */
    public void sweep() {
        addressBuckets.sweep();
        tenantBuckets.sweep();
        principalBuckets.sweep();
        submissionBuckets.sweep();
        tenantSubmissions.sweep();
        tenantStreams.sweep();
        principalStreams.sweep();
        activeExecutions.sweep();
    }

    /** Releases every retained entry. The limiter must not outlive the server that owns it. */
    @Override
    public void close() {
        addressBuckets.clear();
        tenantBuckets.clear();
        principalBuckets.clear();
        submissionBuckets.clear();
        tenantSubmissions.clear();
        tenantStreams.clear();
        principalStreams.clear();
        activeExecutions.close();
    }

    /**
     * A reserved concurrency slot, or the refusal that explains why there is none.
     *
     * <p>Closing a refused lease is a no-op, so callers can use one shape for both outcomes.</p>
     */
    public static final class Lease implements AutoCloseable {
        private final RateLimitDecision refusal;
        private final Runnable release;
        private boolean released;

        private Lease(RateLimitDecision refusal, Runnable release) {
            this.refusal = refusal;
            this.release = release;
        }

        public boolean granted() {
            return refusal == null;
        }

        /** The decision to send when the slot was refused; never called on a granted lease. */
        public RateLimitDecision refusal() {
            if (refusal == null) {
                throw new IllegalStateException("Lease was granted");
            }
            return refusal;
        }

        /** Idempotent: a double close must not return a permit that was already returned. */
        @Override
        public void close() {
            if (released) {
                return;
            }
            released = true;
            release.run();
        }
    }
}
