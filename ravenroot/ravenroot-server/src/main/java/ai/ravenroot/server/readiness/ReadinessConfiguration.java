package ai.ravenroot.server.readiness;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Every knob {@link ReadinessGate} and the {@code GracefulShutdown} sequence need from deployment
 * configuration. Same
 * {@code fromEnvironment(Map)} idiom as {@code AuthenticationConfiguration}, {@code RateLimitConfiguration},
 * {@code TelemetryConfiguration} and {@code NodePackageLoader} -- a bare environment-variable read,
 * without deciding where platform configuration lives.
 *
 * <p>{@code storeCheckTimeout} has two siblings rather than a compatibility constructor beside it.
 * Unlike {@code ExecutionEvent} in PLAT-01, this record has exactly one external construction site
 * outside this class ({@code ReadinessGateTest}); a compatibility shim here would protect a caller
 * that does not exist.</p>
 */
public record ReadinessConfiguration(Duration storeCheckTimeout, Duration drainGracePeriod, Duration httpStopDelay) {

    /** Default store-check timeout when {@value #TIMEOUT_VARIABLE} is unset: generous enough for
     * a slow disk, short enough that a hung store cannot stall a probe past a Kubernetes probe's
     * own default 1s timeout by more than this bound. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(500);

    /**
     * Default pause between {@code engine.drain()} (readiness immediately starts reporting
     * {@code DRAINING}) and actually stopping the HTTP listener: one full readiness-probe period
     * with margin. Both shipped manifests (Helm and raw Kubernetes) poll {@code /ready} every 5s
     * ({@code deploy/helm/ravenroot/values.yaml}'s {@code probes.readiness.periodSeconds},
     * {@code deploy/kubernetes/ravenroot.yaml}'s {@code readinessProbe.periodSeconds}); 6s
     * guarantees at least one poll lands inside the window before the listener stops accepting,
     * without the two manifests needing to agree with this constant for the sequencing to be
     * correct -- a shorter probe period only makes the margin larger.
     */
    private static final Duration DEFAULT_DRAIN_GRACE_PERIOD = Duration.ofSeconds(6);

    /**
     * Default bound passed to {@code HttpServer.stop(int)}: how long already-in-flight HTTP
     * exchanges are given to finish once the listener stops accepting new ones. Matches {@code
     * GraphRunner.DEFAULT_SHUTDOWN_BOUND} (also 10s) -- the existing precedent in this codebase for
     * "how long a graceful shutdown waits" -- rather than picking an unrelated number.
     */
    private static final Duration DEFAULT_HTTP_STOP_DELAY = Duration.ofSeconds(10);

    public static final String TIMEOUT_VARIABLE = "RAVENROOT_READY_STORE_CHECK_TIMEOUT_MS";
    public static final String DRAIN_GRACE_PERIOD_VARIABLE = "RAVENROOT_READY_DRAIN_GRACE_MS";
    public static final String HTTP_STOP_DELAY_VARIABLE = "RAVENROOT_SERVER_STOP_DELAY_SECONDS";

