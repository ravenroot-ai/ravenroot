package ai.ravenroot.server.authoring;

/** Browser-safe publication observation used in authoring concurrency checks. */
@FunctionalInterface
public interface PublicationEvidence {
    /**
     * Returns an opaque stable token only when one verified publication binds the exact release.
     *
     * @param tenantId authenticated tenant owning the release
     * @param graphId stable authored graph identity
     * @param releaseVersion positive authored release version
     * @param sourceRevision immutable release-source commit
     * @param graphMl exact canonical release bytes
     * @return stable verified-publication token, or {@code absent}
     */
    String token(String tenantId, String graphId, long releaseVersion, String sourceRevision, byte[] graphMl);

    /**
     * Returns an evidence source representing an unconfigured publication catalog.
     *
     * @return evidence source that always reports {@code absent}
     */
    static PublicationEvidence absent() { return (tenant, graph, version, source, bytes) -> "absent"; }
}
