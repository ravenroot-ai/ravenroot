package ai.ravenroot.extensions.gitworkspace;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;

/** Immutable operator authority; graph content can select this profile but cannot widen it. */
public record GitWorkspaceProfile(String tenant, String name, Path root, String remote,
                                  String baseRef, String issueRefPrefix, Path gitExecutable,
                                  String objectFormat, String credentialRef, String credentialUsername,
                                  Duration deadline, int maxConcurrency, int maxOutputBytes,
                                  int historyScanLimit) {
    public GitWorkspaceProfile {
        if (!identifier(tenant) || !identifier(name) || root == null || !root.isAbsolute()
                || Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || gitExecutable == null || !gitExecutable.isAbsolute() || Files.isSymbolicLink(gitExecutable)
                || !Files.isRegularFile(gitExecutable, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(gitExecutable) || !safeRef(baseRef) || !safeRefPrefix(issueRefPrefix)
                || !("sha1".equals(objectFormat) || "sha256".equals(objectFormat))
                || deadline == null || deadline.compareTo(Duration.ofMillis(100)) < 0
                || deadline.compareTo(Duration.ofMinutes(5)) > 0 || maxConcurrency < 1 || maxConcurrency > 64
                || maxOutputBytes < 1024 || maxOutputBytes > 1_048_576
                || historyScanLimit < 1 || historyScanLimit > 10_000
                || !credentialShape(credentialRef, credentialUsername)) {
            throw new IllegalArgumentException("invalid Git workspace profile");
        }
        try {
            root = root.toRealPath();
            Path executableParent = gitExecutable.getParent().toRealPath();
            gitExecutable = executableParent.resolve(gitExecutable.getFileName()).normalize();
            if (Files.isSymbolicLink(gitExecutable)
                    || !Files.isRegularFile(gitExecutable, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.io.IOException();
            }
        } catch (java.io.IOException unavailable) {
            throw new IllegalArgumentException("invalid Git workspace profile", unavailable);
        }
        remote = validateRemote(remote, credentialRef != null);
    }

    private static String validateRemote(String value, boolean credentialled) {
        if (value == null || value.length() > 2048 || value.isBlank()
                || value.codePoints().anyMatch(cp -> cp <= 0x20 || cp == 0x7f)) {
            throw new IllegalArgumentException("invalid Git workspace profile");
        }
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid Git workspace profile");
            }
            if ("https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank()) {
                return uri.normalize().toASCIIString();
            }
            if (!credentialled && "file".equals(uri.getScheme())) {
                Path target = Path.of(uri);
                if (!target.isAbsolute() || Files.isSymbolicLink(target)
                        || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("invalid Git workspace profile");
                }
                return target.toRealPath().toUri().toASCIIString();
            }
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid Git workspace profile", invalid);
        }
        throw new IllegalArgumentException("invalid Git workspace profile");
    }

    static boolean safeRef(String value) {
        if (value == null || !value.startsWith("refs/heads/") || value.length() > 255
                || value.endsWith("/") || value.endsWith(".") || value.contains("..")
                || value.contains("@{") || value.contains("//") || value.endsWith(".lock")) return false;
        return value.codePoints().allMatch(cp -> cp > 0x20 && cp < 0x7f
                && "~^:?*[\\".indexOf(cp) < 0);
    }

    private static boolean safeRefPrefix(String value) {
        return value != null && value.endsWith("/") && safeRef(value + "x");
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    private static boolean credentialShape(String reference, String username) {
        if (reference == null) return username == null;
        return !reference.isBlank() && reference.length() <= 256
                && reference.codePoints().allMatch(cp -> cp > 0x20 && cp < 0x7f)
                && username != null && !username.isBlank() && username.length() <= 256
                && username.codePoints().allMatch(cp -> cp > 0x20 && cp < 0x7f);
    }
}
