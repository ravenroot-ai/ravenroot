package ai.ravenroot.api.catalog;

import ai.ravenroot.api.execution.RetryBackoff;
import ai.ravenroot.api.execution.RetryClassifier;
import ai.ravenroot.api.execution.RetryPolicy;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The platform-owned node properties through which a graph author declares, <strong>per node
 * instance</strong>, a bounded orchestration retry policy.
 *
 * <h2>Platform-owned, like {@code runtime.maxConcurrency} and unlike {@code recovery.repeatable}</h2>
 * <p>The two families exist for different reasons and this one belongs to the first. A recovery
 * repeatability declaration is an <em>author assertion of domain knowledge</em> — "this POST is safe
 * to repeat" — so a behavior package may legitimately declare it and shape its own condition. A retry
 * bound is a rule the <em>orchestrator</em> enforces: it decides how many durable attempts one
 * invocation may produce. If a behavior package could declare a property with these names, it would
 * hold a second authority over the same key — its own {@link NodePropertyDescriptor#type()},
 * {@link NodePropertyDescriptor#allowedValues()} and {@link NodePropertyDescriptor#defaultValue()}
 * against the platform's — and whichever was consulted second would silently decide how many times
 * an effect may happen. {@link #validateShape(NodeTypeDescriptor)} refuses that at catalog load, on
 * the one path every registration takes.</p>
 *
 * <p>The <em>values</em> remain the author's. Nothing here decides for them; it decides only that
 * the meaning of the key is fixed.</p>
 *
 * <h2>Fail-closed on every branch</h2>
 * <p>The default policy is {@link RetryPolicy#NONE}: one attempt, no wait, nothing classified
 * retryable — behaviourally identical to this runtime before orchestration retries existed. A node
 * that declares nothing therefore cannot observe that the machinery is present. A node that declares
 * {@link #MAX_ATTEMPTS} and nothing else gets the default backoff and still retries nothing, because
 * the classifier is fail-closed until {@link #RETRY_ON} names something. That is deliberate rather
 * than an inconvenience: a bound with no classification would otherwise mean "repeat any failure",
 * which is the fail-open reading of a property whose name says nothing about safety.</p>
 *
 * <h2>Malformed values are refused loudly, at graph admission</h2>
 * <p>Unlike {@link RecoveryRepeatabilityProperty#parse(Object)}, which degrades an unrecognised token
 * to {@code UNDECLARED}, a malformed number or duration here throws. The two are not alike: there,
 * degrading lands on the safe answer and the author's intent was one of two tokens; here an author
 * who wrote {@code retry.maxAttempts=tree} intended <em>some</em> bound, and silently giving them one
 * attempt would present a policy that is not running as a policy that is. {@link #RETRY_ON} is the
 * one exception and states its own reason.</p>
 */
public final class NodeRetryProperty {

    /** Platform-owned GraphML property naming the total number of attempts allowed. */
    public static final String MAX_ATTEMPTS = "retry.maxAttempts";

    /** Platform-owned GraphML property naming the wait before the first retry. */
    public static final String INITIAL_BACKOFF = "retry.initialBackoff";

    /** Platform-owned GraphML property naming the growth factor applied to each further wait. */
    public static final String BACKOFF_MULTIPLIER = "retry.backoffMultiplier";

    /** Platform-owned GraphML property naming the ceiling on any single wait. */
    public static final String MAX_BACKOFF = "retry.maxBackoff";

    /**
     * Platform-owned GraphML property naming the throwable types this node declares retryable.
     *
     * <p>A comma-separated list of fully qualified or simple class names. See
     * {@link RetryClassifier#declaredRetryable(Set)} for the matching rule and for why an
     * unrecognisable name is not an error.</p>
     */
    public static final String RETRY_ON = "retry.retryOn";

    /** Every name this family owns, so a collision check needs no second list to keep in step. */
    public static final List<String> NAMES =
            List.of(MAX_ATTEMPTS, INITIAL_BACKOFF, BACKOFF_MULTIPLIER, MAX_BACKOFF, RETRY_ON);

    /**
     * The ceiling on {@link #MAX_ATTEMPTS}.
     *
     * <p>A bound on the bound, because the value multiplies external effects and a mistyped
     * {@code 1000} would be indistinguishable from a considered choice until the effects had already
     * happened. The number is generous relative to any real policy and exists to catch a slip, not to
     * express a recommendation.</p>
     */
    public static final int MAX_DECLARABLE_ATTEMPTS = 100;

    private NodeRetryProperty() {
    }

    /**
     * Reads one node instance's declared policy.
     *
     * @param instanceValues authored property values for the node instance, possibly {@code null}
     * @return the declared policy, or {@link RetryPolicy#NONE} when nothing usable was declared
     * @throws IllegalArgumentException when a declared value is present but malformed, or when the
     *                                  declared shape is internally inconsistent — a ceiling below
     *                                  the initial wait, a shrinking multiplier, a non-positive
     *                                  attempt budget
     */
    public static RetryPolicy read(Map<String, Object> instanceValues) {
        if (instanceValues == null || NAMES.stream().noneMatch(instanceValues::containsKey)) {
            return RetryPolicy.NONE;
        }
        int maxAttempts = instanceValues.containsKey(MAX_ATTEMPTS)
                ? parseAttempts(instanceValues.get(MAX_ATTEMPTS)) : 1;
        Duration initialDelay = instanceValues.containsKey(INITIAL_BACKOFF)
                ? parseDuration(INITIAL_BACKOFF, instanceValues.get(INITIAL_BACKOFF))
                : RetryBackoff.DEFAULT_INITIAL_DELAY;
        Duration maxDelay = instanceValues.containsKey(MAX_BACKOFF)
                ? parseDuration(MAX_BACKOFF, instanceValues.get(MAX_BACKOFF))
                : RetryBackoff.DEFAULT_MAX_DELAY;
        double multiplier = instanceValues.containsKey(BACKOFF_MULTIPLIER)
                ? parseMultiplier(instanceValues.get(BACKOFF_MULTIPLIER))
                : RetryBackoff.DEFAULT_MULTIPLIER;
        Set<String> retryOn = parseTypeNames(instanceValues.get(RETRY_ON));
        // A declared initial wait above the default ceiling is a coherent intent expressed with one
        // property: raise the ceiling to meet it rather than refusing, which is what RetryBackoff's
        // own constructor would otherwise do to an author who never mentioned maxBackoff.
        if (!instanceValues.containsKey(MAX_BACKOFF) && maxDelay.compareTo(initialDelay) < 0) {
            maxDelay = initialDelay;
        }
        return new RetryPolicy(maxAttempts, new RetryBackoff(initialDelay, multiplier, maxDelay),
                RetryClassifier.declaredRetryable(retryOn));
    }

    /**
     * Whether any of this family's names appears in the authored values.
     *
     * @param values authored property values to inspect, possibly {@code null}
     * @return whether at least one platform retry property is explicitly present
     */
    public static boolean declaredBy(Map<String, Object> values) {
        return values != null && NAMES.stream().anyMatch(values::containsKey);
    }

    /**
     * Refuses a node package that attempts to redefine any platform-owned retry property.
     *
     * @param descriptor trusted catalog descriptor governing the node type
     * @throws IllegalArgumentException when the descriptor declares one of {@link #NAMES}
     */
    public static void validateShape(NodeTypeDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        descriptor.properties().stream()
                .map(NodePropertyDescriptor::name)
                .filter(NAMES::contains)
                .findFirst()
                .ifPresent(collision -> {
                    throw new IllegalArgumentException("Behavior '" + descriptor.behavior()
                            + "' property '" + collision + "' is platform-owned and cannot be declared "
                            + "as a behavior property");
                });
    }

    /**
     * Strict decimal parsing of the attempt budget, bounded at both ends.
     *
     * @param raw candidate authored value
     * @return the parsed budget, between one and {@link #MAX_DECLARABLE_ATTEMPTS}
     */
    public static int parseAttempts(Object raw) {
        if (raw instanceof Integer integer) {
            return requireInRange(integer);
        }
        if (raw instanceof Long value && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
            return requireInRange(value.intValue());
        }
        String text = raw == null ? "" : raw.toString().strip();
        if (!text.matches("[0-9]+")) {
            throw new IllegalArgumentException("invalid " + MAX_ATTEMPTS + ": '" + text + "'");
        }
        try {
            return requireInRange(Integer.parseInt(text));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid " + MAX_ATTEMPTS + ": '" + text + "'", invalid);
        }
    }

    /**
     * Parses a wait, accepting either an ISO-8601 duration or a plain count of milliseconds.
     *
     * <p>Both spellings are accepted because both are what authors write, and rejecting one costs a
     * support conversation for no safety gained. A bare number is milliseconds and never seconds: it
     * is the unit every other time value on this surface uses, and guessing seconds for small numbers
     * would make {@code 30} mean half a minute and {@code 300} mean a third of a second.</p>
     *
     * @param name the property being parsed, for the rejection message
     * @param raw  candidate authored value
     * @return the parsed non-negative duration
     */
    public static Duration parseDuration(String name, Object raw) {
        if (raw instanceof Duration duration) {
            return requireNonNegative(name, duration);
        }
        if (raw instanceof Number number) {
            return requireNonNegative(name, millisToDuration(name, number));
        }
        String text = raw == null ? "" : raw.toString().strip();
        if (text.matches("[0-9]+")) {
            try {
                return requireNonNegative(name, Duration.ofMillis(Long.parseLong(text)));
            } catch (NumberFormatException tooLarge) {
                throw new IllegalArgumentException("invalid " + name + ": '" + text + "'", tooLarge);
            }
        }
        try {
            return requireNonNegative(name, Duration.parse(text));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("invalid " + name + ": '" + text
                    + "'; expected ISO-8601 such as PT0.5S, or a count of milliseconds", invalid);
        }
    }

    /**
     * Strict parsing of the growth factor.
     *
     * @param raw candidate authored value
     * @return the parsed multiplier; {@link RetryBackoff} refuses values below one
     */
    public static double parseMultiplier(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        String text = raw == null ? "" : raw.toString().strip();
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid " + BACKOFF_MULTIPLIER + ": '" + text + "'",
                    invalid);
        }
    }

    /**
     * Splits the declared type-name list.
     *
     * <p>Deliberately tolerant, and the one property in this family that is: the names refer to
     * classes this deployment may not have loaded — a connector's exception type lives in a plugin
     * another deployment installs — so there is nothing here to validate against. Blank entries are
     * dropped and order is preserved for readability of any message that echoes the set back.</p>
     *
     * @param raw candidate authored value, possibly {@code null}
     * @return the declared names, possibly empty and never {@code null}
     */
    public static Set<String> parseTypeNames(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        var names = new LinkedHashSet<String>();
        for (String candidate : raw.toString().split(",")) {
            String name = candidate.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Converts a numeric millisecond count to a duration without losing a fractional part.
     *
     * <p>{@code longValue()} would truncate, and the truncation is silent and lands on the one value
     * that means "no wait at all": a declared {@code 0.5} became {@code PT0S}, so a policy an author
     * wrote to pause half a millisecond between attempts retried as fast as the machine could go.
     * Nanosecond precision is used instead, which is the resolution {@link Duration} carries anyway
     * and is finer than any wait this property is used to express.</p>
     *
     * <p>An integral value takes the exact path, so nothing that worked before goes through the
     * floating-point one — {@code Long.MAX_VALUE} milliseconds still converts exactly, where a
     * {@code double} would already have lost precision. A non-finite value is refused rather than
     * rounded, because {@link Math#round(double)} maps NaN to zero, which is the same silent
     * "no wait" this method exists to stop producing.</p>
     */
    private static Duration millisToDuration(String name, Number number) {
        if (number instanceof Integer || number instanceof Long || number instanceof Short
                || number instanceof Byte || number instanceof java.math.BigInteger) {
            return Duration.ofMillis(number.longValue());
        }
        double millis = number.doubleValue();
        if (!Double.isFinite(millis)) {
            throw new IllegalArgumentException("invalid " + name + ": '" + number + "'");
        }
        double nanos = millis * 1_000_000.0;
        if (Math.abs(nanos) > (double) Long.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is out of range: '" + number + "'");
        }
        return Duration.ofNanos(Math.round(nanos));
    }

    private static int requireInRange(int attempts) {
        if (attempts < 1 || attempts > MAX_DECLARABLE_ATTEMPTS) {
            throw new IllegalArgumentException(MAX_ATTEMPTS + " must be between 1 and "
                    + MAX_DECLARABLE_ATTEMPTS + ": " + attempts);
        }
        return attempts;
    }

    private static Duration requireNonNegative(String name, Duration value) {
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative: " + value);
        }
        return value;
    }
}
