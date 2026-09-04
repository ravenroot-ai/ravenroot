package ai.ravenroot.core.manifest;

import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifestCompatibility;

import java.util.Objects;

/**
 * Refusal raised when an execution's pinned dependencies are not the ones this runtime resolves.
 *
 * <p>Separate from {@link ai.ravenroot.api.persistence.ExecutionManifestStoreException} because it
 * is not a storage failure: the manifest was found and verified, and what failed is the comparison
 * against the current environment. A caller that treated the two alike would retry a storage outage
 * and an incompatible runtime identically, and only one of those can be resolved by waiting.</p>
 *
 * <p>The message is the compatibility report's own bounded description, so a diagnostic names each
 * differing dimension and both of its values without any further formatting — and, because every one
 * of those values is a closed token or a digest, without any redaction.</p>
 */
public final class ExecutionManifestIncompatibleException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient ExecutionKey key;
    private final transient ExecutionManifestCompatibility report;

    /**
     * Creates a refusal carrying the dimensions along which the runtime disagrees with the pin.
     *
     * @param key tenant-scoped execution whose manifest does not match this runtime.
     * @param report the differences found, bounded and free of protected configuration.
     */
    public ExecutionManifestIncompatibleException(ExecutionKey key, ExecutionManifestCompatibility report) {
        super("process instance " + Objects.requireNonNull(key, "key").processInstanceId()
                + " was accepted against a different dependency set than this runtime resolves ("
                + Objects.requireNonNull(report, "report").describe() + ")");
        this.key = key;
        this.report = report;
    }

    /**
     * The execution this refusal is about.
     *
     * @return tenant-scoped execution key.
     */
    public ExecutionKey key() {
        return key;
    }

    /**
     * The differences that caused the refusal.
     *
     * @return bounded compatibility report.
     */
    public ExecutionManifestCompatibility report() {
        return report;
    }
}
