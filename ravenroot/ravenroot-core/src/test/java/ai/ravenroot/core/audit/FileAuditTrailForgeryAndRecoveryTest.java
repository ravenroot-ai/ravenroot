package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.audit.AuditTrailException;
import ai.ravenroot.api.audit.ChainAnomaly;
import ai.ravenroot.api.audit.ChainVerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four SEC-13 defects, each driven against the durable adapter's
 * real on-disk bytes.
 *
 * <p>Three of them share a shape worth naming: a verifier that trusts a field an attacker controls is
 * not a verifier. The redaction flag was exactly that — stored on disk, outside the digest, and
 * believed by {@code verify()} as grounds to skip checking the record entirely.</p>
 */
class FileAuditTrailForgeryAndRecoveryTest {

    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TENANT = "acme";

    // Column positions in the pipe-delimited on-disk record, as FileAuditTrail.serialize writes them.
    private static final int ACTION = 5;
    private static final int SEQUENCE = 15;
    private static final int REDACTED = 20;
    private static final int REDACTED_AT_SECONDS = 21;
    private static final int REDACTED_AT_NANOS = 22;
    private static final int REDACTION_REASON = 23;
    private static final int REDACTED_BY = 24;

    @TempDir
    Path directory;

    private FileAuditTrail trail() {
        return new FileAuditTrail(directory, Clock.fixed(EPOCH, ZoneOffset.UTC), Duration.ofHours(24));
    }

    private static AuditEnvelope envelope(String action) {
        return AuditEnvelope.of(TENANT, "issuer|USER|alice", AuditCategory.DECISION, action,
                "resource", "r-1", AuditOutcome.ALLOWED, "policy allowed", UUID.randomUUID().toString(), EPOCH);
    }

    private Path logFile() throws IOException {
        return fileEndingWith(".audit.jsonl");
    }

    private Path headFile() throws IOException {
        return fileEndingWith(".audit.head");
    }

