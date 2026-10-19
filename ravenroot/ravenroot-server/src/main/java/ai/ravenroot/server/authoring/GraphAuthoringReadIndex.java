package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringRepository.DocumentSummary;
import ai.ravenroot.api.authoring.GraphAuthoringRepository.Page;

import java.util.List;
import java.util.Optional;

/**
 * Disposable authoring projection, separate from admitted executable graph definitions.
 * Implementations publish a complete immutable generation atomically and page metadata in storage.
 * Keys contain trusted repository configuration and tenant identity, never credentials.
 */
public interface GraphAuthoringReadIndex extends AutoCloseable {
    /**
     * One validated document projection and the identities needed for incremental reconciliation.
     */
    record Entry(
            DocumentSummary summary,
            String draftBlob,
            String releaseBlob,
            String draftDigest,
            String releaseDigest) {}

    /** Exact upstream observations from which a generation was constructed. */
    record Generation(
            String id,
            String draftHead,
            String releaseHead,
            String publicationVersion,
            long synchronizedAtMillis) {}

    Optional<Generation> latest(String repository, String tenant);

    List<Entry> entries(String repository, String tenant, String generation);

    Page<DocumentSummary> page(
            String repository, String tenant, String generation, int page, int size);

    void publish(String repository, String tenant, Generation generation, List<Entry> entries);

    Optional<byte[]> content(String repository, String identity);

    void retainContent(String repository, String identity, byte[] bytes);

    /** Read-only retained-content size for bounded operational statistics. */
    long retainedBytes();

    @Override
    default void close() {}
}
