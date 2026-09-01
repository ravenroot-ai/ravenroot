package ai.ravenroot.server.ratelimit;

/**
 * Receives one record per refused request.
 *
 * <p>Rejections are always audited, at one level, with no sampling and no debug flag. A throttled
 * request is the observable half of an attack in progress — credential stuffing, scraping, a submission
 * flood — and it is also the first thing an operator looks at when a legitimate tenant reports errors.
 * Making that record conditional would mean the evidence is missing in exactly the incidents it exists
 * for. Allowed requests are not recorded: they are the normal case, and logging them would turn the
 * limiter into its own amplification vector.</p>
 *
 * <p>The sink lives in the server module rather than in {@code ravenroot-application-api} because rate
 * limiting is a property of the HTTP adapter, not of the application contract. Nothing behind the
 * adapter needs to know a request was throttled, and widening the shared API to say so would couple the
 * core to a transport concern.</p>
 */
@FunctionalInterface
public interface RateLimitAuditSink {
    void record(RateLimitAuditEvent event);

    /** Discards records; for tests and embedded uses that supply their own observability. */
    static RateLimitAuditSink discarding() {
        return event -> {
        };
    }
}
