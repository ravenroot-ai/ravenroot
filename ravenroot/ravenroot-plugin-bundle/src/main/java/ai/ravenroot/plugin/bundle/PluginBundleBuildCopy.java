package ai.ravenroot.plugin.bundle;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * The OCI build's own front door onto {@link PluginBundleValidator} (PLAT-12): copies exactly
 * the subdirectories of a source directory that are valid plugin bundles into a destination
 * directory, and nothing else.
 *
 * <h2>What counts as a bundle candidate</h2>
 * <p>Only an immediate subdirectory of {@code sourceDir} that directly contains
 * {@value PluginBundleValidator#MANIFEST_FILE_NAME}. A loose top-level file (a README, a
 * {@code .gitkeep}) is not a subdirectory and is silently never a candidate — that is not the "no
 * silent fallback" rule being broken, because a loose file was never claiming to be a bundle. A
 * subdirectory <em>without</em> a manifest is treated the same way, for the same reason.</p>
 *
 * <h2>What happens once something does claim to be a bundle</h2>
 * <p>Every candidate is validated with {@link PluginBundleValidator#validate(Path)}. A candidate that
 * fails fails the whole build: {@link #main(String[])} exits non-zero with the rejection's sanitised
 * message and reason on stderr, copying nothing. An invalid bundle sitting in the convention
 * directory is a build error, not something silently excluded from the image — silently excluding it
 * would be exactly the silent fallback PLAT-12 rules out, applied to build time instead of
 * startup.</p>
 *
 * <h2>The empty case is load-bearing</h2>
 * <p>When {@code sourceDir} does not exist, or exists with zero bundle candidates, this class creates
 * {@code destDir} as an empty directory and returns normally without touching anything else. That
 * empty directory is what the runtime image build stage uses to decide whether to create
 * {@code /opt/ravenroot/plugins} in the final image at all — see the Dockerfile. Nothing this class
 * does can make an empty source directory produce anything other than an empty destination.</p>
 */
public final class PluginBundleBuildCopy {

    private PluginBundleBuildCopy() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: PluginBundleBuildCopy <sourceDir> <destDir>");
            System.exit(2);
        }
        Path sourceDir = Path.of(args[0]);
        Path destDir = Path.of(args[1]);
        try {
            List<String> copied = run(sourceDir, destDir);
            for (String id : copied) {
                System.out.println("plugin bundle copied: " + id);
            }
            if (copied.isEmpty()) {
                System.out.println("no plugin bundles found under " + sourceDir + "; destination left empty");
            }
        } catch (PluginBundleException rejection) {
            System.err.println("plugin bundle rejected: " + rejection.getMessage()
                    + " (reason=" + rejection.reason() + ", incident=" + rejection.incidentId() + ")");
            System.exit(1);
        } catch (IOException failed) {
            System.err.println("plugin bundle build copy failed: " + failed.getMessage());
            System.exit(1);
        }
    }

    /**
     * Validates and copies every bundle candidate. Returns the copied bundle ids, in directory-listing
     * order. Creates {@code destDir} (possibly empty) unconditionally, and creates nothing else when
     * there are no candidates.
     *
     * @throws PluginBundleException on the first invalid candidate; nothing has been copied at that point
     * @throws IOException           on a filesystem failure unrelated to bundle validity
     */
    static List<String> run(Path sourceDir, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        if (!Files.isDirectory(sourceDir)) {
            return List.of();
        }

        var candidates = new ArrayList<Path>();
        try (var listing = Files.list(sourceDir)) {
            listing.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME),
                            LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .forEach(candidates::add);
        }

        // Validate every candidate before copying any of them: a build must not leave a partially
        // populated plugins directory behind after failing on the second of three bundles.
        for (Path candidate : candidates) {
            PluginBundleValidator.validate(candidate);
        }

        var copiedIds = new ArrayList<String>(candidates.size());
        for (Path candidate : candidates) {
            String name = candidate.getFileName().toString();
            copyTree(candidate, destDir.resolve(name));
            copiedIds.add(name);
        }
        return List.copyOf(copiedIds);
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                CopyOption[] options = {StandardCopyOption.COPY_ATTRIBUTES};
                Files.copy(file, destination.resolve(source.relativize(file)), options);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
