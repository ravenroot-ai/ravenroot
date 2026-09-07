package ai.ravenroot.testkit.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/** Standalone synchronized CPU worker used only by the explicit hosted issue-138 measurement. */
final class CpuContentionWorker {

    private static final long HEARTBEAT_NANOS = Duration.ofMillis(100).toNanos();

    private CpuContentionWorker() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("expected START_FILE STOP_FILE HEARTBEAT_FILE");
        }
        Path start = Path.of(arguments[0]);
        Path stop = Path.of(arguments[1]);
        Path heartbeat = Path.of(arguments[2]);
        writeHeartbeat(heartbeat, "READY", 0L);
        while (!Files.exists(start)) {
            Thread.sleep(10L);
        }

        long iterations = 0L;
        long accumulator = 1L;
        long nextHeartbeat = System.nanoTime();
        while (!Files.exists(stop)) {
            accumulator = accumulator * 31L + 7L;
            iterations++;
            if (accumulator == Long.MIN_VALUE) {
                accumulator = 1L;
            }
            long now = System.nanoTime();
            if (now >= nextHeartbeat) {
                writeHeartbeat(heartbeat, "RUNNING", iterations);
                nextHeartbeat = now + HEARTBEAT_NANOS;
            }
        }
        writeHeartbeat(heartbeat, "STOPPED", iterations);
    }

    private static void writeHeartbeat(Path file, String state, long iterations) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, state + " " + iterations + " " + System.nanoTime() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
