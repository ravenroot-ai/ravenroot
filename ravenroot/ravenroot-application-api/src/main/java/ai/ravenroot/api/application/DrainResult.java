package ai.ravenroot.api.application;

import java.util.Objects;

/**
 * The outcome of {@link AuthorizedRavenrootApplication#drain} (API-02).
 *
 * <p>Drain is ADR 0012's engine-wide mechanism, exposed as an on-demand operator command, using ADR
 * 0023 section 6's sequencing shape: stop accepting further work and stop every running node
 * cooperatively ({@code ExecutionEngine.drain()}, which itself refuses further {@code spawn}), then wait
 * up to a bounded duration. This type reports only whether that bound was met -- it does not escalate to
 * cancellation and does not close the engine. {@code ExecutionEngine.drain()}'s own contract is explicit
 * that "the underlying runtime is left up" so an application may drain, observe and only then
 * {@code close()}; conflating an operator-triggered drain with a shutdown would remove that choice from
 * the operator who asked only to drain. {@link Outcome#TIMED_OUT} is the caller's signal to observe
 * (e.g. {@code runtimeSnapshot()}) and decide its own next step -- retry the wait, cancel specific
 * traversals, or escalate to an actual shutdown -- rather than this method deciding for it.
 * @param outcome classification indicating whether draining was applied, unavailable, or refused
 */
public record DrainResult(Outcome outcome) {

/**
 * Rejects incomplete drain outcomes and keeps their operator-facing disposition explicit.
 */
    public DrainResult {
        Objects.requireNonNull(outcome, "outcome");
    }

/**
 * Enumerates the supported outcome values returned by this API.
 */
    public enum Outcome {
        /** Every node terminated within the configured bound; the runtime remains up. */
        DRAINED,
        /** The bound elapsed with work still outstanding; the runtime remains up and still draining. */
        TIMED_OUT
    }
}
