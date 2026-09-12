package ai.ravenroot.api.persistence;

import java.util.List;

/**
 * The semantic dependencies an execution was admitted against, other than the graph document itself.
 *
 * <h2>Every field is a closed value or a digest, and that is the secret boundary</h2>
 * <p>Two fields are integers, two are names drawn from vocabularies this repository owns, and the
 * remaining four are SHA-256 digests derived by the runtime from values it resolved. There is no
 * free-form field, so a credential, a bearer token or an authorization snapshot has nowhere to go —
 * a manifest is safe to persist, project and log because of its shape, not because something
 * redacts it on the way out.</p>
 *
 * <h2>Why adapter-supplied identities are digested rather than recorded verbatim</h2>
 * <p>The engine id, the store and engine capability sets, the execution limits and the program
 * runtime's compatibility fingerprint are all supplied by implementations this contract does not
 * own. Recording them verbatim would put arbitrary third-party text into a durable, projectable
 * record, and validating that text would make a manifest refuse an otherwise sound deployment.
 * Digesting is the third option: a change is still detected exactly, and the manifest still cannot
 * carry anything but hexadecimal.</p>
 *
 * <h2>What this profile does not cover, stated rather than implied</h2>
 * <ul>
 *   <li><strong>No parser version and no planner version.</strong> This runtime has no separate
 *       planning or compilation stage and no versioned GraphML dialect, so there is no such version
 *       to pin. {@code graphSchemaVersion} and {@code definitionFormatVersion} are the two format
 *       versions that do exist, and they are what is recorded.</li>
 *   <li><strong>No node package content digest.</strong> See {@link PinnedNodePackage}.</li>
 *   <li><strong>No program artifact approval state.</strong> A program node carries its own source
 *       in the graph document, so the artifact's content digest is already pinned transitively by
 *       the graph pin. Whether that artifact is currently approved is mutable authorization state
 *       that must be re-read at redemption rather than restored from a manifest, and pinning it
 *       would defeat revocation.</li>
 * </ul>
 *
 * @param graphSchemaVersion version of the canonical graph snapshot format this execution used.
 * @param definitionFormatVersion version of the stored canonical definition format this execution used.
 * @param executionPolicy name of the submission policy the execution was admitted under.
 * @param unknownBehaviorMode admission stance for a behavior no trusted catalog entry claims.
 * @param engineDigest digest of the execution engine's identifier and capability set.
 * @param storeDigest digest of the execution store's capability set.
 * @param executionLimitsDigest digest of the graph execution limits in force at admission.
 * @param programRuntimeDigest digest of the program runtime's identifier and compatibility contract.
 */
public record ResolvedRuntimeProfile(int graphSchemaVersion, int definitionFormatVersion,
                                     String executionPolicy, String unknownBehaviorMode,
                                     String engineDigest, String storeDigest,
                                     String executionLimitsDigest, String programRuntimeDigest) {

    /** Domain tag callers use when deriving {@link #engineDigest()}. */
    public static final String ENGINE_DOMAIN = "ravenroot.execution-manifest.engine.v1";

    /** Domain tag callers use when deriving {@link #storeDigest()}. */
    public static final String STORE_DOMAIN = "ravenroot.execution-manifest.store.v1";

    /** Domain tag callers use when deriving {@link #executionLimitsDigest()}. */
    public static final String LIMITS_DOMAIN = "ravenroot.execution-manifest.limits.v1";

    /** Domain tag callers use when deriving {@link #programRuntimeDigest()}. */
    public static final String PROGRAM_RUNTIME_DOMAIN = "ravenroot.execution-manifest.program-runtime.v1";

    /** Rejects a profile whose fields are not closed values a manifest may safely carry. */
    public ResolvedRuntimeProfile {
        if (graphSchemaVersion <= 0) {
            throw new IllegalArgumentException("graphSchemaVersion must be positive");
        }
        if (definitionFormatVersion <= 0) {
            throw new IllegalArgumentException("definitionFormatVersion must be positive");
        }
        executionPolicy = ManifestTokens.requireEnumName(executionPolicy, "executionPolicy");
        unknownBehaviorMode = ManifestTokens.requireToken(unknownBehaviorMode, "unknownBehaviorMode");
        engineDigest = ManifestTokens.requireSha256Hex(engineDigest, "engineDigest");
        storeDigest = ManifestTokens.requireSha256Hex(storeDigest, "storeDigest");
        executionLimitsDigest = ManifestTokens.requireSha256Hex(executionLimitsDigest, "executionLimitsDigest");
        programRuntimeDigest = ManifestTokens.requireSha256Hex(programRuntimeDigest, "programRuntimeDigest");
    }

    /**
     * Derives one component digest under a domain tag.
     *
     * <p>Convenience over {@link ExecutionManifestDigest#component(String, java.util.List)} so a
     * runtime composing a profile does not import a second type to fill four of its fields.</p>
     *
     * @param domain one of this record's domain tag constants.
     * @param parts ordered values to encode; each is length-prefixed.
     * @return lowercase hexadecimal SHA-256 suitable for the matching field.
     */
    public static String digestOf(String domain, List<String> parts) {
        return ExecutionManifestDigest.component(domain, parts);
    }
}
