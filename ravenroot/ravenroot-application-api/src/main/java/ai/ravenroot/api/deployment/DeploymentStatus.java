package ai.ravenroot.api.deployment;

import java.util.Objects;
import java.util.Optional;

/**
 * A deployment's observable state and, alongside it, a sanitized cause (ADR 0021 D2).
 *
 * <h2>Why the cause is a separate component</h2>
 * <p>{@link DeploymentState} is what a probe, an alert rule or a dashboard keys on. The cause exists
 * for the person reading an incident and is free to change wording, gain detail or be redacted
 * without notice. Folding the two together — a state whose name encoded a reason, or a single
 * string parsed by both audiences — would make every consumer a text parser and would break them
 * silently the first time the wording improved.
 *
 * <h2>Sanitized means sanitized</h2>
 * <p>The cause is a short operator-facing summary. It must not carry payloads, credentials, secret
 * references or raw exception messages from user code, because this value reaches logs and operator
 * surfaces, which are exactly the places a leaked secret persists.
 *
 * <p><strong>This obligation is this record's own, and {@code ExecutionEvent.detail} is not an
 * example of it being met</strong>. That field was cited here as "bounded and redacted"; it
 * is bounded and it is not redacted. It carries the deepest cause's {@code getMessage()} verbatim on
 * failure events and graph-authored outcome text on completion events, with no redaction anywhere in
 * core — a diagnostic string that may contain payload fragments and author-controlled text, safe to
 * display to the run's own author and unsafe to forward anywhere else. A reader who inherited the
 * old claim and treated {@code detail} as pre-sanitized should re-check what they did with it; see
 * that field's documentation for the full contract. A cause is present only
 * for {@link DeploymentState#DEGRADED} and {@link DeploymentState#FAILED}; the other states have
 * nothing to explain and carry {@link Optional#empty()}.
 * @param id deployment whose lifecycle is observed.
 * @param state machine-readable current lifecycle state.
 * @param cause sanitized operator summary for degraded or failed states only.
 */
public record DeploymentStatus(DeploymentId id, DeploymentState state, Optional<String> cause) {
/**
 * Normalizes absent or blank causes and rejects explanations for states that cannot carry one.
 */
    public DeploymentStatus {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        cause = cause == null ? Optional.empty() : cause.filter(text -> !text.isBlank());
        if (cause.isPresent() && state != DeploymentState.DEGRADED && state != DeploymentState.FAILED) {
            throw new IllegalArgumentException(
                    "Only DEGRADED and FAILED carry a cause; " + state + " has nothing to explain");
        }
    }

/**
 * A state with nothing to explain.
 * @param id deployment whose state is reported.
 * @param state state with no associated incident explanation.
 * @return status that deliberately carries no cause.
 */
    public static DeploymentStatus of(DeploymentId id, DeploymentState state) {
        return new DeploymentStatus(id, state, Optional.empty());
    }

/**
 * A state that needs explaining, with an already-sanitized operator-facing summary.
 * @param id deployment whose state is reported.
 * @param state degraded or failed state requiring explanation.
 * @param sanitizedCause bounded operator-safe incident summary.
 * @return status carrying the supplied sanitized explanation.
 */
    public static DeploymentStatus of(DeploymentId id, DeploymentState state, String sanitizedCause) {
        return new DeploymentStatus(id, state, Optional.ofNullable(sanitizedCause));
    }

/**
 * Whether this deployment is admitting ingress. Only {@link DeploymentState#READY} does.
 * @return whether this status permits new ingress admission.
 */
    public boolean admitting() {
        return state == DeploymentState.READY;
    }
}
