package ai.ravenroot.api.correlation;

import java.util.Optional;

/**
 * Immutable application context established at an authorized entry, independently of transport,
 * request, trace, process and traversal identities. Replacing a node payload does not replace it.
 *
 * @param applicationCorrelationId opaque business operation identity
 * @param contributionId optional distinct business item, never inferred from delivery attempts
 * @param contributionKind optional authored kind or partition label
 * @param roundId explicit join generation; a retry retains the same value
 * @param publicReference bounded non-secret lookup reference, such as a filename
 */
public record ApplicationCorrelationContext(String applicationCorrelationId, Optional<String> contributionId,
                                            Optional<String> contributionKind, String roundId,
                                            Optional<String> publicReference) {
    /** Validates the public hard ceiling; composition applies its tighter configured limit first. */
    public ApplicationCorrelationContext {
        var ceiling = new CorrelationLimits(4096, 16384, 256, 10000,
                ai.ravenroot.api.payload.PayloadLimits.DEFAULTS);
        applicationCorrelationId = ceiling.requireId(applicationCorrelationId, "applicationCorrelationId");
        roundId = ceiling.requireId(roundId, "roundId");
        contributionId = optional(contributionId, ceiling, "contributionId");
        contributionKind = optional(contributionKind, ceiling, "contributionKind");
        publicReference = optional(publicReference, ceiling, "publicReference");
    }
    private static Optional<String> optional(Optional<String> value, CorrelationLimits limits, String field) {
        return value == null ? Optional.empty() : value.map(text -> limits.requireId(text, field));
    }
}
