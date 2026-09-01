package ai.ravenroot.extensions.ocr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;

/** One private invocation directory below the operator's explicit writable root. */
final class OcrWorkspace implements AutoCloseable {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path directory;
    private final Path input;

    private OcrWorkspace(Path directory, Path input) { this.directory = directory; this.input = input; }

    static OcrWorkspace create(OcrProfile profile, OcrImage image) throws IOException {
        Path configuredRoot = profile.temporaryRoot();
        if (Files.isSymbolicLink(configuredRoot) || !Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(configuredRoot)) {
            throw new IOException("OCR temporary root is unavailable");
        }
        Path root = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path directory = Files.createTempDirectory(root, "ravenroot-ocr-");
        setPermissions(directory, PRIVATE_DIRECTORY);
        Path realDirectory = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!root.equals(realDirectory.getParent())) {
            delete(realDirectory);
            throw new IOException("OCR invocation directory escaped its root");
        }
        Path input = realDirectory.resolve("input." + image.extension());
        Files.write(input, image.bytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        setPermissions(input, PRIVATE_FILE);
        if (Files.size(input) != image.bytes().length || Files.size(input) > profile.maxInputBytes()) {
            delete(realDirectory);
            throw new IOException("OCR input size changed before process start");
        }
        return new OcrWorkspace(realDirectory, input);
    }

    Path directory() { return directory; }
    Path input() { return input; }

    @Override public void close() { delete(directory); }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try { Files.setPosixFilePermissions(path, permissions); }
        catch (UnsupportedOperationException | IOException ignored) { }
    }

    private static void delete(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
