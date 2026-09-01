package ai.ravenroot.server.assistant;

/**
 * The classes of this deployment's context an author can consent to, or refuse, one at a time under
 * the consent contract.
 *
 * <h2>Why this is a separate enum and not {@code AssistantInternalContext.Tool}</h2>
 * <p>The two are one-to-one today, and collapsing them would look like removing ceremony. It would
 * instead put a <em>model-facing name</em> into durable data. A tool's name and description belong to
 * the model-facing instruction pack and may be renamed as it is tuned. If the consent rows were keyed on
 * {@code ravenroot_execution_events}, renaming that tool for prompt reasons would silently revoke
 * every author's consent to that class, and the failure would be invisible: no error, just context
 * quietly no longer sent. The stored key must therefore be a name that changes only when the
 * <em>class of data</em> changes, which is what these constants are.</p>
 *
 * <h2>Why there are four members, not six</h2>
 * <p>ADR 0025 and the consent register speak of six context classes: status, runtime, node types,
 * execution events, the graph, and validation errors — with logs named separately again. This enum has
 * <b>four</b>, and the missing ones are missing for two different reasons, neither of which is an
 * oversight.</p>
 *
 * <ul>
 *   <li><b>Graph and validation errors have no server-side class to deny.</b> There is no tool, no
 *       authorized read and no route posture behind them: nothing composes them into a turn, so a
 *       toggle for them would be a control over a data flow that does not exist. A switch wired to
 *       nothing is worse than an absent switch, because it reads to an author as a promise that
 *       something is being withheld.</li>
 *   <li><b>Logs are absent for a reason already recorded in code.</b>
 *       {@code AssistantInternalContext} states that {@code AuthorizedRavenrootApplication} exposes no
 *       log-reading method at all — there is no authorized port to call — and that adding one means a
 *       new authorization action, a tenancy filter, a redaction pass and a bounded tail.</li>
 * </ul>
 *
 * <p><b>The reason for stopping at four rather than building the missing three:</b> a consent register
 * governs egress that already happens. Adding graph, validation-error and log reads in order to have
 * something to deny would expand the responsibility from "record and enforce consent over the context
 * this product sends" to "open three new context surfaces, then consent to them" — the second is a larger
 * change with an entirely different risk profile, since each new surface is a new authorized read of
 * author data with its own tenancy and redaction obligations. Those changes require separate design
 * and validation.
 * Widening this enum later is additive: a new member with no rows is a class nobody has consented to,
 * which is the correct default for a surface that did not exist when the author last chose.</p>
 */
public enum AssistantContextClass {

    /** Engine state, execution engine id and declared capabilities. Mirrors {@code /v1/status}. */
    STATUS,

    /** Active executions and per-node arrival counts. Mirrors {@code /v1/runtime}. */
    RUNTIME,

    /** The trusted node-type catalog. Mirrors {@code /v1/node-types}. */
    NODE_TYPES,

    /** Recent execution events the author is permitted to observe. Mirrors {@code /v1/events}. */
    EXECUTION_EVENTS
}
