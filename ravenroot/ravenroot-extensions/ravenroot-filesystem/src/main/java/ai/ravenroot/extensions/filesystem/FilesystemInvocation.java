package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.node.NodeConfiguration;

import java.time.Duration;

record FilesystemInvocation(FilesystemProfile profile, FilesystemPaths.Parsed path, long maxBytes,
                            Duration timeout, String encoding) {
    static FilesystemInvocation resolve(NodeConfiguration configuration, FilesystemRuntime runtime,
                                        String tenant, boolean write) {
        String profileName = configuration.requiredProperty("filesystemProfile");
        FilesystemProfile profile;
        try {
            profile = runtime.profiles.resolve(tenant, profileName).orElseThrow(() ->
                    FilesystemNodeException.of(FilesystemNodeException.Reason.PROFILE_UNAVAILABLE));
        } catch (FilesystemNodeException typed) {
            throw typed;
        } catch (RuntimeException unavailable) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.PROFILE_UNAVAILABLE);
        }
        if (write ? !profile.write() : !profile.read()) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.AUTHORITY_REFUSED);
        }
        FilesystemPaths.Parsed path = FilesystemPaths.parse(profile.root(), configuration.requiredProperty("path"));
        if (!profile.permits(path.relative())) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.AUTHORITY_REFUSED);
        }
        long maxBytes = tightenLong(configuration.property("maxBytes").orElse(null), profile.maxBytes());
        long timeoutMillis = tightenLong(configuration.property("deadlineMs").orElse(null), profile.timeout().toMillis());
        String encoding = configuration.property("encoding", "utf-8");
        if (!encoding.equals("utf-8") && !encoding.equals("base64")) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT);
        }
        return new FilesystemInvocation(profile, path, maxBytes, Duration.ofMillis(timeoutMillis), encoding);
    }

    Duration remainingSince(long startedNanos) {
        long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        long remaining = timeout.toNanos() - elapsed;
        if (remaining <= 0L) throw FilesystemNodeException.of(FilesystemNodeException.Reason.TIMEOUT);
        return Duration.ofNanos(remaining);
    }

    private static long tightenLong(String raw, long ceiling) {
        if (raw == null) return ceiling;
        try {
            long value = Long.parseLong(raw);
            if (value < 1 || value > ceiling) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT);
        }
    }
}
