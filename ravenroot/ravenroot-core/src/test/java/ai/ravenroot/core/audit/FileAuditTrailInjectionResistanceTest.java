package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.persistence.OpaquePayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the structural-immunity claim rather than merely asserting it.
 * {@code FileAuditTrail} Base64-encodes every {@link AuditEnvelope} string field before it reaches the
 * persisted, line-oriented log, so no field's own content -- including a tab, a real newline, or a
 * sub-{@code 0x20} control character -- can be confused with the {@code '|'} delimiter or a line
 * boundary. This is verified here the same way the JSON-lines sinks are: append a record whose fields
 * carry the canary, append an ordinary record after it, and confirm exactly two records read back --
 * not three or more from a line the canary silently split, and not one from a line it silently merged
 * with its neighbour.
 */
class FileAuditTrailInjectionResistanceTest {
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    /** Built from code points rather than typed literally, so this source file carries no raw control byte. */
    private static final String CANARY = "before" + '\t' + "middle" + '\n' + "ctrl" + (char) 1 + "after";

    @TempDir
    Path directory;

    @Test
    void aRecordCarryingTheCanaryInEveryFieldSurvivesAsExactlyOneRecordAmongItsNeighbours() {
        try (var trail = new FileAuditTrail(directory, Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24))) {
            trail.append(AuditEnvelope.of("tenant-a", CANARY, AuditCategory.ACCESS, CANARY, CANARY, CANARY,
                    AuditOutcome.DENIED, CANARY, "req-1", EPOCH,
                    OpaquePayload.of(CANARY.getBytes(StandardCharsets.UTF_8), "text/plain")));
            trail.append(AuditEnvelope.of("tenant-a", "alice", AuditCategory.ACCESS, "execution.completed",
                    "execution", "e-2", AuditOutcome.ALLOWED, "ok", "req-2", EPOCH));

            List<AuditRecord> records = trail.read("tenant-a", 0, 10);

            assertEquals(2, records.size(),
                    () -> "the canary record must not have split into more lines, or merged with its "
                            + "neighbour into fewer: " + records.size() + " records read back");
            assertEquals(CANARY, records.get(0).envelope().principal(),
                    "the canary must round-trip byte-for-byte through Base64, not merely survive as "
                            + "SOME value");
            assertEquals(CANARY, records.get(0).envelope().action());
            assertEquals(CANARY, new String(records.get(0).envelope().detail().bytes(), StandardCharsets.UTF_8));
            assertEquals("e-2", records.get(1).envelope().resourceId(),
                    "the record after the canary must read back intact, proving the canary did not "
                            + "corrupt the line boundary between the two");

            var verified = trail.verify("tenant-a");
            assertEquals(true, verified.intact(), () -> String.valueOf(verified.anomalies()));
        }
    }
}
