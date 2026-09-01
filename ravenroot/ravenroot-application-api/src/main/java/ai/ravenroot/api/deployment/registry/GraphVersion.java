package ai.ravenroot.api.deployment.registry;

import ai.ravenroot.api.deployment.DeploymentId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable canonical graph bytes bound to exactly one tenant/deployment/version aggregate. */
public final class GraphVersion {
/**
 * Unbound input accepted by the server-side registry when creating or appending a version.
 * @param snapshotFormatVersion positive encoding version of the canonical graph bytes.
 * @param canonicalSnapshot non-empty canonical graph bytes.
 * @param createdBy identity that authored this immutable version.
 * @param createdAt timestamp assigned when the content was created.
 */
    public record Content(int snapshotFormatVersion, byte[] canonicalSnapshot, String createdBy, Instant createdAt) {
/**
 * Rejects incomplete content and defensively copies the graph bytes.
 */
        public Content {
            if (snapshotFormatVersion < 1) throw new IllegalArgumentException("snapshotFormatVersion must be positive");
            canonicalSnapshot = Objects.requireNonNull(canonicalSnapshot, "canonicalSnapshot").clone();
            if (canonicalSnapshot.length == 0) throw new IllegalArgumentException("canonicalSnapshot cannot be empty");
            if (createdBy == null || createdBy.isBlank()) throw new IllegalArgumentException("createdBy cannot be blank");
            Objects.requireNonNull(createdAt, "createdAt");
        }
/**
 * Returns a defensive copy of the canonical graph bytes.
 * @return bytes that cannot mutate this content value.
 */
        @Override public byte[] canonicalSnapshot() { return canonicalSnapshot.clone(); }
    }

    private final String tenantId;
    private final DeploymentId deploymentId;
    private final long version;
    private final Content content;
    private final String canonicalDigest;

    private GraphVersion(String tenantId, DeploymentId deploymentId, long version, Content content) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId cannot be blank");
        this.tenantId = tenantId;
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        this.version = version;
        this.content = Objects.requireNonNull(content, "content");
        this.canonicalDigest = sha256(content.canonicalSnapshot());
    }

/**
 * Registry/adapters use this factory only after resolving the authoritative aggregate.
 * @param tenantId tenant owning the deployment aggregate.
 * @param deploymentId deployment that owns the version.
 * @param version positive aggregate version number.
 * @param content immutable bytes and provenance for that version.
 * @return graph version bound to its authoritative aggregate identity.
 */
    public static GraphVersion bind(String tenantId, DeploymentId deploymentId, long version, Content content) {
        return new GraphVersion(tenantId, deploymentId, version, content);
    }

/** Returns the tenant that owns this graph version.
 * @return tenant that owns this graph version.
 */
    public String tenantId() { return tenantId; }
/** Returns the deployment aggregate that contains this version.
 * @return deployment aggregate that contains this version.
 */
    public DeploymentId deploymentId() { return deploymentId; }
/** Returns this version's positive number within the deployment aggregate.
 * @return positive version number within the deployment aggregate.
 */
    public long version() { return version; }
/** Returns the canonical snapshot encoding version.
 * @return canonical snapshot encoding version.
 */
    public int snapshotFormatVersion() { return content.snapshotFormatVersion(); }
/** Returns a defensive copy of the immutable canonical snapshot.
 * @return defensive copy of the immutable canonical snapshot.
 */
    public byte[] canonicalSnapshot() { return content.canonicalSnapshot(); }
/** Returns the SHA-256 digest of the canonical snapshot bytes.
 * @return SHA-256 digest of the canonical snapshot bytes.
 */
    public String canonicalDigest() { return canonicalDigest; }
/** Returns the identity recorded as the version author.
 * @return identity recorded as the version author.
 */
    public String createdBy() { return content.createdBy(); }
/** Returns the timestamp at which the immutable content was created.
 * @return timestamp at which the immutable content was created.
 */
    public Instant createdAt() { return content.createdAt(); }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
