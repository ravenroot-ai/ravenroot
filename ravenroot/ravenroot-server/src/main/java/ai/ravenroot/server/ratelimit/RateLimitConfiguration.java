package ai.ravenroot.server.ratelimit;

import java.time.Duration;
import java.util.Map;

/**
 * Every limit the HTTP adapter enforces, with defaults that are always on.
 *
 * <h2>Why the defaults are limits and not "unlimited"</h2>
 *
 * <p>A limit that must be configured before it applies is absent in every deployment that has not read
 * the documentation, which is most of them and all of the early ones. So each value below has a
 * conservative default and there is <strong>no way to express "no limit"</strong>: validation rejects
 * zero and negative values, so the only way to weaken a limit is to state a specific larger number, in
 * writing, on purpose.</p>
 *
 * <p>Trust runs the other way. {@link TrustedProxyConfiguration} defaults to trusting nothing, because
 * a wrong default there does not throttle a caller, it lets one impersonate another. Limits default on;
 * trust defaults off.</p>
 *
 * <h2>Choosing the numbers</h2>
 *
 * <p>The address budget is the loosest, because one browser page load is a burst of dozens of asset
 * requests from a single address and a limiter that breaks the UI will be turned off. The per-tenant
 * budget sits above the per-principal one so a tenant's users share a pool without any single user
 * being able to drain it. Submissions are the tightest by an order of magnitude: a submission starts a
 * graph execution, so it costs far more than the request that carried it, and it is the one endpoint
 * where a modest request rate is an immodest amount of work.</p>
 *
 * @param addressRequestsPerSecond      sustained request rate for one client address, pre-authentication
 * @param addressBurst                  burst allowance for one client address
 * @param tenantRequestsPerSecond       sustained request rate for one tenant
 * @param tenantBurst                   burst allowance for one tenant
 * @param principalRequestsPerSecond    sustained request rate for one principal within a tenant
 * @param principalBurst                burst allowance for one principal
 * @param submissionsPerSecond          sustained execution-submission rate for one tenant
 * @param submissionBurst               burst allowance for submissions by one tenant
 * @param tenantConcurrentSubmissions   simultaneous in-flight submissions for one tenant
 * @param globalActiveExecutions        ceiling on active executions across the process
 * @param tenantConcurrentStreams       simultaneous event streams for one tenant
 * @param principalConcurrentStreams    simultaneous event streams for one principal
 * @param streamQueueCapacity           buffered events per stream before a slow consumer is dropped
 * @param maxQueryBytes                 longest accepted raw query string
 * @param maxQueryParameters            most accepted query parameters
 * @param maxHeaderCount                most accepted request headers
 * @param maxHeaderBytes                largest accepted total request-header size
 * @param maxHeaderValueBytes           largest accepted single header value
 * @param maxTrackedClients             ceiling on retained per-address state
 * @param maxTrackedTenants             ceiling on retained per-tenant state
 * @param maxTrackedPrincipals          ceiling on retained per-principal state
 * @param idleEntryTtl                  how long unused limiter state is retained before eviction
 * @param executionMaxAge               how long one active execution may be counted before its entry is
 *                                      reclaimed, so an execution that never publishes a terminal event
 *                                      cannot hold admission capacity for the life of the process
 */
