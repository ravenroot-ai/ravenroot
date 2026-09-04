package ai.ravenroot.api.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The integrity address of one execution manifest, and the only construction used to derive it.
 *
 * <h2>Why this digest is domain-separated and length-prefixed, when {@link GraphContentId} is not</h2>
 * <p>{@link GraphContentId} hashes one complete XML document, so a concatenation ambiguity cannot
 * arise and a plain digest buys byte-identity with the graph version reference. A manifest is the
 * opposite shape: it is a concatenation of independently chosen fields, and without length prefixes
 * a package id of {@code "ab"} beside a version of {@code "c"} would hash identically to
 * {@code "a"} beside {@code "bc"}. The construction here is the one
 * {@link ai.ravenroot.api.programming.ProgramArtifactIdentity} already uses for the same reason, so
 * this repository has one answer to that problem rather than two.</p>
 *
 * <h2>What a matching digest does and does not prove</h2>
 * <p>It proves that the manifest read back is field-for-field the manifest that was pinned. It is an
 * integrity check against storage faults and against a row edited underneath the runtime; it is not
 * a signature and authenticates nothing, exactly as
 * {@link ai.ravenroot.api.programming.ArtifactEvidence} states of its own digest. Anyone who can
 * rewrite the row can recompute the digest beside it.</p>
 *
 * @param value lowercase hexadecimal SHA-256 of the manifest's canonical encoding.
 */
public record ExecutionManifestDigest(String value) {

    /** Domain tag for a whole manifest, so this digest can never collide with another SHA-256 use. */
    private static final String MANIFEST_DOMAIN = "ravenroot.execution-manifest.v1";

    /** Rejects an address that is not a lowercase hexadecimal SHA-256 digest. */
    public ExecutionManifestDigest {
        value = ManifestTokens.requireSha256Hex(value, "digest value");
    }

    /**
     * Derives the digest of one manifest from its fields.
     *
     * <p>Node packages are sorted before they are encoded, so two runtimes that resolved the same
     * packages in different registration orders agree on the address.</p>
     *
     * @param manifest manifest to address; never {@code null}.
     * @return the canonical digest of that manifest.
     */
    public static ExecutionManifestDigest of(ExecutionManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        var parts = new java.util.ArrayList<String>();
        parts.add(Integer.toString(manifest.formatVersion()));
        parts.add(manifest.key().tenantId());
        parts.add(manifest.key().processInstanceId().toString());
        parts.add(manifest.graphContentId().value());
        parts.add(manifest.graphIdentity().graphId());
        parts.add(manifest.graphIdentity().versionId());
        ResolvedRuntimeProfile runtime = manifest.runtime();
        parts.add(Integer.toString(runtime.graphSchemaVersion()));
        parts.add(Integer.toString(runtime.definitionFormatVersion()));
        parts.add(runtime.executionPolicy());
        parts.add(runtime.unknownBehaviorMode());
        parts.add(runtime.engineDigest());
        parts.add(runtime.storeDigest());
        parts.add(runtime.executionLimitsDigest());
        parts.add(runtime.programRuntimeDigest());
        parts.add(Integer.toString(manifest.nodePackages().size()));
        for (PinnedNodePackage pinned : manifest.nodePackages()) {
            parts.add(pinned.packageId());
            parts.add(pinned.identityDigest());
        }
        return new ExecutionManifestDigest(component(MANIFEST_DOMAIN, parts));
    }

    /**
     * Derives one lowercase hexadecimal SHA-256 over an ordered list of fields under a domain tag.
     *
     * <p>Public because the manifest's own component digests — the engine, the store, the execution
     * limits and the program runtime — are derived by the runtime that resolves them and must use
     * this exact encoding. A second encoder would be a second answer to the concatenation ambiguity
     * this one exists to close.</p>
     *
     * @param domain domain tag distinguishing this digest from every other SHA-256 use.
     * @param parts ordered fields to encode; each is length-prefixed, and {@code null} is rejected.
     * @return lowercase hexadecimal SHA-256 of the domain-separated encoding.
     */
    public static String component(String domain, List<String> parts) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain cannot be blank");
        }
        Objects.requireNonNull(parts, "parts");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            for (String part : parts) {
                byte[] bytes = Objects.requireNonNull(part, "manifest digest part").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
