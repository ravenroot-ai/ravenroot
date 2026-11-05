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
    String token(
            String tenantId,
            String graphId,
            long releaseVersion,
            String sourceRevision,
            byte[] graphMl);

    /**
     * Returns an evidence source representing an unconfigured publication catalog.
     *
     * @return evidence source that always reports {@code absent}
     */
    static PublicationEvidence absent() {
        return new PublicationEvidence() {
            @Override
            public String token(
                    String tenant, String graph, long version, String source, byte[] bytes) {
                return "absent";
            }

            @Override
            public String version() {
                return "absent";
            }

            @Override
            public String tokenByDigest(
                    String tenant,
                    String graph,
                    long version,
                    String source,
                    String digest,
                    java.util.function.Supplier<byte[]> bytes) {
                return "absent";
            }
        };
    }

    /**
     * Captures fresh catalog state once for a complete synchronization. Custom evidence stays fresh
     * by default.
     */
    default PublicationEvidence snapshot() {
        return this;
    }

    /** Immutable catalog observation identifier, or null when evidence cannot be reused. */
    default String version() {
        return null;
    }

    /**
     * Verifies exact release bytes by digest when supported; the default preserves custom evidence
     * behavior.
     */
    default String tokenByDigest(
            String tenant,
            String graph,
            long version,
            String source,
            String digest,
            java.util.function.Supplier<byte[]> bytes) {
        return token(tenant, graph, version, source, bytes.get());
    }
}
