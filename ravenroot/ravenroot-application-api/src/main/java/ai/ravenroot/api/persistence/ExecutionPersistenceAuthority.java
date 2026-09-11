package ai.ravenroot.api.persistence;

import java.util.Objects;

/**
 * Exact managed-manifest identity and generic store capacity authorizing one managed execution
 * mutation. The authority is safe only for the manifest's own execution key; adapters validate that
 * relationship from their durable manifest row rather than trusting this carrier by itself.
 */
public record ExecutionPersistenceAuthority(ExecutionManifestDigest manifestDigest,
                                            int maximumPayloadBytes) {
    public ExecutionPersistenceAuthority {
        Objects.requireNonNull(manifestDigest, "manifestDigest");
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        }
    }

    /** Derives authority only from a v3/v4 manifest that explicitly pins generic capacity. */
    public static ExecutionPersistenceAuthority from(StoredExecutionManifest stored) {
        Objects.requireNonNull(stored, "stored");
        ExecutionManifest manifest = stored.manifest();
        if ((manifest.formatVersion() != ExecutionManifest.FORMAT_VERSION_3
                && manifest.formatVersion() != ExecutionManifest.FORMAT_VERSION_4)
                || manifest.operationalPolicy() == null
                || manifest.operationalPolicy().persistence().isEmpty()) {
            throw new IllegalArgumentException("execution manifest has no generic persistence capacity");
        }
        return new ExecutionPersistenceAuthority(stored.digest(), manifest.operationalPolicy()
                .persistence().orElseThrow().maximumPayloadBytes());
    }
}
