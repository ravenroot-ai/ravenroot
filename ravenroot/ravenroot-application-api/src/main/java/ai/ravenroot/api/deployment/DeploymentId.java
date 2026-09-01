package ai.ravenroot.api.deployment;

import java.util.Objects;

/**
 * The stable identity of a long-lived graph deployment (ADR 0021 D2).
 *
 * <h2>Opaque on purpose</h2>
 * <p>This type carries a value and validates that it is present. It deliberately does <em>not</em>
 * define how the value is minted, whether it is unique across pods, how it survives a restart, or
 * how it relates to a graph version. Those belong to a durable graph-identity registry; this
 * process-scoped value does not pre-empt that registry's construction rules.
 *
 * <p>What that means concretely for Phase A: identity is <b>caller-supplied and process-scoped</b>.
 * Two pods given the same value are not thereby the same deployment, and nothing here claims they
 * are. When the registry lands, this type gains construction rules; it does not change shape.
 * @param value caller-supplied, process-scoped deployment identity.
 */
public record DeploymentId(String value) {
/**
 * Rejects blank identities without defining registry-level minting or uniqueness semantics.
 */
    public DeploymentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("A deployment id cannot be blank");
        }
    }

/**
 * Creates an opaque deployment identity from a non-blank value.
 * @return deployment identity carrying the supplied value.
 */
    public static DeploymentId of(String value) {
        return new DeploymentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
