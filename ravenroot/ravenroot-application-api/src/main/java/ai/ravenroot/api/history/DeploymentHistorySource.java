package ai.ravenroot.api.history;

/** Captured deployment binding. The tenant is always supplied by authenticated SDK/request context.
 * @param deploymentId logical deployment identity; a new incarnation does not change it
 * @param incarnationId captured physical registration incarnation used as a stale-source fence
 * @param graphVersion exact immutable graph version admitted for this history
 */
public record DeploymentHistorySource(String deploymentId, String incarnationId, String graphVersion) {
    /** Rejects missing, unbounded or control-bearing source coordinates. */
    public DeploymentHistorySource {
        deploymentId = text(deploymentId); incarnationId = text(incarnationId); graphVersion = text(graphVersion);
    }
    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid deployment history source");
        return value;
    }
}
