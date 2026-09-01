package ai.ravenroot.plugin.bundle;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import ai.ravenroot.plugin.bundle.PluginBundleException.Reason;

/**
 * The single declared sanitisation policy for every plugin bundle rejection (PLAT-12).
 *
 * <p>Mirrors {@code ai.ravenroot.api.payload.PayloadRejection}'s three rules exactly (see that
 * class's javadoc for the reasoning in full): a public message built only from text authored here
 * and from server-side limit numbers; everything derived from the bundle's own content — a path, a
 * filename, an observed digest — routed to {@link PluginBundleException#diagnosticDetail()} and
 * never {@link Throwable#getMessage()}; and an incident id on every rejection so the two records can
 * be correlated without the caller-facing message carrying anything the bundle author wrote.</p>
 */
final class PluginBundleRejection {
    private static final SecureRandom INCIDENTS = new SecureRandom();

    private PluginBundleRejection() {
    }

    /**
     * A manifest field or document shape that failed to parse into what it should have been.
     *
     * <p>{@code field} is the diagnosis and is never optional: it names the field (or, for a
     * whole-document shape failure, a fixed context token like {@code "root"} or {@code "json"}) and
     * is always present in {@link PluginBundleException#diagnosticDetail()}, independent of whether
     * {@code observed} has anything to add. This is deliberate, not incidental: seven call sites
     * across this module once passed {@code (key, null)} to a predecessor of this method whose
     * {@code detail(key, value)} helper returned an empty map whenever {@code value} was {@code null}
     * — discarding {@code key}, which was the entire diagnosis, along with it. Every one of those
     * rejections came back as {@code reason=MALFORMED_MANIFEST, detail={}}, sanitized but not
     * actionable. Building the map inline here, with {@code field}
     * unconditional and {@code observed} strictly additive, makes that failure mode structurally
     * impossible to reintroduce at a ninth call site: there is no code path through this method that
     * drops {@code field}.</p>
     *
     * @param field    the field name, or a fixed context token for a whole-document failure; required
     * @param observed what was actually found, when there is something more specific to say than the
     *                 field name alone; {@code null} when there is not
     */
    static PluginBundleException malformedManifest(String field, String observed) {
        Objects.requireNonNull(field, "field");
        var detail = new LinkedHashMap<String, String>();
        detail.put("field", field);
        if (observed != null) {
            detail.put("observed", truncate(observed));
        }
        return build(Reason.MALFORMED_MANIFEST, "Plugin manifest is not well-formed", detail);
    }

    static PluginBundleException unsupportedSchemaVersion(String observed) {
        return build(Reason.UNSUPPORTED_SCHEMA_VERSION,
                "Plugin manifest declares a schemaVersion this validator does not implement",
                detail("schemaVersion", observed));
    }

    static PluginBundleException unsupportedSdkContract(String observed) {
        return build(Reason.UNSUPPORTED_SDK_CONTRACT,
                "Plugin manifest declares a Node SDK contract this runtime does not host",
                detail("sdkContract", observed));
    }

    static PluginBundleException missingRequiredField(String fieldName) {
        return build(Reason.MISSING_REQUIRED_FIELD, "Plugin manifest is missing a required field",
                detail("field", fieldName));
    }

    /** The offending key is a detail, not a message: it is manifest-author-supplied, unvalidated text. */
    static PluginBundleException unknownField(String fieldName) {
        return build(Reason.UNKNOWN_FIELD,
                "Plugin manifest declares a field this schema does not recognise", detail("field", fieldName));
    }

    static PluginBundleException invalidArtifactName(String observed) {
        return build(Reason.INVALID_ARTIFACT_NAME,
                "Plugin manifest declares an artifact name that is not a bare filename",
                detail("name", observed));
    }

    static PluginBundleException pathEscapesBundle(String observed) {
        return build(Reason.PATH_ESCAPES_BUNDLE,
                "Plugin bundle artifact path resolves outside the bundle directory", detail("path", observed));
    }

    static PluginBundleException symlinkEscapesBundle(String observed) {
        return build(Reason.SYMLINK_ESCAPES_BUNDLE,
                "Plugin bundle contains a symbolic link that resolves outside the bundle directory",
                detail("path", observed));
    }

    /**
     * {@code bundleDir} does not exist, or exists but is not a directory -- discovered before any
     * manifest is read. The message is worded to stay true in both sub-cases: it does not say the
     * path does not exist, because it may -- as a regular file, which fails this same guard.
     * {@code path} is the exact path that was checked, never the fixed manifest filename constant:
     * reporting the constant here is precisely the defect this factory exists to avoid.
     */
    static PluginBundleException bundleNotFound(Path bundleDir) {
        return build(Reason.BUNDLE_NOT_FOUND, "Plugin bundle path is not a plugin bundle directory",
                detail("path", bundleDir.toString()));
    }

    /**
     * {@code bundleDir} exists and is a directory, but {@code manifestPath} -- the manifest file
     * directly inside it -- does not exist as a regular file. {@code path} is the manifest path that
     * was checked, so the diagnostic detail names an observed location, never a bare constant.
     */
    static PluginBundleException manifestNotFound(Path manifestPath) {
        return build(Reason.MANIFEST_NOT_FOUND, "Plugin bundle has no manifest file",
                detail("path", manifestPath.toString()));
    }