    private Path fileEndingWith(String suffix) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(p -> p.toString().endsWith(suffix)).findFirst()
                    .orElseThrow(() -> new AssertionError("no " + suffix + " file in " + directory));
        }
    }

    private List<String> lines() throws IOException {
        return new ArrayList<>(Files.readAllLines(logFile(), StandardCharsets.UTF_8));
    }

    private void writeLines(List<String> lines) throws IOException {
        Files.write(logFile(), lines, StandardCharsets.UTF_8);
    }

    private static String[] fieldsOf(String line) {
        String[] fields = line.split("\\|", -1);
        assertEquals(25, fields.length, "test assumption about the on-disk record shape");
        return fields;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    // ---- A forged redaction flag must not become an unauthenticated exemption ----------------------

    /**
     * The attack edits one line to alter content <em>and</em> set the flag that
     * exempts it from content verification, with the digest column byte-for-byte untouched and no
     * downstream record recomputed.
     *
     * <p>A verifier that trusts the asserted exemption returns {@code intact=true}. The record's own
     * stored digest still proves the original content, and nothing about the edit is subtle — that
     * verifier simply never looks.</p>
     */
    @Test
    void settingTheRedactionFlagOnDiskMustNotLaunderAContentEdit() throws IOException {
        AuditRecord forged;
        try (var trail = trail()) {
            trail.append(envelope("read"));
            trail.append(envelope("list"));
            forged = trail.append(envelope("authorize:EXECUTION_START"));
            trail.append(envelope("export"));
            trail.append(envelope("delete"));
            assertTrue(trail.verify(TENANT).intact(), "control: the chain is clean before the edit");
        }

        List<String> lines = lines();
        String[] fields = fieldsOf(lines.get(2));
        String originalDigest = fields[17];
        fields[ACTION] = b64("authorize:ARTIFACT_APPROVE");
        fields[REDACTED] = "1";
        fields[REDACTED_AT_SECONDS] = Long.toString(EPOCH.getEpochSecond());
        fields[REDACTED_AT_NANOS] = "0";
        fields[REDACTION_REASON] = b64("retention");
        fields[REDACTED_BY] = b64("issuer|USER|mallory");
        lines.set(2, String.join("|", fields));
        writeLines(lines);
        assertEquals(originalDigest, fieldsOf(lines().get(2))[17], "the digest column must stay untouched");

        try (var trail = trail()) {
            ChainVerificationResult result = trail.verify(TENANT);
            assertFalse(result.intact(),
                    "an attacker who can set the flag that disables verification has not been verified; "
                            + "the exemption must be bound to something they cannot assert");
            assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.UnaccountedRedaction u
                            && u.sequence() == forged.sequence()),
                    () -> "expected an UnaccountedRedaction at sequence " + forged.sequence()
                            + ": no ADMINISTRATION tombstone names it, so the redaction was never "
                            + "authorised through the trail's own API. Got: " + result.anomalies());
        }
    }

    /** The flag alone, with content untouched, is still an unaccounted redaction. */
    @Test
    void aRedactionFlagWithNoTombstoneIsReportedEvenWhenTheContentIsUnchanged() throws IOException {
        try (var trail = trail()) {
            trail.append(envelope("read"));
            trail.append(envelope("list"));
            trail.append(envelope("export"));
            assertTrue(trail.verify(TENANT).intact(), "control");
        }

        List<String> lines = lines();
        String[] fields = fieldsOf(lines.get(1));
        fields[REDACTED] = "1";
        fields[REDACTED_AT_SECONDS] = Long.toString(EPOCH.getEpochSecond());
        fields[REDACTED_AT_NANOS] = "0";
        fields[REDACTION_REASON] = b64("retention");
        fields[REDACTED_BY] = b64("issuer|USER|mallory");
        lines.set(1, String.join("|", fields));
        writeLines(lines);

        try (var trail = trail()) {
            ChainVerificationResult result = trail.verify(TENANT);
            assertFalse(result.intact());
            assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.UnaccountedRedaction u
                            && u.sequence() == 2), () -> String.valueOf(result.anomalies()));
        }
    }

    /** A redaction performed through the API is fully accounted for and verifies clean. */
    @Test
    void aRedactionPerformedThroughTheApiLeavesTheChainIntact() {
        try (var trail = trail()) {
            trail.append(envelope("read"));
            trail.append(envelope("list"));
            trail.append(envelope("export"));

            AuditRecord tombstone = trail.redact(TENANT, 2, 3, "retention", "issuer|USER|admin");
            assertEquals(AuditCategory.ADMINISTRATION, tombstone.envelope().category());

            ChainVerificationResult result = trail.verify(TENANT);
            assertTrue(result.intact(),
                    () -> "a redaction the trail itself performed names its own range in an "
                            + "ADMINISTRATION tombstone and must verify clean: " + result.anomalies());
        }
    }

    /**
     * The crash window inside {@code redact()} is benign when the tombstone is written first.
     *
     * <p>The tombstone is appended <em>before</em> the log is rewritten. A crash between the two
     * therefore leaves a tombstone naming a range whose records are not yet redacted — a state in which
     * every record still verifies against its own digest and the tombstone accounts for nothing that
     * happened. That must not report tamper. Had the original order been kept, the same crash would
     * have left redacted records with no tombstone, which is indistinguishable from the forgery above.
     * The state is reconstructed here by restoring the pre-redaction record lines and keeping the
     * tombstone, which is byte-for-byte what the interrupted sequence leaves behind.</p>
     */
    @Test
    void aCrashBetweenTheTombstoneAndTheRewriteIsNotReportedAsTamper() throws IOException {
        List<String> beforeRedaction;
        try (var trail = trail()) {
            trail.append(envelope("read"));
            trail.append(envelope("list"));
            trail.append(envelope("export"));
            beforeRedaction = lines();

            trail.redact(TENANT, 2, 3, "retention", "issuer|USER|admin");
        }

        List<String> afterRedaction = lines();
        assertEquals(4, afterRedaction.size(), "three records plus the tombstone");
        var crashState = new ArrayList<>(beforeRedaction);
        crashState.add(afterRedaction.get(3));
        writeLines(crashState);

        try (var trail = trail()) {
            ChainVerificationResult result = trail.verify(TENANT);
            assertTrue(result.intact(),
                    () -> "a tombstone whose rewrite never happened describes a redaction that did not "
                            + "occur; nothing is missing and nothing is forged: " + result.anomalies());
        }
    }

    // ---- Crash recovery must not raise a false tamper alarm -----------------------------------------

    /**
     * The documented crash window: the record is fsync'd, the process dies before the watermark
     * advances. The class Javadoc says this "a crash never causes" a mismatch worth reporting, and the
     * code compared the two sequences symmetrically anyway.
     */
    @Test
    void aWatermarkOneBehindTheLogIsOrdinaryCrashRecoveryAndNotTamper() throws IOException {
        AuditRecord fourth;
        try (var trail = trail()) {
            trail.append(envelope("a"));
            trail.append(envelope("b"));
            trail.append(envelope("c"));
            fourth = trail.append(envelope("d"));
            trail.append(envelope("e"));
        }

        // Roll the watermark back to record four: the log has five, exactly as it would after a crash
        // between appendLine() and writeHead(). Nothing is deleted and nothing is edited.
        Files.writeString(headFile(), fourth.sequence() + ":" + fourth.digest().hex(), StandardCharsets.UTF_8);

        try (var trail = trail()) {
            ChainVerificationResult result = trail.verify(TENANT);
            assertTrue(result.intact(),
                    () -> "the log is the source of truth for whether a write happened; a watermark "
                            + "behind it is the documented crash window, not a tamper: " + result.anomalies());
            assertEquals(5L, result.checkedThroughSequence());
        }
    }

    /** The control for the test above: the opposite direction is tail deletion and must still be caught. */
    @Test
    void aWatermarkAheadOfTheLogIsTailDeletionAndMustStillBeReported() throws IOException {
        try (var trail = trail()) {
            trail.append(envelope("a"));
            trail.append(envelope("b"));
            trail.append(envelope("c"));
        }

        List<String> lines = lines();
        lines.remove(2);
        writeLines(lines);

        try (var trail = trail()) {
            ChainVerificationResult result = trail.verify(TENANT);
            assertFalse(result.intact(), "the watermark still claims a record the log no longer has");
            assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.HeadMismatch h
                            && h.claimedSequence() == 3 && h.observedSequence() == 2),
                    () -> String.valueOf(result.anomalies()));
        }
    }

    /** A watermark whose digest disagrees with the record actually at that sequence is still tamper. */
    @Test
    void aWatermarkWhoseDigestDisagreesAtTheSameSequenceIsStillReported() throws IOException {
        AuditRecord first;
        try (var trail = trail()) {
            first = trail.append(envelope("a"));
            trail.append(envelope("b"));
        }

        // Claim sequence 2 but carry record 1's digest: a lower sequence would be the crash window, so
        // this is the case that proves the direction check did not simply stop looking.
        Files.writeString(headFile(), "2:" + first.digest().hex(), StandardCharsets.UTF_8);

        try (var trail = trail()) {
            ChainVerificationResult result = trail.verify(TENANT);
            assertFalse(result.intact());
            assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.HeadMismatch),
                    () -> String.valueOf(result.anomalies()));
        }
    }

    // ---- One corrupted byte must produce a typed result rather than brick the tenant ----------------

    /**
     * {@code verify()}'s contract says it "never throws for a tampered or gapped chain — that is the
     * report, not a failure of verification itself". A single unparseable field made it throw
     * {@code NumberFormatException} from three frames down.
     */
    @Test
    void aMalformedRecordIsReportedByVerifyRatherThanThrown() throws IOException {
        try (var trail = trail()) {
            trail.append(envelope("a"));
            trail.append(envelope("b"));
            trail.append(envelope("c"));
        }

        List<String> lines = lines();
        String[] fields = fieldsOf(lines.get(1));
        fields[SEQUENCE] = "not-a-number";
        lines.set(1, String.join("|", fields));
        writeLines(lines);

        try (var trail = trail()) {
            ChainVerificationResult result = trail.verify(TENANT);
            assertFalse(result.intact(), "an unreadable record is a reportable defect, not a crash");
            assertTrue(result.anomalies().stream().anyMatch(a -> a instanceof ChainAnomaly.MalformedRecord),
                    () -> "expected a MalformedRecord anomaly: " + result.anomalies());
        }
    }

    /** A corrupted byte must not leave the tenant permanently unable to append, untyped. */
    @Test
    void aMalformedRecordFailsAppendWithATypedFailureRatherThanARawParseError() throws IOException {
        try (var trail = trail()) {
            trail.append(envelope("a"));
            trail.append(envelope("b"));
        }

        List<String> lines = lines();
        String[] fields = fieldsOf(lines.get(0));
        fields[SEQUENCE] = "not-a-number";
        lines.set(0, String.join("|", fields));
        writeLines(lines);

        try (var trail = trail()) {
            AuditTrailException thrown = assertThrows(AuditTrailException.class,
                    () -> trail.append(envelope("c")),
                    "a raw NumberFormatException escaping the port tells a caller nothing it can act on");
            assertInstanceOf(ai.ravenroot.api.audit.AuditTrailFailure.class, thrown.reason());
        }
    }

    // ---- Append must not reread the entire log ------------------------------------------------------

    /**
     * That {@code append()} no longer re-reads the log, proved structurally rather than by a stopwatch.
     *
     * <p>A timing assertion was tried first and rejected: each append costs two fsyncs, roughly ten
     * milliseconds, which swamps the parse cost being measured and would have made the test a
     * flake-generator that passes for the wrong reason. This is deterministic instead. Damaging a
     * record the adapter has already walked past makes the next append succeed if and only if it does
     * not go back and read it. A whole-log reread raises {@code NumberFormatException} from
     * {@code readLog} on the damaged record.</p>
     *
     * <p>That the adapter appends onto a chain it cannot currently re-parse is deliberate, not an
     * oversight: noticing would require the whole-log read this defect was about, and {@code verify()}
     * is the operation whose job is to notice. The complementary case — a corrupt log with no cached
     * head, where the chain's end genuinely cannot be established — refuses with a typed failure, and
     * is covered above.</p>
     */
    @Test
    void appendDoesNotRereadTheWholeLogOnEveryWrite() throws IOException {
        try (var trail = trail()) {
            trail.append(envelope("a"));
            trail.append(envelope("b"));
            trail.append(envelope("c"));

            List<String> lines = lines();
            String[] fields = fieldsOf(lines.get(1));
            fields[SEQUENCE] = "not-a-number";
            lines.set(1, String.join("|", fields));
            writeLines(lines);

            AuditRecord fourth = trail.append(envelope("d"));
            assertEquals(4L, fourth.sequence(),
                    "the head came from memory; an append that re-parsed the log would have died on "
                            + "the damaged record two positions back");
        }
    }
}
