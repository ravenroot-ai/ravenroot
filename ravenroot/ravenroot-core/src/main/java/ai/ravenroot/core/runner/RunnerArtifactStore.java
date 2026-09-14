package ai.ravenroot.core.runner;

import ai.ravenroot.api.runner.RunnerArtifact;
import ai.ravenroot.api.runner.RunnerJob;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Immutable bounded artifact storage on an operator-owned volume. Paths are generated solely from
 * accepted identities; caller filenames and URLs are never resolved. The volume must not be mounted
 * into runner containers. Shared control-plane replicas require the same locking-capable volume.
 */
public final class RunnerArtifactStore {
    private final Path root;
    public RunnerArtifactStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (!this.root.equals(this.root.toRealPath())) throw new IllegalArgumentException("artifact root must not contain symbolic links");
    }

    public RunnerArtifact put(RunnerJob job, RunnerArtifact.Kind kind, InputStream source) throws IOException {
        Path directory = directory(job);
        Files.createDirectories(directory);
        requireSafe(directory);
        try (var channel = FileChannel.open(directory.resolve("artifact.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
            long stored = 0; int count = 0;
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    if (file.getFileName().toString().equals("artifact.lock")) continue;
                    requireSafe(file); stored = Math.addExact(stored, Files.size(file)); count++;
                }
            }
            if (count >= 128) throw new IllegalArgumentException("runner artifact count quota exceeded");
            boolean log = kind == RunnerArtifact.Kind.LOG || kind == RunnerArtifact.Kind.STDOUT || kind == RunnerArtifact.Kind.STDERR;
            long limit = job.authority().limits().artifactBytes() - stored;
            if (log) limit = Math.min(limit, job.authority().limits().logBytes());
            if (limit < 0) throw new IllegalArgumentException("runner artifact byte quota exceeded");
            Path temporary = Files.createTempFile(directory, "upload-", ".pending");
            try {
                var digest = digest(); long size = 0;
                try (var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[8192];
                    for (int length; (length = source.read(buffer)) != -1;) {
                        if (length > limit - size) throw new IllegalArgumentException("runner artifact byte quota exceeded");
                        output.write(buffer, 0, length); digest.update(buffer, 0, length); size += length;
                    }
                }
                var artifact = new RunnerArtifact(job.identity(), UUID.randomUUID(), kind,
                        HexFormat.of().formatHex(digest.digest()), size);
                Files.move(temporary, path(job, artifact), StandardCopyOption.ATOMIC_MOVE);
                return artifact;
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    /** Revalidates retained evidence, including its digest, before it can authorize a terminal report. */
    public void verify(RunnerJob job, RunnerArtifact artifact) throws IOException {
        if (!job.identity().equals(artifact.job())) throw new IllegalArgumentException("runner artifact scope mismatch");
        Path path = path(job, artifact); requireSafe(path);
        if (Files.size(path) != artifact.sizeBytes() || artifact.sizeBytes() > job.authority().limits().artifactBytes()) {
            throw new IllegalArgumentException("runner artifact size mismatch");
        }
        var digest = digest();
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            for (int size; (size = input.read(buffer)) != -1;) digest.update(buffer, 0, size);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) {
            throw new IllegalArgumentException("runner artifact digest mismatch");
        }
    }

    /** Caller must first authorize the job and select the reference from its accepted report. */
    public InputStream open(RunnerJob job, RunnerArtifact artifact) throws IOException {
        verify(job, artifact);
        return Files.newInputStream(path(job, artifact), LinkOption.NOFOLLOW_LINKS);
    }

    private Path directory(RunnerJob job) {
        return directory(job.identity());
    }
    private Path directory(ai.ravenroot.api.runner.RunnerJobIdentity id) {
        String tenant = HexFormat.of().formatHex(digest().digest(
                id.execution().tenantId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return root.resolve(tenant).resolve(id.execution().processInstanceId().toString()).resolve(id.runnerJobId().toString());
    }
    /** Called only after the control plane has verified terminal process retention. */
    public void removeRetained(RunnerJob job) throws IOException {
        Path directory = directory(job);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        requireSafe(directory);
        try (var channel = FileChannel.open(directory.resolve("artifact.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock();
             var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                requireSafe(file);
                String name = file.getFileName().toString();
                if (name.endsWith(".blob") || name.endsWith(".pending")) Files.delete(file);
            }
        }
        // Keep the zero-byte lock as a stable cross-process synchronization inode.
    }
    private Path path(RunnerJob job, RunnerArtifact artifact) {
        return directory(job).resolve(artifact.artifactId() + "." + artifact.kind() + "." + artifact.sha256() + ".blob");
    }
    private void requireSafe(Path path) throws IOException {
        if (!path.startsWith(root) || !path.equals(path.toRealPath())
                || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root)) {
            throw new IllegalArgumentException("unsafe artifact storage path");
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
