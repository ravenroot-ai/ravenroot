package ai.ravenroot.plugin.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates one plugin bundle directory against its manifest (PLAT-12): the sole entry point
 * for turning a directory under {@code ravenroot-plugins/} into a trusted, checksum-verified
 * {@link PluginManifest}.
 *
 * <h2>What this class never does</h2>
 * <p>It never calls {@code Class.forName} or {@code ClassLoader.loadClass}, and it never adds a
 * bundle jar to any classpath or classloader. Every check here is data inspection: reading bytes,
 * hashing them, resolving filesystem paths, and reading a class file's own constant pool (see
 * {@link ClassFileOwnName}). That is what makes it safe to run this validator against a bundle that
 * has not been, and may never be, activated — presence under the convention directory triggers this
 * class and nothing else.</p>
 *
 * <h2>Rejection-first, by construction</h2>
 * <p>Every failure mode below throws before this method returns a {@link PluginManifest}: a missing
 * or oversized manifest, a malformed or unrecognised manifest field, a declared artifact absent from
 * disk, a checksum or size mismatch, a path or symlink that resolves outside the bundle, a file on
 * disk the manifest never declared, or a class in a reserved package. A caller only ever receives a
 * manifest for a bundle that passed every one of these.</p>
 */
public final class PluginBundleValidator {

    /** The fixed manifest filename this validator looks for directly inside a bundle directory. */
    public static final String MANIFEST_FILE_NAME = "ravenroot-plugin.json";

    /** Filename prefixes tolerated without being declared as artifacts (case-insensitive). */
    private static final List<String> UNDECLARED_METADATA_PREFIXES = List.of("license", "notice", "readme");

    /** Resource-exhaustion guard on the reserved-package scan: entries inspected per declared jar. */
    private static final int MAX_JAR_ENTRIES = 10_000;

    private PluginBundleValidator() {
    }

    /**
     * Validates {@code bundleDir} in full and returns its manifest.
     *
     * @throws PluginBundleException on any of the rejection modes documented on this class
     */
    public static PluginManifest validate(Path bundleDir) {
        if (!Files.isDirectory(bundleDir)) {
            throw PluginBundleRejection.bundleNotFound(bundleDir);
        }
        PluginManifest manifest = readManifest(bundleDir);

        var declaredArtifacts = new java.util.ArrayList<PluginArtifact>();
        declaredArtifacts.add(manifest.mainArtifact());
        declaredArtifacts.addAll(manifest.dependencyArtifacts());

        var declaredNames = new LinkedHashSet<String>();
        declaredNames.add(MANIFEST_FILE_NAME);
        for (PluginArtifact artifact : declaredArtifacts) {
            Path resolved = BundlePaths.resolveWithinBundle(bundleDir, artifact.fileName());
            verifySize(artifact, resolved);
            verifyChecksum(artifact, resolved);
            declaredNames.add(artifact.fileName());
        }

        requireNoUndeclaredFiles(bundleDir, declaredNames);
        requireNoReservedPackages(bundleDir, declaredArtifacts);

        return manifest;
    }

