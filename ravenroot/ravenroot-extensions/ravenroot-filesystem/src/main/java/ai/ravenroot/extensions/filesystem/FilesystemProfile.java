package ai.ravenroot.extensions.filesystem;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Immutable operator-owned authority. Graph content can select and tighten it, never expand it. */
public final class FilesystemProfile {
    private final String name;
    private final Path root;
    private final Object rootFileKey;
    private final boolean read;
    private final boolean write;
    private final Set<String> allowedPaths;
    private final List<PathMatcher> matchers;
    private final long maxBytes;
    private final int maxConcurrency;
    private final Duration timeout;

    public FilesystemProfile(String name, Path root, boolean read, boolean write, Set<String> allowedPaths,
                             long maxBytes, int maxConcurrency, Duration timeout) {
        if (!safeName(name) || root == null || !root.isAbsolute() || Files.isSymbolicLink(root)
                || !Files.isDirectory(root) || (!read && !write) || allowedPaths == null || allowedPaths.isEmpty()
                || maxBytes < 1 || maxBytes > 67_108_864L || maxConcurrency < 1 || maxConcurrency > 1024
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("invalid filesystem profile");
        }
        this.name = name;
        try {
            this.root = root.toRealPath();
            this.rootFileKey = Files.readAttributes(this.root, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("invalid filesystem profile", unavailable);
        }
        this.read = read;
        this.write = write;
        this.allowedPaths = Set.copyOf(allowedPaths);
        FileSystem fileSystem = this.root.getFileSystem();
        try {
            this.matchers = this.allowedPaths.stream().map(pattern -> {
                FilesystemPaths.parsePattern(pattern);
                return fileSystem.getPathMatcher("glob:" + pattern);
            }).toList();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid filesystem profile", invalid);
        }
        this.maxBytes = maxBytes;
        this.maxConcurrency = maxConcurrency;
        this.timeout = timeout;
    }

    public String name() { return name; }
    public Path root() { return root; }
    Object rootFileKey() { return rootFileKey; }
    public boolean read() { return read; }
    public boolean write() { return write; }
    public Set<String> allowedPaths() { return allowedPaths; }
    public long maxBytes() { return maxBytes; }
    public int maxConcurrency() { return maxConcurrency; }
    public Duration timeout() { return timeout; }

    boolean permits(Path relative) { return matchers.stream().anyMatch(matcher -> matcher.matches(relative)); }

    private static boolean safeName(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }
}
