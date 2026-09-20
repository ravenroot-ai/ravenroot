package ai.ravenroot.api.application;

/** Sanitized result of an idempotent embed deployment traversal request. */
public record EmbedDeploymentStart(Outcome outcome, String requestId) {
    public enum Outcome { ACCEPTED, DUPLICATE, REFUSED, RECONCILE }

    public EmbedDeploymentStart {
        if (outcome == null || requestId == null || requestId.isBlank() || requestId.length() > 256) {
            throw new IllegalArgumentException("invalid embed deployment start result");
        }
    }
}