public record RateLimitConfiguration(
        int addressRequestsPerSecond, int addressBurst,
        int tenantRequestsPerSecond, int tenantBurst,
        int principalRequestsPerSecond, int principalBurst,
        int submissionsPerSecond, int submissionBurst,
        int tenantConcurrentSubmissions, int globalActiveExecutions,
        int tenantConcurrentStreams, int principalConcurrentStreams,
        int streamQueueCapacity,
        int maxQueryBytes, int maxQueryParameters,
        int maxHeaderCount, int maxHeaderBytes, int maxHeaderValueBytes,
        int maxTrackedClients, int maxTrackedTenants, int maxTrackedPrincipals,
        Duration idleEntryTtl, Duration executionMaxAge) {

    /** Ceiling on any burst, so a bucket's capacity arithmetic cannot be made to overflow. */
    private static final int MAX_BURST = 1_000_000;

    /**
     * Fraction of the global execution ceiling that is never available to any single tenant.
     *
     * <p>One eighth is deliberately small. The per-tenant execution cap exists to stop one tenant
     * answering 429 to every other tenant, not to re-partition the engine: a single-tenant deployment
     * is the common case, and a cap of a half or a quarter would silently cut its concurrency by that
     * factor. At one eighth a lone tenant still reaches 56 of the default 64, while every other tenant
     * always has slots that the first one is structurally unable to take.</p>
     */
    private static final int RESERVED_HEADROOM_DIVISOR = 8;

    /** Floor on retained execution entries, so a small ceiling still leaves room to observe overrun. */
    private static final int MIN_TRACKED_EXECUTIONS = 256;

    public static final RateLimitConfiguration DEFAULTS = new RateLimitConfiguration(
            20, 120,
            50, 200,
            20, 80,
            2, 10,
            4, 64,
            16, 4,
            256,
            4096, 64,
            64, 16 * 1024, 8 * 1024,
            10_000, 1_000, 10_000,
            Duration.ofSeconds(60), Duration.ofHours(1));

    public RateLimitConfiguration {
        positive("RAVENROOT_RATELIMIT_ADDRESS_RPS", addressRequestsPerSecond);
        burst("RAVENROOT_RATELIMIT_ADDRESS_BURST", addressBurst, addressRequestsPerSecond);
        positive("RAVENROOT_RATELIMIT_TENANT_RPS", tenantRequestsPerSecond);
        burst("RAVENROOT_RATELIMIT_TENANT_BURST", tenantBurst, tenantRequestsPerSecond);
        positive("RAVENROOT_RATELIMIT_PRINCIPAL_RPS", principalRequestsPerSecond);
        burst("RAVENROOT_RATELIMIT_PRINCIPAL_BURST", principalBurst, principalRequestsPerSecond);
        positive("RAVENROOT_RATELIMIT_SUBMISSION_RPS", submissionsPerSecond);
        burst("RAVENROOT_RATELIMIT_SUBMISSION_BURST", submissionBurst, submissionsPerSecond);
        positive("RAVENROOT_RATELIMIT_TENANT_CONCURRENT_SUBMISSIONS", tenantConcurrentSubmissions);
        positive("RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS", globalActiveExecutions);
        positive("RAVENROOT_RATELIMIT_TENANT_STREAMS", tenantConcurrentStreams);
        positive("RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS", principalConcurrentStreams);
        positive("RAVENROOT_SSE_QUEUE_CAPACITY", streamQueueCapacity);
        positive("RAVENROOT_RATELIMIT_MAX_QUERY_BYTES", maxQueryBytes);
        positive("RAVENROOT_RATELIMIT_MAX_QUERY_PARAMETERS", maxQueryParameters);
        positive("RAVENROOT_RATELIMIT_MAX_HEADER_COUNT", maxHeaderCount);
        positive("RAVENROOT_RATELIMIT_MAX_HEADER_BYTES", maxHeaderBytes);
        positive("RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES", maxHeaderValueBytes);
        positive("RAVENROOT_RATELIMIT_MAX_TRACKED_CLIENTS", maxTrackedClients);
        positive("RAVENROOT_RATELIMIT_MAX_TRACKED_TENANTS", maxTrackedTenants);
        positive("RAVENROOT_RATELIMIT_MAX_TRACKED_PRINCIPALS", maxTrackedPrincipals);
        if (principalConcurrentStreams > tenantConcurrentStreams) {
            throw new IllegalArgumentException(
                    "RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS must not exceed RAVENROOT_RATELIMIT_TENANT_STREAMS");
        }
        if (maxHeaderValueBytes > maxHeaderBytes) {
            throw new IllegalArgumentException(
                    "RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES must not exceed the total header budget");
        }
        if (idleEntryTtl == null || idleEntryTtl.compareTo(Duration.ofSeconds(1)) < 0
                || idleEntryTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS must be between 1 and 3600");
        }
        if (executionMaxAge == null || executionMaxAge.compareTo(Duration.ofSeconds(1)) < 0
                || executionMaxAge.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(
                    "RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS must be between 1 and 86400");
        }
    }

    /**
     * Slots of the global ceiling that no single tenant can occupy.
     *
     * <p>Derived rather than configured on purpose. An operator who raises the global ceiling must not
     * have to remember a second variable to keep the guarantee, and there must be no value that makes
     * the guarantee disappear — which an independent per-tenant setting would allow the moment somebody
     * set it to the ceiling. It is at least one slot for every possible ceiling.</p>
     */
    public int reservedExecutionHeadroom() {
        return Math.max(1, globalActiveExecutions / RESERVED_HEADROOM_DIVISOR);
    }

    /**
     * Active executions one tenant may hold.
     *
     * <p>Structurally below {@link #globalActiveExecutions()} whenever the ceiling is more than one, so
     * a tenant holding executions can never be the reason another tenant is refused. With a ceiling of
     * exactly one the two coincide and the guarantee cannot hold; that is a property of a ceiling of
     * one, not a configuration mistake, and it is documented rather than silently corrected.</p>
     */
    public int tenantActiveExecutions() {
        return Math.max(1, globalActiveExecutions - reservedExecutionHeadroom());
    }

    /**
     * Ceiling on retained active-execution entries.
     *
     * <p>Admission never lets this registry exceed {@link #globalActiveExecutions()}, but executions
     * started by an in-process CLI or embedded caller publish the same events without passing admission,
     * so the map needs a bound of its own. Four times the ceiling with a floor of
     * {@value #MIN_TRACKED_EXECUTIONS} leaves room for that traffic and still cannot grow without
     * limit.</p>
     */
    public int maxTrackedExecutions() {
        return Math.max(MIN_TRACKED_EXECUTIONS, Math.min(Integer.MAX_VALUE / 4, globalActiveExecutions) * 4);
    }

    public static RateLimitConfiguration fromEnvironment(Map<String, String> environment) {
        return new RateLimitConfiguration(
                integer(environment, "RAVENROOT_RATELIMIT_ADDRESS_RPS", DEFAULTS.addressRequestsPerSecond),
                integer(environment, "RAVENROOT_RATELIMIT_ADDRESS_BURST", DEFAULTS.addressBurst),
                integer(environment, "RAVENROOT_RATELIMIT_TENANT_RPS", DEFAULTS.tenantRequestsPerSecond),
                integer(environment, "RAVENROOT_RATELIMIT_TENANT_BURST", DEFAULTS.tenantBurst),
                integer(environment, "RAVENROOT_RATELIMIT_PRINCIPAL_RPS", DEFAULTS.principalRequestsPerSecond),
                integer(environment, "RAVENROOT_RATELIMIT_PRINCIPAL_BURST", DEFAULTS.principalBurst),
                integer(environment, "RAVENROOT_RATELIMIT_SUBMISSION_RPS", DEFAULTS.submissionsPerSecond),
                integer(environment, "RAVENROOT_RATELIMIT_SUBMISSION_BURST", DEFAULTS.submissionBurst),
                integer(environment, "RAVENROOT_RATELIMIT_TENANT_CONCURRENT_SUBMISSIONS",
                        DEFAULTS.tenantConcurrentSubmissions),
                integer(environment, "RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS",
                        DEFAULTS.globalActiveExecutions),
                integer(environment, "RAVENROOT_RATELIMIT_TENANT_STREAMS", DEFAULTS.tenantConcurrentStreams),
                integer(environment, "RAVENROOT_RATELIMIT_PRINCIPAL_STREAMS", DEFAULTS.principalConcurrentStreams),
                integer(environment, "RAVENROOT_SSE_QUEUE_CAPACITY", DEFAULTS.streamQueueCapacity),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_QUERY_BYTES", DEFAULTS.maxQueryBytes),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_QUERY_PARAMETERS", DEFAULTS.maxQueryParameters),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_HEADER_COUNT", DEFAULTS.maxHeaderCount),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_HEADER_BYTES", DEFAULTS.maxHeaderBytes),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_HEADER_VALUE_BYTES", DEFAULTS.maxHeaderValueBytes),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_TRACKED_CLIENTS", DEFAULTS.maxTrackedClients),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_TRACKED_TENANTS", DEFAULTS.maxTrackedTenants),
                integer(environment, "RAVENROOT_RATELIMIT_MAX_TRACKED_PRINCIPALS", DEFAULTS.maxTrackedPrincipals),
                Duration.ofSeconds(integer(environment, "RAVENROOT_RATELIMIT_IDLE_TTL_SECONDS",
                        (int) DEFAULTS.idleEntryTtl.toSeconds())),
                Duration.ofSeconds(integer(environment, "RAVENROOT_RATELIMIT_EXECUTION_MAX_AGE_SECONDS",
                        (int) DEFAULTS.executionMaxAge.toSeconds())));
    }

    private static void positive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive; limits cannot be disabled");
        }
    }

    private static void burst(String name, int value, int rate) {
        positive(name, value);
        if (value > MAX_BURST) {
            throw new IllegalArgumentException(name + " must not exceed " + MAX_BURST);
        }
        if (value < rate) {
            throw new IllegalArgumentException(name + " must be at least the sustained rate");
        }
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }
}
