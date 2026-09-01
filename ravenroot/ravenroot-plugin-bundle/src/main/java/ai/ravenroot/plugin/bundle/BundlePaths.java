package ai.ravenroot.plugin.bundle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Path safety for plugin bundle contents (PLAT-12).
 *
 * <h2>Canonicalize, then compare a prefix — never string-match</h2>
 * <p>A declared artifact name is already constrained to a bare filename by {@link PluginArtifact}'s
 * own constructor, which removes {@code ..} and path separators before this class is ever reached.
 * This class exists for the check that constraint cannot make: a filename that is syntactically bare
 * can still be a symbolic link whose <em>target</em> resolves outside the bundle. Detecting that
 * requires resolving the real filesystem path and comparing it against the bundle root's real path —
 * string-matching {@code ..} in a name says nothing about where a symlink actually points.</p>
 */
final class BundlePaths {

    private BundlePaths() {
    }

    /**
     * Resolves {@code fileName} directly inside {@code bundleRoot} and verifies the real (symlink-
     * resolved) path is still contained in the bundle root's real path.
     *
     * @throws PluginBundleException when the file is absent, or resolves outside the bundle
     */
    static Path resolveWithinBundle(Path bundleRoot, String fileName) {
        Path candidate = bundleRoot.resolve(fileName);
        if (!Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw PluginBundleRejection.artifactMissing(fileName);
        }
        Path realRoot = realPath(bundleRoot);
        Path realCandidate = realPath(candidate);
        if (!realCandidate.startsWith(realRoot)) {
            throw Files.isSymbolicLink(candidate)
                    ? PluginBundleRejection.symlinkEscapesBundle(fileName)
                    : PluginBundleRejection.pathEscapesBundle(fileName);
        }
        return candidate;
    }

    /**
     * Every regular file actually present under {@code bundleRoot}, as filenames relative to it.
     *
     * <p>Used to compute the undeclared-file set: what is on disk, independent of what the manifest
     * claims. Any symlink encountered — whether it resolves inside or outside the root — is reported
     * as its link path rather than followed, because a bundle is defined to contain only its own
     * regular files; a symlink is either an escape attempt (rejected elsewhere by this class) or, at
     * best, an undeclared entry the manifest never named.</p>
     */
    static List<String> listRegularFiles(Path bundleRoot) {
        Path realRoot = realPath(bundleRoot);
        var names = new ArrayList<String>();
        try (Stream<Path> walk = Files.walk(bundleRoot)) {
            walk.filter(path -> !path.equals(bundleRoot)).forEach(path -> {
                if (Files.isSymbolicLink(path)) {
                    Path realTarget = realPath(path);
                    if (!realTarget.startsWith(realRoot)) {
                        throw PluginBundleRejection.symlinkEscapesBundle(bundleRoot.relativize(path).toString());
                    }
                    // A symlink that happens to resolve inside the bundle is still not a regular file
                    // the manifest can declare; it surfaces as an undeclared entry rather than being
                    // silently followed and copied under a different name.
                    names.add(bundleRoot.relativize(path).toString());
                } else if (Files.isRegularFile(path)) {
                    names.add(bundleRoot.relativize(path).toString());
                }
            });
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        return List.copyOf(names);
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException failed) {
            throw PluginBundleRejection.artifactMissing(path.getFileName() == null
                    ? path.toString() : path.getFileName().toString());
        }
    }
}
