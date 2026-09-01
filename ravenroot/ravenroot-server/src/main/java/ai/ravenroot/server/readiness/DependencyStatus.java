package ai.ravenroot.server.readiness;

/**
 * One optional dependency's status, reported for observability only: no
 * {@code DependencyStatus} ever changes {@link ReadinessReport#ready()}. An optional dependency
 * (today: the program runtime and the AI provider/agent registries) being unavailable is expected
 * and supported; hiding that from the readiness response would just move the discovery to the
 * first request that needed it.
 */
public record DependencyStatus(String name, boolean up, String detail) {
    public DependencyStatus {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        detail = detail == null ? "" : detail;
    }
}
