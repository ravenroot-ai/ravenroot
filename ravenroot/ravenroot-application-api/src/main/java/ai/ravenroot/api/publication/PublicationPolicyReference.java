package ai.ravenroot.api.publication;

import java.util.Objects;

/**
 * Immutable identity of one exact publication policy revision.
 *
 * @param id operator-owned stable profile identifier
 * @param version operator-owned immutable profile version
 * @param digest canonical SHA-256 binding over every effective profile field
 */
public record PublicationPolicyReference(String id, String version, String digest) {
    private static final String TOKEN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final String SHA256 = "sha256:[0-9a-f]{64}";

    /** Sentinel used when a node has no usable configured policy reference. */
    public static final PublicationPolicyReference UNCONFIGURED = new PublicationPolicyReference(
            "unconfigured", "unconfigured", "sha256:" + "0".repeat(64));

    /** Validates the bounded public identity and canonical digest. */
    public PublicationPolicyReference {
        id = requireToken(id, "policy id");
        version = requireToken(version, "policy version");
        digest = Objects.requireNonNull(digest, "policy digest");
        if (!digest.matches(SHA256)) {
            throw new IllegalArgumentException("policy digest must be a lowercase SHA-256 binding");
        }
    }

    private static String requireToken(String value, String name) {
        if (value == null || !value.matches(TOKEN)) {
            throw new IllegalArgumentException(name + " must be a bounded canonical identifier");
        }
        return value;
    }
}
