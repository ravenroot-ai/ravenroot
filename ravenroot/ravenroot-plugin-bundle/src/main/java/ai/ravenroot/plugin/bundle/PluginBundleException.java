package ai.ravenroot.plugin.bundle;

import java.util.Map;
import java.util.Objects;

/**
 * A plugin bundle rejection (PLAT-12).
 *
 * <p>Mirrors {@code ai.ravenroot.api.payload.PayloadException} and {@code GraphMlRejectionDetail}
 * deliberately: this is not a code dependency on either (both are effectively package-private to
 * their own sanitisation policy) but the same three rules, because a third sanitisation posture
 * invented for this module would be a third thing to keep in step with the two the repository
 * already has. {@link #getMessage()} is assembled exclusively from fixed text authored in
 * {@link PluginBundleRejection}; {@link #diagnosticDetail()} carries everything derived from the
 * bundle's own content — paths, filenames, observed digests — and is for the server-side record
 * only; {@link #incidentId()} lets the two be correlated without the caller-facing message carrying
 * anything the bundle author wrote.</p>
 */
public final class PluginBundleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Why a bundle or its manifest was rejected. */
    public enum Reason {
        MALFORMED_MANIFEST,
        UNSUPPORTED_SCHEMA_VERSION,
        UNSUPPORTED_SDK_CONTRACT,
        MISSING_REQUIRED_FIELD,
        UNKNOWN_FIELD,
        INVALID_ARTIFACT_NAME,
        PATH_ESCAPES_BUNDLE,
        SYMLINK_ESCAPES_BUNDLE,
        /**
         * The path handed to {@link PluginBundleValidator#validate} does not exist, or exists but is
         * not a directory. This is an operator-side mistake made before any manifest is ever read --
         * a wrong path, a bundle that was never built or never unpacked -- not a defect in the
         * bundle's own content. Distinguishing it from {@link #MANIFEST_NOT_FOUND} matters because the
         * two point an operator in different directions: this one says "check the path or build it",
         * that one says "the bundle exists but its manifest is missing".
         */
        BUNDLE_NOT_FOUND,
        /**
         * {@code bundleDir} exists and is a directory, but it has no
         * {@value PluginBundleValidator#MANIFEST_FILE_NAME} directly inside it. Unlike
         * {@link #BUNDLE_NOT_FOUND}, the bundle location itself is correct: this is a mutilated or
         * incomplete bundle -- the manifest file was never written, was deleted, or was misplaced --
         * not a wrong path.
         */
        MANIFEST_NOT_FOUND,
        ARTIFACT_MISSING,
        CHECKSUM_MISMATCH,
        SIZE_MISMATCH,
        DUPLICATE_ARTIFACT_NAME,
        UNDECLARED_FILE,
        RESERVED_PACKAGE,
        BUNDLE_TOO_LARGE,
        TOO_MANY_ENTRIES,
        /** {@code RAVENROOT_ENABLED_PLUGINS} names an id with no installed bundle (PLAT-12). */
        UNKNOWN_PLUGIN_ID,
        /** Two installed bundles declare the same manifest {@code id} (PLAT-12). */
        DUPLICATE_PLUGIN_ID,
        /** A declared {@code nodePackageClasses} entry is absent from the bundle's classpath. */
        MISSING_CLASS,
        /** A declared class was found but does not implement {@code NodePackage}. */
        NOT_A_NODE_PACKAGE,
        /** A declared class has no public no-argument constructor, or its construction threw. */
        INSTANTIATION_FAILED,
        /** Linking a declared class failed because something it references was never on the bundle's classpath. */
        MISSING_DEPENDENCY
    }

    private final Reason reason;
    private final String incidentId;
    private final Map<String, String> diagnosticDetail;

    PluginBundleException(Reason reason, String message, String incidentId, Map<String, String> diagnosticDetail) {
        super(Objects.requireNonNull(message, "message"));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.diagnosticDetail = Map.copyOf(diagnosticDetail);
    }

    public Reason reason() {
        return reason;
    }

    /** Correlates this rejection with the server-side record without the response carrying it. */
    public String incidentId() {
        return incidentId;
    }

    /** Everything derived from the bundle's own content. Server-side only; never in {@link #getMessage()}. */
    public Map<String, String> diagnosticDetail() {
        return diagnosticDetail;
    }
}
