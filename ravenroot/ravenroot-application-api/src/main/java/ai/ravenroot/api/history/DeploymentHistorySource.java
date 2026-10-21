package ai.ravenroot.api.history;

/** Captured deployment binding. The tenant is always supplied by authenticated SDK/request context. */
public record DeploymentHistorySource(String deploymentId, String incarnationId, String graphVersion) {
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
