package ai.ravenroot.server.authoring;

/** Browser-safe publication observation used in authoring concurrency checks. */
@FunctionalInterface
public interface PublicationEvidence {
    /** Returns an opaque stable token, or {@code absent}; never a secret or caller-provided flag. */
    String token(String tenantId, String graphId, long releaseVersion);

    static PublicationEvidence absent() { return (tenant, graph, version) -> "absent"; }
}