    private static PluginManifest readManifest(Path bundleDir) {
        Path manifestPath = bundleDir.resolve(MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw PluginBundleRejection.manifestNotFound(manifestPath);
        }
        // The size check happens against the filesystem entry, before any byte is read into memory,
        // for the same reason PayloadJson.read(byte[], limits) checks encoded length before decoding:
        // it is the one budget enforceable without touching the content, so it must bound every other
        // check. PluginBundleLimits.MANIFEST's encoded-byte ceiling is the source of truth; this is a
        // cheap pre-check ahead of it, not a second, independent limit.
        long size = size(manifestPath);
        long maxEncodedBytes = PluginBundleLimits.MANIFEST.maxEncodedBytes();
        if (size > maxEncodedBytes) {
            throw PluginBundleRejection.bundleTooLarge(size, maxEncodedBytes);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(manifestPath);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return PluginManifest.read(bytes);
    }

    private static void verifySize(PluginArtifact artifact, Path resolved) {
        long actual = size(resolved);
        if (actual != artifact.sizeBytes()) {
            throw PluginBundleRejection.sizeMismatch(artifact.fileName());
        }
    }

    /**
     * Verifies {@code resolved}'s actual SHA-256 against {@code artifact}'s declared digest.
     *
     * <p>This proves the installed jar has not changed since the manifest was written -- tamper
     * detection. It does not prove an independent rebuild of the same plugin source would produce
     * this same digest -- build reproducibility, a different and not-yet-held property, tracked
     * under PLAT-06. See {@code ravenroot-plugins/README.md}'s "Reproducibility" section for
     * the full reasoning and the measurement behind it; this note exists so a reader of the check
     * itself, not just the documentation, meets the same distinction.</p>
     */
    private static void verifyChecksum(PluginArtifact artifact, Path resolved) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
        }
        try (InputStream in = Files.newInputStream(resolved);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[8192];
            while (digestIn.read(buffer) != -1) {
                // draining the stream is the point; the digest accumulates as a side effect
            }
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        String actualHex = HexFormat.of().formatHex(digest.digest());
        if (!actualHex.equalsIgnoreCase(artifact.sha256Hex())) {
            throw PluginBundleRejection.checksumMismatch(artifact.fileName());
        }
    }

    private static void requireNoUndeclaredFiles(Path bundleDir, Set<String> declaredNames) {
        for (String relativeName : BundlePaths.listRegularFiles(bundleDir)) {
            if (declaredNames.contains(relativeName)) {
                continue;
            }
            String lower = relativeName.toLowerCase(Locale.ROOT);
            boolean toleratedMetadata = UNDECLARED_METADATA_PREFIXES.stream().anyMatch(lower::startsWith);
            if (!toleratedMetadata) {
                throw PluginBundleRejection.undeclaredFile(relativeName);
            }
        }
    }

    /**
     * Opens every declared {@code .jar} artifact and reads the binary name of each {@code .class}
     * entry via {@link ClassFileOwnName} — never {@code Class.forName} — rejecting the first one
     * found in a {@link ReservedPluginPackages reserved package}.
     */
    private static void requireNoReservedPackages(Path bundleDir, List<PluginArtifact> declaredArtifacts) {
        for (PluginArtifact artifact : declaredArtifacts) {
            if (!artifact.fileName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }
            Path jarPath = bundleDir.resolve(artifact.fileName());
            try (ZipFile zip = new ZipFile(jarPath.toFile())) {
                int scanned = 0;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".class")) {
                        continue;
                    }
                    if (++scanned > MAX_JAR_ENTRIES) {
                        throw PluginBundleRejection.tooManyEntries(artifact.fileName(), MAX_JAR_ENTRIES);
                    }
                    long declaredSize = entry.getSize();
                    if (declaredSize < 0 || declaredSize > ClassFileOwnName.MAX_CLASS_FILE_BYTES) {
                        throw PluginBundleRejection.bundleTooLarge(
                                Math.max(declaredSize, 0), ClassFileOwnName.MAX_CLASS_FILE_BYTES);
                    }
                    byte[] classBytes;
                    try (InputStream entryStream = zip.getInputStream(entry)) {
                        classBytes = entryStream.readNBytes(ClassFileOwnName.MAX_CLASS_FILE_BYTES + 1);
                    }
                    if (classBytes.length > ClassFileOwnName.MAX_CLASS_FILE_BYTES) {
                        // The declared size lied (or was absent, -1, guarded above); the actual stream
                        // is larger than the ceiling, so it is refused on the same terms as if the
                        // declared size had said so honestly.
                        throw PluginBundleRejection.bundleTooLarge(
                                classBytes.length, ClassFileOwnName.MAX_CLASS_FILE_BYTES);
                    }
                    String diagnosticName = artifact.fileName() + "!" + entry.getName();
                    String binaryName = ClassFileOwnName.read(classBytes, diagnosticName);
                    if (ReservedPluginPackages.isReserved(binaryName)) {
                        throw PluginBundleRejection.reservedPackage(binaryName);
                    }
                }
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
