package ai.ravenroot.plugin.bundle;

import java.util.Locale;
import java.util.Objects;

/**
 * One checksummed file a plugin bundle manifest declares (PLAT-12): the main artifact, or one
 * runtime dependency.
 *
 * <p>{@code fileName} must be a bare filename, never a path: no separator, no {@code ..}, not
 * absolute. That is enforced at construction so a {@link PluginArtifact} can never itself be the
 * vector for a path-escape check to skip — every caller that already has one has already had it
 * validated.</p>
 *
 * @param fileName  the stable filename the artifact occupies directly inside the bundle directory
 * @param sha256Hex the declared SHA-256 digest, lowercase hex, exactly 64 characters
 * @param sizeBytes the declared size in bytes
 */
public record PluginArtifact(String fileName, String sha256Hex, long sizeBytes) {

    public PluginArtifact {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256Hex, "sha256Hex");
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.equals(".") || fileName.equals("..")) {
            throw PluginBundleRejection.invalidArtifactName(fileName);
        }
        String normalizedDigest = sha256Hex.trim().toLowerCase(Locale.ROOT);
        if (normalizedDigest.length() != 64 || !normalizedDigest.chars()
                .allMatch(character -> Character.digit(character, 16) >= 0)) {
            throw PluginBundleRejection.malformedManifest("sha256", sha256Hex);
        }
        sha256Hex = normalizedDigest;
        if (sizeBytes < 0) {
            throw PluginBundleRejection.malformedManifest("sizeBytes", Long.toString(sizeBytes));
        }
    }
}
