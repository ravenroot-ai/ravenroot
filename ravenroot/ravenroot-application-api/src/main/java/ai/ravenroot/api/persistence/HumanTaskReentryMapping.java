package ai.ravenroot.api.persistence;

/**
 * Graph outcomes selected by each terminal human-task disposition.
 *
 * @param resolvedOutcome outcome used after a valid response.
 * @param deniedOutcome outcome used after denial.
 * @param expiredOutcome outcome used after durable expiry.
 * @param cancelledOutcome outcome used after cancellation.
 */
public record HumanTaskReentryMapping(String resolvedOutcome, String deniedOutcome,
                                      String expiredOutcome, String cancelledOutcome) {
    /** Validates every graph-authored outcome key. */
    public HumanTaskReentryMapping {
        resolvedOutcome = HandlerRegistration.requireBoundedKey(resolvedOutcome, "resolvedOutcome");
        deniedOutcome = HandlerRegistration.requireBoundedKey(deniedOutcome, "deniedOutcome");
        expiredOutcome = HandlerRegistration.requireBoundedKey(expiredOutcome, "expiredOutcome");
        cancelledOutcome = HandlerRegistration.requireBoundedKey(cancelledOutcome, "cancelledOutcome");
    }

    /**
     * Resolves the graph outcome for a terminal status.
     *
     * @param status terminal status to map.
     * @return configured graph outcome.
     */
    public String outcomeFor(HumanTaskStatus status) {
        return switch (status) {
            case RESOLVED -> resolvedOutcome;
            case DENIED -> deniedOutcome;
            case EXPIRED -> expiredOutcome;
            case CANCELLED -> cancelledOutcome;
            case WAITING, ESCALATED -> throw new IllegalArgumentException(status + " is not terminal");
        };
    }
}
