package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.audit.ChainAnomaly;
import ai.ravenroot.api.audit.ChainVerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-13 evidence from an independent verifier ({@link ai.ravenroot.api.audit.AuditTrail#verify}):
 * detects and reports both a tamper and a gap, produced here by editing the durable adapter's on-disk
 * bytes directly — the same act an attacker with filesystem access to the audit store would perform,
 * bypassing {@link FileAuditTrail}'s own API entirely rather than using a testkit backdoor.
 *
 * <p>Each mutation test starts from an unmutated, freshly-verified-intact chain (the control), applies
 * exactly one change to the smallest point that can be wrong alone, and shows {@code verify()} reports
 * it. None of these three scenarios overlap: content-only tamper, middle-record deletion and
 * tail-record deletion each exercise a different branch of {@code ChainWalker}/{@code verify}.</p>
 */
class FileAuditTrailTamperAndGapTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    @TempDir
    Path directory;

    private static AuditEnvelope envelope(String action) {
        return AuditEnvelope.of(TENANT, "issuer|USER|alice", AuditCategory.DECISION, action,
                "resource", "r-1", AuditOutcome.ALLOWED, "policy allowed", UUID.randomUUID().toString(), EPOCH);
    }

    private Path logFile() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.toString().endsWith(".audit.jsonl")).findFirst()
                    .orElseThrow(() -> new AssertionError("no audit log file found in " + directory));
        }
    }

    private Path headFile() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.toString().endsWith(".audit.head")).findFirst()
                    .orElseThrow(() -> new AssertionError("no audit head file found in " + directory));
        }
    }

    @Test
    void contentTamperOnAStoredRecordIsDetectedAndReportedWithoutDisturbingTheGapCheck() throws IOException {
        var trail = new FileAuditTrail(directory, Clock.fixed(EPOCH, ZoneOffset.UTC), java.time.Duration.ofHours(24));
        trail.append(envelope("read"));
        AuditRecord tampered = trail.append(envelope("delete"));
        trail.append(envelope("export"));

        // Control: the chain is intact before any mutation.
        assertTrue(trail.verify(TENANT).intact(), "control must be clean before mutating anything");

        // Attack: rewrite the "action" field of the middle record's line on disk, in place, leaving its
        // stored digest, previousDigest and every other field untouched — the minimal single-field
        // content mutation, which is exactly what a raw content edit without recomputing anything looks
        // like.
        List<String> lines = Files.readAllLines(logFile(), StandardCharsets.UTF_8);
        String[] fields = lines.get(1).split("\\|", -1);
        assertEquals(25, fields.length, "test assumption about the on-disk record shape");
        String forgedAction = Base64.getEncoder().encodeToString("approve".getBytes(StandardCharsets.UTF_8));
        assertFalse(forgedAction.equals(fields[5]), "the forged action must actually differ from the original");
        fields[5] = forgedAction;
        lines.set(1, String.join("|", fields));
        Files.write(logFile(), lines, StandardCharsets.UTF_8);

        ChainVerificationResult result = trail.verify(TENANT);
        assertFalse(result.intact(), "a single-field content edit must be caught");
        assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.TamperedContent tc
                        && tc.sequence() == tampered.sequence()),
                () -> "expected a TamperedContent anomaly at sequence " + tampered.sequence()
                        + " but got: " + result.anomalies());
        // The digest and previousDigest columns were untouched, so this specific mutation must not be
        // misreported as a linkage break or a gap — that would be the wrong diagnosis for what happened.
        assertTrue(result.anomalies().stream().noneMatch(a -> a instanceof ChainAnomaly.Gap
                        || a instanceof ChainAnomaly.TamperedLinkage),
                () -> "a pure content edit must not also be reported as a gap or a linkage break: "
                        + result.anomalies());
        trail.close();
    }

    @Test
    void deletingAMiddleRecordProducesAReportedGapAndBreaksTheForwardLink() throws IOException {
        var trail = new FileAuditTrail(directory, Clock.fixed(EPOCH, ZoneOffset.UTC), java.time.Duration.ofHours(24));
        trail.append(envelope("read"));
        trail.append(envelope("delete"));
        AuditRecord third = trail.append(envelope("export"));
        assertTrue(trail.verify(TENANT).intact(), "control must be clean before mutating anything");

        // Attack: remove the middle line entirely, as a direct-storage delete would. The head watermark
        // is untouched because record 3, the true tail, is still physically present and unaffected.
        List<String> lines = Files.readAllLines(logFile(), StandardCharsets.UTF_8);
        lines.remove(1);
        Files.write(logFile(), lines, StandardCharsets.UTF_8);

        ChainVerificationResult result = trail.verify(TENANT);
        assertFalse(result.intact());
        assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.Gap gap
                        && gap.toSequenceInclusive() == 2),
                () -> "expected a Gap covering the missing sequence 2: " + result.anomalies());
        assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.TamperedLinkage linkage
                        && linkage.sequence() == third.sequence()),
                () -> "record 3's previousDigest no longer matches what now precedes it: " + result.anomalies());
        trail.close();
    }

    @Test
    void deletingTheTailLeavesNoBrokenForwardLinkButTheWatermarkCatchesIt() throws IOException {
        var trail = new FileAuditTrail(directory, Clock.fixed(EPOCH, ZoneOffset.UTC), java.time.Duration.ofHours(24));
        trail.append(envelope("read"));
        trail.append(envelope("delete"));
        AuditRecord third = trail.append(envelope("export"));
        assertTrue(trail.verify(TENANT).intact(), "control must be clean before mutating anything");

        // Attack: delete the chain's own tail. No surviving record's previousDigest points at the
        // deleted one, so a check that only walked forward links would see nothing wrong — this is
        // exactly the case ai.ravenroot.api.audit.AuditTrail's Javadoc names as needing the separate
        // watermark file, which this attack deliberately leaves untouched.
        List<String> lines = Files.readAllLines(logFile(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        lines.remove(2);
        Files.write(logFile(), lines, StandardCharsets.UTF_8);
        assertTrue(Files.exists(headFile()), "the watermark file must still claim the deleted record");

        ChainVerificationResult result = trail.verify(TENANT);
        assertFalse(result.intact(), "tail deletion must be caught even though no forward link breaks");
        assertTrue(result.anomalies().stream().noneMatch(a -> a instanceof ChainAnomaly.Gap
                        || a instanceof ChainAnomaly.TamperedLinkage),
                () -> "with the tail gone there is no surviving successor to notice a broken link, which is "
                        + "exactly why this case needs the watermark rather than the chain alone: "
                        + result.anomalies());
        Optional<ChainAnomaly.HeadMismatch> headMismatch = result.anomalies().stream()
                .filter(ChainAnomaly.HeadMismatch.class::isInstance).map(ChainAnomaly.HeadMismatch.class::cast)
                .findFirst();
        assertTrue(headMismatch.isPresent(), () -> "expected a HeadMismatch: " + result.anomalies());
        assertEquals(third.sequence(), headMismatch.get().claimedSequence());
        assertEquals(third.digest(), headMismatch.get().claimedDigest());
        assertEquals(2L, headMismatch.get().observedSequence());
        trail.close();
    }
}
