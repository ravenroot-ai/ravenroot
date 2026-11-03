package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class FileAuditTrailStreamingRecoveryTest {
    @TempDir Path directory;
    private static AuditEnvelope envelope() {
        return AuditEnvelope.of("acme", "issuer|USER|alice", AuditCategory.DECISION, "read",
                "resource", "r-1", AuditOutcome.ALLOWED, "allowed ".repeat(64), "request", Instant.EPOCH);
    }

    @Test void recoversAndReadsALogLargerThanTheAvailableHeap() throws Exception {
        var serialize = FileAuditTrail.class.getDeclaredMethod("serialize", AuditRecord.class);
        serialize.setAccessible(true);
        Path log = directory.resolve("YWNtZQ.audit.jsonl");
        AuditRecord record = AuditRecord.genesis(envelope(), Instant.EPOCH);
        try (var writer = Files.newBufferedWriter(log)) {
            for (int i = 0; i < 60000; i++) {
                if (i > 0) record = AuditRecord.chainedAfter(record, envelope(), Instant.EPOCH);
                writer.write((String) serialize.invoke(null, record)); writer.newLine();
            }
        }
        assertTrue(Files.size(log) > 32L * 1024 * 1024);
        Path output = directory.resolve("child.txt");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                getClass().getName(), directory.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "bounded-memory recovery must finish");
            assertEquals(0, child.exitValue(), Files.readString(output));
        } finally { child.destroyForcibly(); }
    }

    @Test void corruptionBeyondRequestedPageStillRefusesReadAndAppend() throws Exception {
        try (var trail = new FileAuditTrail(directory, Clock.systemUTC(), Duration.ofHours(24))) {
            trail.append(envelope());
        }
        Files.writeString(directory.resolve("YWNtZQ.audit.jsonl"), "invalid-record\n", StandardOpenOption.APPEND);
        try (var reopened = new FileAuditTrail(directory, Clock.systemUTC(), Duration.ofHours(24))) {
            assertThrows(AuditTrailException.class, () -> reopened.read("acme", 0, 1));
            assertThrows(AuditTrailException.class, () -> reopened.append(envelope()));
        }
    }

    public static void main(String[] args) {
        try (var trail = new FileAuditTrail(Path.of(args[0]), Clock.systemUTC(), Duration.ofHours(24))) {
            if (trail.head("acme").orElseThrow().sequence() != 60000) throw new AssertionError("head");
            if (trail.append(envelope()).sequence() != 60001) throw new AssertionError("append");
            if (trail.read("acme", 59999, 1).getFirst().sequence() != 60000) throw new AssertionError("page");
        }
    }
}
