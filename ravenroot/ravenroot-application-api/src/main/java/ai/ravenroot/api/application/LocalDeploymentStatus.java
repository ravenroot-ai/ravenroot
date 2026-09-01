package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Bounded observation of one tenant-scoped, process-local graph deployment.
 *
 * <h2>The scope is on the wire, not in the documentation</h2>
 * <p>{@link #SCOPE} is serialized with every lifecycle response. That is the whole point of this
 * surface: a caller reading a lifecycle answer can tell, mechanically and without consulting prose,
 * that the guarantee it just received is single-process. The same token is what
 * {@link SourceSessionStatus#SCOPE} already puts on the wire for source sessions, and it is
 * deliberately the same string rather than a synonym — one fact, one vocabulary.</p>
 *
 * <h2>Where a mismatched scope is refused, and where it cannot be</h2>
 * <p>A caller that asks for a scope other than this one is refused rather than silently answered with
 * local semantics — but that refusal is an <em>adapter</em> concern, and this type does not carry it.
 * The application contract ({@link RavenrootApplication#registerLocalDeployment}) takes no scope
 * argument at all, so an in-process embedder has no way to request a scope and nothing to be refused
 * for; {@link LocalDeploymentException} accordingly has no scope reason. The refusal lives where a
 * scope can actually be expressed: the built-in HTTP adapter rejects a {@code scope} query parameter
 * that is not {@link #SCOPE} on {@code /v1/deployments} with {@code 400 INVALID_REQUEST}, before any
 * registration happens. Any other adapter that accepts a scope token owes the same refusal at its own
 * boundary. If a second scope becomes real, that change gives a scope argument a
 * meaning inside the contract — and only then a contract-level refusal something to refuse.</p>
 *
 * @param deploymentId caller-supplied identity, unique within the authenticated tenant
 * @param state truthful process-local lifecycle state
 * @param sourceCount effective inbound SOURCE nodes validated from the registered graph; legitimately
 *                    zero, because a graph with no source is registrable and controllable as a local
 *                    deployment (this is what separates a deployment from a transient traversal)
 * @param diagnostic fixed, bounded, operator-safe explanation, present only for degraded or failed
 *                   deployments
 */
public record LocalDeploymentStatus(String deploymentId, LocalDeploymentState state, int sourceCount,
                                    Optional<String> diagnostic) {
    /** Honest ownership label returned on the wire; intentionally makes no multi-replica claim. */
    public static final String SCOPE = "LOCAL_PROCESS";
    /** Defense in depth for implementations other than the reference implementation. */
    public static final int MAX_DIAGNOSTIC_CHARACTERS = 192;

    /** Normalizes the diagnostic and refuses one on a state that has nothing to explain. */
    public LocalDeploymentStatus {
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new IllegalArgumentException("deploymentId must not be blank");
        }
        Objects.requireNonNull(state, "state");
        if (sourceCount < 0) {
            throw new IllegalArgumentException("sourceCount must not be negative");
        }
        diagnostic = diagnostic == null ? Optional.empty() : diagnostic
                .map(String::trim).filter(text -> !text.isEmpty())
                .map(text -> text.substring(0, Math.min(text.length(), MAX_DIAGNOSTIC_CHARACTERS)));
        if (diagnostic.isPresent() && state != LocalDeploymentState.DEGRADED
                && state != LocalDeploymentState.FAILED) {
            throw new IllegalArgumentException("only degraded and failed deployments carry diagnostics");
        }
    }

    /**
     * A status with nothing to explain.
     * @param deploymentId caller-supplied identity within the authenticated tenant
     * @param state lifecycle state that carries no incident explanation
     * @param sourceCount effective inbound SOURCE nodes in the registered graph
     * @return status carrying no diagnostic
     */
    public static LocalDeploymentStatus of(String deploymentId, LocalDeploymentState state, int sourceCount) {
        return new LocalDeploymentStatus(deploymentId, state, sourceCount, Optional.empty());
    }

    /**
     * A status that needs explaining, with an already-sanitized operator-facing summary.
     * @param deploymentId caller-supplied identity within the authenticated tenant
     * @param state degraded or failed state requiring explanation
     * @param sourceCount effective inbound SOURCE nodes in the registered graph
     * @param safeDiagnostic bounded operator-safe explanation carrying no payload or adapter text
     * @return status carrying the supplied sanitized explanation
     */
    public static LocalDeploymentStatus of(String deploymentId, LocalDeploymentState state, int sourceCount,
                                           String safeDiagnostic) {
        return new LocalDeploymentStatus(deploymentId, state, sourceCount, Optional.ofNullable(safeDiagnostic));
    }
}