    static PluginBundleException artifactMissing(String observed) {
        return build(Reason.ARTIFACT_MISSING,
                "Plugin manifest declares an artifact that is not present in the bundle",
                detail("name", observed));
    }

    static PluginBundleException checksumMismatch(String observed) {
        return build(Reason.CHECKSUM_MISMATCH,
                "Plugin bundle artifact does not match its declared checksum", detail("name", observed));
    }

    static PluginBundleException sizeMismatch(String observed) {
        return build(Reason.SIZE_MISMATCH,
                "Plugin bundle artifact does not match its declared size", detail("name", observed));
    }

    static PluginBundleException duplicateArtifactName(String observed) {
        return build(Reason.DUPLICATE_ARTIFACT_NAME,
                "Plugin manifest declares the same artifact filename more than once", detail("name", observed));
    }

    static PluginBundleException undeclaredFile(String observed) {
        return build(Reason.UNDECLARED_FILE,
                "Plugin bundle contains a file the manifest does not declare and that is not a "
                        + "recognised metadata file", detail("path", observed));
    }

    static PluginBundleException reservedPackage(String observed) {
        return build(Reason.RESERVED_PACKAGE,
                "Plugin bundle contains a class in a package reserved for the Ravenroot runtime",
                detail("class", observed));
    }

    static PluginBundleException bundleTooLarge(long observedBytes, long limitBytes) {
        return build(Reason.BUNDLE_TOO_LARGE,
                "Plugin bundle exceeds the configured size limit of " + limitBytes + " bytes",
                detail("observedBytes", Long.toString(observedBytes)));
    }

    /** {@code artifactFileName} names which jar exceeded the limit; the limit itself is already in the message. */
    static PluginBundleException tooManyEntries(String artifactFileName, int limit) {
        return build(Reason.TOO_MANY_ENTRIES,
                "Plugin bundle exceeds the configured entry count limit of " + limit,
                detail("name", artifactFileName));
    }

    // ---- PLAT-12 loader-level rejections, never raised by the validator itself ------------------
    // The four class-related factories below carry both the plugin id AND the class name: "the
    // actionable thing" an operator needs is not just which class failed but which installed bundle
    // it belongs to, and instantiate(pluginId, className, loader) always has both in scope at the
    // point one of these is thrown.

    static PluginBundleException unknownPluginId(String observed) {
        return build(Reason.UNKNOWN_PLUGIN_ID, "Enabled plugin id is not installed", detail("id", observed));
    }

    static PluginBundleException duplicatePluginId(String observed) {
        return build(Reason.DUPLICATE_PLUGIN_ID,
                "Two installed plugin bundles declare the same id", detail("id", observed));
    }

    static PluginBundleException missingClass(String pluginId, String className) {
        return build(Reason.MISSING_CLASS,
                "Plugin declares a class that could not be loaded from its bundle", detailPair(pluginId, className));
    }

    static PluginBundleException notANodePackage(String pluginId, String className) {
        return build(Reason.NOT_A_NODE_PACKAGE,
                "Plugin declares a class that does not implement the NodePackage contract",
                detailPair(pluginId, className));
    }

    static PluginBundleException instantiationFailed(String pluginId, String className) {
        return build(Reason.INSTANTIATION_FAILED,
                "Plugin declares a class that could not be instantiated", detailPair(pluginId, className));
    }

    static PluginBundleException missingDependency(String pluginId, String className) {
        return build(Reason.MISSING_DEPENDENCY,
                "Plugin declares a class that could not be initialized because something it depends on "
                        + "was not found in the bundle", detailPair(pluginId, className));
    }

    /**
     * Every caller of this helper passes a value that is never {@code null} by construction — this
     * enforces that rather than accommodating a {@code null} silently, on purpose. The predecessor of
     * {@link #malformedManifest} returned {@code Map.of()} (dropping {@code key} along with a
     * {@code null} value) specifically because this helper offered that as its behaviour; failing
     * loudly here is what stops the same shortcut from being taken again.
     */
    private static Map<String, String> detail(String key, String value) {
        return Map.of(key, truncate(Objects.requireNonNull(value, "value")));
    }

    private static Map<String, String> detailPair(String pluginId, String className) {
        var ordered = new LinkedHashMap<String, String>();
        ordered.put("id", truncate(Objects.requireNonNull(pluginId, "pluginId")));
        ordered.put("class", truncate(Objects.requireNonNull(className, "className")));
        return ordered;
    }

    /** Truncation is acceptable here only: this string goes to a server-side sink, never the caller. */
    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "<truncated>";
    }

    private static PluginBundleException build(Reason reason, String message, Map<String, String> detail) {
        byte[] handle = new byte[8];
        INCIDENTS.nextBytes(handle);
        var ordered = new LinkedHashMap<>(detail);
        return new PluginBundleException(reason, message, HexFormat.of().formatHex(handle), ordered);
    }
}
