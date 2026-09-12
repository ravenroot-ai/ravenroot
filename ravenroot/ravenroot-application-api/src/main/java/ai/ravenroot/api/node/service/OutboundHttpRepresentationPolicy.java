package ai.ravenroot.api.node.service;

import java.util.Set;

/**
 * Immutable selection of HTTP statuses whose response media type will be interpreted.
 *
 * <p>Successful responses are always validated. Callers may additionally select bounded,
 * concrete non-success statuses or select every status when an operator-owned protocol schema
 * defines a representation for its default response.</p>
 *
 * @param allStatuses whether every HTTP status has a representation that must be validated
 * @param additionalStatuses concrete non-success statuses whose representations must be validated
 */
public record OutboundHttpRepresentationPolicy(boolean allStatuses, Set<Integer> additionalStatuses) {
    /** Validates successful response representations and otherwise leaves error bodies opaque. */
    public static final OutboundHttpRepresentationPolicy SUCCESS_ONLY =
            new OutboundHttpRepresentationPolicy(false, Set.of());

    /** Validates the representation of every response status. */
    public static final OutboundHttpRepresentationPolicy ALL_STATUSES =
            new OutboundHttpRepresentationPolicy(true, Set.of());

    /** Creates a bounded immutable policy. */
    public OutboundHttpRepresentationPolicy {
        additionalStatuses = Set.copyOf(additionalStatuses);
        if (additionalStatuses.size() > 64
                || additionalStatuses.stream().anyMatch(status -> status < 100 || status > 599)) {
            throw new IllegalArgumentException("response representation statuses are invalid");
        }
    }

    /**
     * Returns whether the response representation is interpreted for this status.
     *
     * @param status HTTP response status
     * @return {@code true} when media-type validation is required
     */
    public boolean validates(int status) {
        return status >= 200 && status < 300 || allStatuses || additionalStatuses.contains(status);
    }
}
