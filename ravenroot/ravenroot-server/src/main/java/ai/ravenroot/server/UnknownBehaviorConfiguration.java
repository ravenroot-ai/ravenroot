package ai.ravenroot.server;

import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;

import java.util.Map;

/**
 * Selects what happens when a graph names a behavior this deployment's catalog does not contain
 * (SEC-09).
 *
 * <h2>Why this lives here and not in core</h2>
 * <p>{@link UnknownBehaviorPolicy}'s own Javadoc refused to let the seam be "finished" inside core,
 * because core has no configuration channel and inventing one there would decide, as a side effect
 * of a security fix, where all platform-wide configuration lives. So the reading happens at a
 * composition root — the same place {@code ExecutionStoreConfiguration} and
 * {@code DeploymentCapConfiguration} read theirs — and the chosen policy travels inward as an
 * ordinary constructor parameter.</p>
 *
 * <h2>The default is pass-through, deliberately</h2>
 * <p>An unset, blank or unrecognised value selects pass-through. That is a
 * judgement, not an omission. Pass-through is a documented product capability: a partially built
 * graph stays submittable, openable and inspectable, which is what makes the editor usable while a
 * graph is still being assembled. A default that refused every not-yet-built node would be hostile
 * to the person most likely to be running the product, and reversing it is an ADR-level decision
 * about what Ravenroot promises rather than a switch someone flips.</p>
 *
 * <p>What made pass-through dangerous was never the pass-through — it was the run reporting plain
 * success afterwards with nothing saying it had been degraded. The execution result now carries
 * {@code defaultedNodes} and {@code degraded}. The refusal mode exists for the other
 * case, someone testing a graph they expect to actually work, where stopping and naming the missing
 * node beats completing and quietly doing nothing.</p>
 *
 * <h2>Unrecognised values do not fail startup</h2>
 * <p>An unrecognised value selects the default rather than refusing to boot, matching
 * {@code ExecutionStoreConfiguration}'s existing treatment of its own flag. The alternative —
 * refusing to start on a typo — turns a misconfigured word into an outage, and this switch's
 * safe direction is the one that keeps a deployment running the way it ran yesterday.</p>
 */
public record UnknownBehaviorConfiguration(boolean refuse) {

    /**
     * Re-exported from {@link UnknownBehaviorPolicy}, never redeclared.
     *
     * <p>The CLI's embedded composition root reads the same variable and cannot see this class -- the
     * shipped CLI deliberately does not depend on {@code ravenroot-server}. So the name, the accepted
     * values and the parsing rule live once, in core, beside the vocabulary they select; this record
     * is the server's local shape over that one parser, not a second copy of it.</p>
     */
    public static final String VARIABLE = UnknownBehaviorPolicy.ENVIRONMENT_VARIABLE;

    public static final String REFUSE_VALUE = UnknownBehaviorPolicy.REFUSE_VALUE;

    public static final String PASS_THROUGH_VALUE = UnknownBehaviorPolicy.PASS_THROUGH_VALUE;

    public static UnknownBehaviorConfiguration fromEnvironment(Map<String, String> environment) {
        return new UnknownBehaviorConfiguration(UnknownBehaviorPolicy.refusalSelected(environment));
    }

    /** The default: every unknown behavior executes as an observable pass-through. */
    public static UnknownBehaviorConfiguration passThrough() {
        return new UnknownBehaviorConfiguration(false);
    }

    /** Fail-closed: a node naming an absent behavior refuses when a traversal reaches it. */
    public static UnknownBehaviorConfiguration refusing() {
        return new UnknownBehaviorConfiguration(true);
    }

    public UnknownBehaviorPolicy policy() {
        return refuse ? UnknownBehaviorPolicy.refuse() : UnknownBehaviorPolicy.passThrough();
    }

    /** What {@code /v1/status} and the startup banner say this deployment does. */
    public String describe() {
        return refuse ? REFUSE_VALUE : PASS_THROUGH_VALUE;
    }
}
