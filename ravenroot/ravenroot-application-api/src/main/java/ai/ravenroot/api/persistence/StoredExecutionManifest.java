package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * One execution manifest as the store holds it.
 *
 * <p>Every instance a store returns from a read has had its fields re-digested and compared against
 * the digest recorded beside them. A caller therefore never has to decide whether to trust what it
 * received: an unverifiable manifest is a classified failure, not a value.</p>
 *
 * @param manifest the pinned dependency set, exactly as it was committed.
 * @param digest the integrity address recorded beside it, re-derived and confirmed on every read.
 * @param committedAt instant at which the store durably recorded this manifest.
 */
public record StoredExecutionManifest(ExecutionManifest manifest, ExecutionManifestDigest digest,
                                      Instant committedAt) {

    /** Rejects a stored manifest whose recorded address disagrees with the fields it carries. */
    public StoredExecutionManifest {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(committedAt, "committedAt");
        if (!digest.equals(manifest.digest())) {
            throw new IllegalArgumentException(
                    "a stored manifest's digest must address the fields it carries");
        }
    }

    /**
     * The execution this manifest was pinned for.
     *
     * @return tenant-scoped process instance key.
     */
    public ExecutionKey key() {
        return manifest.key();
    }
}
