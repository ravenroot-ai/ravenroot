package ai.ravenroot.api.persistence;

/**
 * Opaque reference to the graph version a process instance is pinned to (ADR 0010 section 8).
 *
 * <p>The execution store stores this reference and nothing else: no graph bytes, no content-hash
 * deduplication rule and no graph-version table. Graph identity, canonical semantic hashing and the
 * publish/activate/retire lifecycle belong to ARC-02's successor, and re-deciding them here would
 * fork identity between the catalog and execution persistence.</p>
 *
 * <p>The single invariant PERS-02 owns is that the pin is <em>write-once</em>: it is set when the
 * instance is created and any later attempt to change it is rejected, so recovery always replays
 * against the same definition.</p>
 * @param reference graph version reference held by the execution.
 */
public record GraphVersionPin(String reference) {
    /** Rejects a missing graph version reference before an execution can be pinned. */
    public GraphVersionPin {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("graph version pin reference cannot be blank");
        }
    }
}
