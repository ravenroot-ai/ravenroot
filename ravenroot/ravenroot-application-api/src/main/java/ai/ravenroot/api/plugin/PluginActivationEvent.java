package ai.ravenroot.api.plugin;

import java.time.Instant;

/**
 * One plugin bundle activation outcome, for the durable audit trail (PLAT-12).
 *
 * <p>Deliberately carries {@code detail} in full, unredacted -- unlike the neutralized, length-capped
 * text a console startup message may show, the audit trail is the complete record, per {@code
 * DESIGN.md}'s "where detail goes": the console message is primary and unconditional, and the audit
 * write is additional, never a substitute, and never allowed to suppress the console message if the
 * audit write itself fails.</p>
 *
 * @param occurredAt   when the outcome was determined
 * @param pluginId     the bundle id this event concerns, or {@code null} when the failure occurred
 *                     before an id could be attributed (e.g. an unreadable manifest)
 * @param outcome      what happened
 * @param reasonToken  a fixed-vocabulary token identifying why, safe on its own
 * @param detail       the full, unredacted diagnostic detail; never truncated or neutralized here
 * @param incidentId   correlates this record with the console message shown for the same event
 */
public record PluginActivationEvent(
        Instant occurredAt, String pluginId, Outcome outcome, String reasonToken, String detail,
        String incidentId) {

/**
 * Defines the outcome contract exposed to Ravenroot integrators.
 */
    public enum Outcome {
/** Bundle became available to the plugin registry. */
        ACTIVATED,
/** Bundle was rejected before activation; the reason token identifies why. */
        REFUSED
    }
}