    public ReadinessConfiguration {
        Objects.requireNonNull(storeCheckTimeout, "storeCheckTimeout");
        Objects.requireNonNull(drainGracePeriod, "drainGracePeriod");
        Objects.requireNonNull(httpStopDelay, "httpStopDelay");
        // Net for a caller that builds this record directly instead of through
        // fromEnvironment/millisOrDefault/secondsOrDefault -- those methods are the primary guards,
        // naming the environment variable and the raw value the operator typed; this constructor does
        // not know a variable name, so its message is weaker (field name, Duration#toString()) on
        // purpose, exactly like the upper-bound net below.
        requirePositive(storeCheckTimeout, "storeCheckTimeout");
        if (drainGracePeriod.isNegative()) {
            throw new IllegalArgumentException("drainGracePeriod cannot be negative, got " + drainGracePeriod);
        }
        requirePositive(httpStopDelay, "httpStopDelay");
        // Net for a caller that builds this record directly instead of through
        // fromEnvironment/secondsOrDefault -- see that method's comment for the full reasoning on why
        // httpStopDelay alone needs this bound. Same threshold, so a value that reached here already
        // wrapped to a negative int somewhere else if this guard did not exist; the message is weaker
        // here (no environment-variable name to report -- this constructor does not know one), which is
        // exactly why secondsOrDefault is the primary guard and this one only a backstop.
        if (httpStopDelay.toSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "httpStopDelay must be no more than Integer.MAX_VALUE seconds (HttpServer.stop(int) "
                            + "takes an int), got " + httpStopDelay);
        }
    }

    public static ReadinessConfiguration defaults() {
        return new ReadinessConfiguration(DEFAULT_TIMEOUT, DEFAULT_DRAIN_GRACE_PERIOD, DEFAULT_HTTP_STOP_DELAY);
    }

    public static ReadinessConfiguration fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        // StoreCheckTimeout must be strictly positive (degenerates the readiness check to
        // never running otherwise) and drainGracePeriod may be zero (skip the grace window) but not
        // negative -- the same two constraints the constructor below already enforced as a net, now
        // measured and applied at the read, where the variable name and the raw typed value are still
        // in scope. See millisOrDefault's comment for why both variables share one method with a flag
        // instead of two near-identical ones.
        Duration storeCheckTimeout = millisOrDefault(environment, TIMEOUT_VARIABLE, DEFAULT_TIMEOUT, true);
        Duration drainGracePeriod = millisOrDefault(environment, DRAIN_GRACE_PERIOD_VARIABLE, DEFAULT_DRAIN_GRACE_PERIOD,
                false);
        Duration httpStopDelay = secondsOrDefault(environment, HTTP_STOP_DELAY_VARIABLE, DEFAULT_HTTP_STOP_DELAY);
        return new ReadinessConfiguration(storeCheckTimeout, drainGracePeriod, httpStopDelay);
    }

    /**
     * @param requirePositive {@code true} rejects zero (storeCheckTimeout: a zero timeout degenerates
     *     the check into never running); {@code false} allows zero but not negative (drainGracePeriod:
     *     zero is a legitimate "skip the grace window" choice). One method with a flag rather than two
     *     near-duplicates, since everything else -- parsing, the not-a-number message, the fallback --
     *     is identical between the two callers.
     */
    private static Duration millisOrDefault(Map<String, String> environment, String variable, Duration fallback,
            boolean requirePositive) {
        String raw = environment.get(variable);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        long millis;
        try {
            millis = Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(variable + "='" + raw + "' is not a number of milliseconds",
                    notANumber);
        }
        // These checks run while the variable name and raw value are still available. Constructor
        // checks see only the field name and a Duration#toString() render of the parsed value. This
        // class's standard (aNonNumericValueNamesTheOffendingVariableInTheFailure) is that a rejection
        // names the variable and the value the operator typed, as the "not a number" check above does.
        if (requirePositive && millis <= 0) {
            throw new IllegalArgumentException(variable + "='" + raw + "' must be positive, got " + millis);
        }
        if (!requirePositive && millis < 0) {
            throw new IllegalArgumentException(variable + "='" + raw + "' cannot be negative, got " + millis);
        }
        return Duration.ofMillis(millis);
    }

    private static Duration secondsOrDefault(Map<String, String> environment, String variable, Duration fallback) {
        String raw = environment.get(variable);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        long seconds;
        try {
            seconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(variable + "='" + raw + "' is not a number of seconds",
                    notANumber);
        }
        // This check runs here for the same reason as the checks in millisOrDefault above:
        // httpStopDelay must be strictly positive (a zero or negative stop
        // delay is not a bound HttpServer.stop(int) can act on), and the constructor only had the
        // field name and a Duration#toString() render to report it with. Checked before the
        // upper-bound guard below since a negative value can never trip that one anyway, and sign is
        // the more fundamental defect of the two.
        if (seconds <= 0) {
            throw new IllegalArgumentException(variable + "='" + raw + "' must be positive, got " + seconds);
        }
        // This method's only caller is httpStopDelay, the one field this record hands to a
        // single-int-parameter API: RavenrootServer#close() calls
        // server.stop((int) httpStopDelay.toSeconds()). Above Integer.MAX_VALUE seconds (~68 years) that
        // cast wraps to a negative int and stop() throws -- not here, but at the next graceful shutdown,
        // hours or days after whoever set the variable is gone, which is the most expensive moment to
        // diagnose a mistake made at deploy time. Rejected here, at the read, rather than clamped to
        // Integer.MAX_VALUE: no real deployment needs a shutdown grace window measured in decades, so a
        // value this large is a mistake (wrong units, extra digits), not a considered request for "as
        // generous as possible". There is no JDK default this class can silently defer to for
        // RAVENROOT_SERVER_STOP_DELAY_SECONDS: this
        // class owns the meaning of the value, so it owns catching the mistake, naming both the variable
        // and the offending value the same way the "not a number" guard above already does.
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(variable + "='" + raw + "' is more than the maximum "
                    + Integer.MAX_VALUE + " seconds (HttpServer.stop takes an int)");
        }
        return Duration.ofSeconds(seconds);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, got " + duration);
        }
    }
}
