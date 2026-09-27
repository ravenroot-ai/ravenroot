package ai.ravenroot.server;

import java.nio.file.*;
import java.time.Instant;

/** Expiring, non-secret worker readiness; stale files never make a restarted manager ready. */
public final class RunnerReadinessMain {
    private RunnerReadinessMain() { }
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("one operator readiness path required");
        try (var input = Files.newInputStream(Path.of(arguments[0]), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(65);
            if (bytes.length > 64 || !Instant.now().isBefore(Instant.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))))
                throw new IllegalStateException("runner readiness expired");
        }
    }
    static java.util.function.Consumer<Boolean> sink(Path file, java.time.Duration ttl) {
        return ready -> {
            try {
                if (!ready) { Files.deleteIfExists(file); return; }
                Path temporary = Files.createTempFile(file.getParent(), "runner-ready-", ".pending");
                try {
                    Files.writeString(temporary, Instant.now().plus(ttl).toString());
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException("runner readiness unavailable", failure); }
        };
    }
}
