package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCapability;
import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditEnvelope;
import ai.ravenroot.api.audit.AuditOutcome;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.audit.AuditRetentionPolicy;
import ai.ravenroot.api.audit.AuditTrail;
import ai.ravenroot.api.audit.AuditTrailException;
import ai.ravenroot.api.audit.AuditTrailFailure;
import ai.ravenroot.api.audit.ChainAnomaly;
import ai.ravenroot.api.audit.ChainVerificationResult;
import ai.ravenroot.api.persistence.EventDigest;
import ai.ravenroot.api.persistence.OpaquePayload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable {@link AuditTrail} adapter: one append-only, fsync'd, line-oriented log file per tenant,
 * plus a separate fsync'd watermark file per tenant recording the chain head (SEC-13, ADR 0013).
 *
 * <h2>Why the watermark is a second, separate file</h2>
 * <p>If the head pointer lived inside the same file as the records, an actor able to truncate that
 * file could truncate the pointer along with the tail it deletes, and nothing would disagree. Keeping
 * it in a distinct file does not defend against an actor who deliberately edits both — see
 * {@link AuditTrail}'s class Javadoc for what this design does and does not defend against — but it
 * does mean a deletion of the log's tail that does not also patch the watermark is caught by
 * {@link #verify}, which is the realistic shape of a careless or partial tamper and exactly what this
 * class's own tests exercise by editing the on-disk files directly.</p>
 *
 * <h2>Ordering: write the log, then advance the watermark</h2>
 * <p>Exactly {@code OutboxPublisher}'s "deliver, then advance" rule (ADR 0011 section 6): the log line
 * is written and fsync'd first. If the process dies between that and advancing the watermark, the next
 * open finds a log one record ahead of its watermark — never data loss, and {@link #append} simply
 * catches the watermark up to the log's own last record the next time it runs, because the log itself,
 * not the watermark, is the source of truth for "did this write happen". The watermark's only job is
 * to notice when the log has fewer records than something once claimed, which a crash never causes.</p>
 *
 * <p><strong>The comparison is directional.</strong> A watermark <em>behind</em> the log is the crash
 * window this ordering deliberately creates, and
 * reporting it as tamper turns every unclean shutdown into a security alert — the failure mode where
 * an alarm that cries wolf on ordinary restarts is the one nobody reads when it finally means
 * something. A watermark <em>ahead</em> of the log is tail deletion and is exactly what the second file
 * exists to catch. The benign direction is still not taken on trust: the record the watermark names
 * must actually be in the log, carrying the digest it claims.</p>
 *
 * <h2>The head is cached, because reading it cost the whole log</h2>
 * <p>{@link #append} once re-read, re-parsed and Base64-decoded every prior record to find the record
 * to chain onto, making each append linear in the chain's length and a run of appends quadratic. That
 * is not merely slow: rate-limit rejections are audited <em>before</em> authentication resolves an
 * identity, so an unauthenticated caller drives both the growth and the per-append cost, and
 * {@code DefaultAuthorizationService.decide} fails closed when the sink throws. The end state of the
 * curve is that nobody can be authorised at all. The head is now held in memory per tenant, seeded once
 * from the log and thereafter maintained under the same lock that serialises appends.</p>
 */
public final class FileAuditTrail implements AuditTrail {
    private static final String FIELD_DELIMITER = "|";

    private final Path directory;
    private final Clock clock;
    private final Duration minimumRetention;
    private final ConcurrentHashMap<String, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();
    /** Chain head per tenant, seeded from the log on first touch and maintained under the tenant lock. */
    private final ConcurrentHashMap<String, AuditRecord> headCache = new ConcurrentHashMap<>();

    public FileAuditTrail(Path directory, Clock clock, Duration minimumRetention) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minimumRetention = Objects.requireNonNull(minimumRetention, "minimumRetention");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new AuditTrailException(new AuditTrailFailure.Unavailable(
                    "cannot create audit directory " + directory + ": " + e.getMessage()));
        }
    }

    @Override
    public AuditRecord append(AuditEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        ReentrantLock tenantLock = lockFor(envelope.tenantId());
        tenantLock.lock();
        try {
            AuditRecord previous = cachedHead(envelope.tenantId());
            Instant now = clock.instant();
            AuditRecord record = previous == null
                    ? AuditRecord.genesis(envelope, now)
                    : AuditRecord.chainedAfter(previous, envelope, now);
            appendLine(envelope.tenantId(), serialize(record));
            writeHead(envelope.tenantId(), record.sequence(), record.digest());
            headCache.put(envelope.tenantId(), record);
            return record;
        } catch (IOException e) {
            // The cached head is dropped rather than kept: the line may or may not have reached the
            // file, so the next call must re-derive the truth from the log instead of chaining onto a
            // record whose position is no longer certain.
            headCache.remove(envelope.tenantId());
            throw new AuditTrailException(new AuditTrailFailure.Unavailable(
                    "cannot append to audit log for tenant " + envelope.tenantId() + ": " + e.getMessage()));
        } catch (RuntimeException failed) {
            headCache.remove(envelope.tenantId());
            throw failed;
        } finally {
            tenantLock.unlock();
        }
    }

    /**
     * The tenant's chain head, read from memory when known and seeded from the log exactly once.
     *
     * <p>Must be called under the tenant's lock. Seeding parses the whole log, which is the cost the
     * cache exists to pay once instead of on every append; a corrupt log makes that seeding fail with a
     * typed {@link AuditTrailFailure.Corrupted} rather than a raw parse error, because appending onto a
     * chain whose end cannot be established risks reusing a sequence an unreadable record already
     * holds.</p>
     */
    private AuditRecord cachedHead(String tenantId) throws IOException {
        AuditRecord known = headCache.get(tenantId);
        if (known != null) {
            return known;
        }
        List<AuditRecord> chain = readLogStrictly(tenantId);
        if (chain.isEmpty()) {
            return null;
        }
        AuditRecord head = chain.get(chain.size() - 1);
        headCache.put(tenantId, head);
        return head;
    }

    @Override
    public List<AuditRecord> read(String tenantId, long afterSequence, int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new AuditTrailException(new AuditTrailFailure.InvalidRequest("tenantId cannot be blank"));
        }
        if (limit < 0) {
            throw new AuditTrailException(new AuditTrailFailure.InvalidRequest("limit cannot be negative"));
        }
        ReentrantLock tenantLock = lockFor(tenantId);
        tenantLock.lock();
        try {
            List<AuditRecord> chain = readLogStrictly(tenantId);
            var result = new ArrayList<AuditRecord>();
            for (AuditRecord record : chain) {
                if (record.sequence() > afterSequence && result.size() < limit) {
                    result.add(record);
                }
            }
            return List.copyOf(result);
        } catch (IOException e) {
            throw new AuditTrailException(new AuditTrailFailure.Unavailable(
                    "cannot read audit log for tenant " + tenantId + ": " + e.getMessage()));
        } finally {
            tenantLock.unlock();
        }
    }

    @Override
    public Optional<AuditRecord> head(String tenantId) {
        ReentrantLock tenantLock = lockFor(tenantId);
        tenantLock.lock();
        try {
            return Optional.ofNullable(cachedHead(tenantId));
        } catch (IOException e) {
            throw new AuditTrailException(new AuditTrailFailure.Unavailable(
                    "cannot read audit log for tenant " + tenantId + ": " + e.getMessage()));
        } finally {
            tenantLock.unlock();
        }
    }

    @Override
    public ChainVerificationResult verify(String tenantId) {
        ReentrantLock tenantLock = lockFor(tenantId);
        tenantLock.lock();
        try {
            var malformed = new ArrayList<ChainAnomaly.MalformedRecord>();
            List<AuditRecord> chain = readLogTolerantly(tenantId, malformed);
            List<ChainAnomaly> anomalies = ChainWalker.walk(chain, malformed);
            AuditRecord observedHead = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            Optional<HeadPointer> claimedHead = readHead(tenantId);
            long claimedSeq = claimedHead.map(HeadPointer::sequence).orElse(0L);
            EventDigest claimedDigest = claimedHead.map(HeadPointer::digest).orElse(null);
            long observedSeq = observedHead == null ? 0 : observedHead.sequence();
            EventDigest observedDigest = observedHead == null ? null : observedHead.digest();
            ChainWalker.watermarkAnomaly(chain, claimedSeq, claimedDigest, observedSeq, observedDigest)
                    .ifPresent(anomalies::add);
            return new ChainVerificationResult(tenantId, observedSeq, anomalies, clock.instant());
        } catch (IOException e) {
            throw new AuditTrailException(new AuditTrailFailure.Unavailable(
                    "cannot verify audit log for tenant " + tenantId + ": " + e.getMessage()));
        } finally {
            tenantLock.unlock();
        }
    }

    @Override
    public AuditRecord redact(String tenantId, long fromSequenceInclusive, long toSequenceInclusive,
                              String reason, String redactedBy) {
        ReentrantLock tenantLock = lockFor(tenantId);
        tenantLock.lock();
        try {
            List<AuditRecord> chain = new ArrayList<>(readLogStrictly(tenantId));
            if (fromSequenceInclusive < 1 || toSequenceInclusive < fromSequenceInclusive
                    || toSequenceInclusive > chain.size()) {
                throw new AuditTrailException(new AuditTrailFailure.RedactionOutOfRange(tenantId,
                        fromSequenceInclusive, toSequenceInclusive, chain.size()));
            }
            Instant now = clock.instant();
            var seals = new LinkedHashMap<Long, EventDigest>();
            for (long sequence = fromSequenceInclusive; sequence <= toSequenceInclusive; sequence++) {
                AuditRecord original = chain.get((int) (sequence - 1));
                if (original.redacted()) {
                    throw new AuditTrailException(new AuditTrailFailure.InvalidRequest(
                            "sequence " + sequence + " for tenant " + tenantId + " is already redacted"));
                }
                // Computed before the rewrite and identical after it, since redaction touches only detail.
                seals.put(sequence, original.redactionSeal());
            }

            // The tombstone is appended BEFORE the records are rewritten, and the order is the whole
            // reason the crash window is harmless. Redacting first would leave, on a crash, records
            // flagged redacted with nothing authorising them — a state indistinguishable from an
            // attacker setting the flag by hand, so an ordinary power loss would masquerade as a forgery.
            // This way the crash leaves a tombstone describing a redaction that did not happen: every
            // record still verifies against its own digest, and the operation is simply retried.
            AuditEnvelope tombstone = AuditEnvelope.of(tenantId, redactedBy, AuditCategory.ADMINISTRATION,
                    RedactionTombstone.ACTION, "audit-trail", tenantId, AuditOutcome.ALLOWED,
                    reason + " [range " + fromSequenceInclusive + ".." + toSequenceInclusive + "]",
                    UUID.randomUUID().toString(), now,
                    OpaquePayload.of(RedactionTombstone.encode(fromSequenceInclusive, toSequenceInclusive, seals),
                            RedactionTombstone.contentType()));
            AuditRecord tombstoneRecord = AuditRecord.chainedAfter(chain.get(chain.size() - 1), tombstone, now);
            appendLine(tenantId, serialize(tombstoneRecord));
            writeHead(tenantId, tombstoneRecord.sequence(), tombstoneRecord.digest());
            headCache.put(tenantId, tombstoneRecord);

            for (long sequence = fromSequenceInclusive; sequence <= toSequenceInclusive; sequence++) {
                int index = (int) (sequence - 1);
                chain.set(index, chain.get(index).redactedCopy(
                        OpaquePayload.of("[redacted: retention]".getBytes(StandardCharsets.UTF_8), "text/plain"),
                        now, reason, redactedBy));
            }
            chain.add(tombstoneRecord);
            rewriteLog(tenantId, chain);
            return tombstoneRecord;
        } catch (IOException e) {
            headCache.remove(tenantId);
            throw new AuditTrailException(new AuditTrailFailure.Unavailable(
                    "cannot redact audit log for tenant " + tenantId + ": " + e.getMessage()));
        } catch (RuntimeException failed) {
            headCache.remove(tenantId);
            throw failed;
        } finally {
            tenantLock.unlock();
        }
    }

    @Override
    public AuditRetentionPolicy retentionPolicy() {
        return new AuditRetentionPolicy(minimumRetention);
    }

    @Override
    public Set<AuditCapability> capabilities() {
        return Set.of(AuditCapability.DURABLE);
    }

    @Override
    public void close() {
        // Every operation opens and closes its own file handles; nothing is held open between calls.
    }

    private ReentrantLock lockFor(String tenantId) {
        return tenantLocks.computeIfAbsent(tenantId, t -> new ReentrantLock());
    }

    // ---- on-disk layout -------------------------------------------------------------------------

    private Path logPath(String tenantId) {
        return directory.resolve(safeName(tenantId) + ".audit.jsonl");
    }

    private Path headPath(String tenantId) {
        return directory.resolve(safeName(tenantId) + ".audit.head");
    }

    private static String safeName(String tenantId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tenantId.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses the log, refusing to answer at all if any record is unreadable.
     *
     * <p>Used by the paths that must know where the chain <em>ends</em> — {@link #append},
     * {@link #head}, {@link #redact}. Skipping an unparseable line here would be worse than failing:
     * the skipped record still occupies a sequence number, so the next append would reuse it and write
     * a collision into the chain while ostensibly recovering from damage.</p>
     */
    private List<AuditRecord> readLogStrictly(String tenantId) throws IOException {
        var malformed = new ArrayList<ChainAnomaly.MalformedRecord>();
        List<AuditRecord> records = readLogTolerantly(tenantId, malformed);
        if (!malformed.isEmpty()) {
            ChainAnomaly.MalformedRecord first = malformed.get(0);
            throw new AuditTrailException(new AuditTrailFailure.Corrupted(tenantId,
                    malformed.size() + " unreadable record(s), the first at position " + first.position()
                            + ": " + first.detail()));
        }
        return records;
    }

    /**
     * Parses what it can and collects what it cannot, so {@link #verify} can report damage instead of
     * dying on it. {@code verify()}'s whole contract is that a broken chain is its output rather than
     * its failure mode.
     */
    private List<AuditRecord> readLogTolerantly(String tenantId, List<ChainAnomaly.MalformedRecord> malformed)
            throws IOException {
        Path path = logPath(tenantId);
        if (!Files.exists(path)) {
            return List.of();
        }
        var records = new ArrayList<AuditRecord>();
        long position = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            position++;
            try {
                records.add(deserialize(line));
            } catch (RuntimeException unreadable) {
                // Deliberately broad: deserialize can raise NumberFormatException, IllegalArgumentException
                // from UUID and Base64, ArrayIndexOutOfBounds from a short field list and UncheckedIOException
                // from the arity check. Every one of them means the same thing to a reader of the report,
                // and enumerating them would leave the next new one escaping raw.
                malformed.add(new ChainAnomaly.MalformedRecord(position,
                        unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage()));
            }
        }
        return records;
    }

    private void appendLine(String tenantId, String line) throws IOException {
        Path path = logPath(tenantId);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
    }

    /** Atomically replaces the whole log, used only by {@link #redact}, never on the append hot path. */
    private void rewriteLog(String tenantId, List<AuditRecord> records) throws IOException {
        Path path = logPath(tenantId);
        Path temp = directory.resolve(safeName(tenantId) + ".audit.jsonl.tmp-" + UUID.randomUUID());
        var text = new StringBuilder();
        for (AuditRecord record : records) {
            text.append(serialize(record)).append('\n');
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeHead(String tenantId, long sequence, EventDigest digest) throws IOException {
        Path path = headPath(tenantId);
        Path temp = directory.resolve(safeName(tenantId) + ".audit.head.tmp-" + UUID.randomUUID());
        String content = sequence + ":" + digest.hex();
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Optional<HeadPointer> readHead(String tenantId) throws IOException {
        Path path = headPath(tenantId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        String content = Files.readString(path, StandardCharsets.UTF_8).trim();
        int separator = content.indexOf(':');
        if (separator < 0) {
            throw new IOException("malformed head pointer for tenant " + tenantId + ": " + content);
        }
        long sequence = Long.parseLong(content.substring(0, separator));
        EventDigest digest = EventDigest.of(hexToBytes(content.substring(separator + 1)));
        return Optional.of(new HeadPointer(sequence, digest));
    }

    private record HeadPointer(long sequence, EventDigest digest) {
    }

    // ---- serialization ----------------------------------------------------------------------------
    // Every string field is Base64-encoded so no field's own content can ever be confused with the
    // '|' delimiter or a newline, without needing an escaping scheme or a JSON library dependency this
    // module does not otherwise have.

    private static String serialize(AuditRecord record) {
        AuditEnvelope envelope = record.envelope();
        return String.join(FIELD_DELIMITER,
                Integer.toString(envelope.envelopeVersion()),
                envelope.auditId().toString(),
                b64(envelope.tenantId()),
                b64(envelope.principal()),
                envelope.category().name(),
                b64(envelope.action()),
                b64(envelope.resourceType()),
                b64(envelope.resourceId()),
                envelope.outcome().name(),
                b64(envelope.reason()),
                b64(envelope.correlationId()),
                Long.toString(envelope.occurredAt().getEpochSecond()),
                Integer.toString(envelope.occurredAt().getNano()),
                b64(envelope.detail().contentType()),
                Base64.getEncoder().encodeToString(envelope.detail().bytes()),
                Long.toString(record.sequence()),
                record.previousDigest() == null ? "" : record.previousDigest().hex(),
                record.digest().hex(),
                Long.toString(record.recordedAt().getEpochSecond()),
                Integer.toString(record.recordedAt().getNano()),
                record.redacted() ? "1" : "0",
                record.redactedAt() == null ? "" : Long.toString(record.redactedAt().getEpochSecond()),
                record.redactedAt() == null ? "" : Integer.toString(record.redactedAt().getNano()),
                record.redactionReason() == null ? "" : b64(record.redactionReason()),
                record.redactedBy() == null ? "" : b64(record.redactedBy()));
    }

    private static AuditRecord deserialize(String line) {
        String[] fields = line.split("\\" + FIELD_DELIMITER, -1);
        if (fields.length != 25) {
            throw new UncheckedIOException(new IOException(
                    "malformed audit record line, expected 25 fields, got " + fields.length));
        }
        int i = 0;
        int envelopeVersion = Integer.parseInt(fields[i++]);
        UUID auditId = UUID.fromString(fields[i++]);
        String tenantId = unb64(fields[i++]);
        String principal = unb64(fields[i++]);
        AuditCategory category = AuditCategory.valueOf(fields[i++]);
        String action = unb64(fields[i++]);
        String resourceType = unb64(fields[i++]);
        String resourceId = unb64(fields[i++]);
        AuditOutcome outcome = AuditOutcome.valueOf(fields[i++]);
        String reason = unb64(fields[i++]);
        String correlationId = unb64(fields[i++]);
        Instant occurredAt = Instant.ofEpochSecond(Long.parseLong(fields[i++]), Integer.parseInt(fields[i++]));
        String detailContentType = unb64(fields[i++]);
        byte[] detailBytes = Base64.getDecoder().decode(fields[i++]);
        long sequence = Long.parseLong(fields[i++]);
        String previousDigestHex = fields[i++];
        String digestHex = fields[i++];
        Instant recordedAt = Instant.ofEpochSecond(Long.parseLong(fields[i++]), Integer.parseInt(fields[i++]));
        boolean redacted = "1".equals(fields[i++]);
        String redactedAtSeconds = fields[i++];
        String redactedAtNanos = fields[i++];
        String redactionReason = fields[i++];
        String redactedBy = fields[i];

        AuditEnvelope envelope = new AuditEnvelope(envelopeVersion, auditId, tenantId, principal, category, action,
                resourceType, resourceId, outcome, reason, correlationId, occurredAt,
                OpaquePayload.of(detailBytes, detailContentType));
        EventDigest previousDigest = previousDigestHex.isEmpty() ? null : EventDigest.of(hexToBytes(previousDigestHex));
        EventDigest digest = EventDigest.of(hexToBytes(digestHex));
        Instant redactedAt = redactedAtSeconds.isEmpty() ? null
                : Instant.ofEpochSecond(Long.parseLong(redactedAtSeconds), Integer.parseInt(redactedAtNanos));
        return new AuditRecord(envelope, sequence, previousDigest, digest, recordedAt, redacted, redactedAt,
                redactionReason.isEmpty() ? null : unb64(redactionReason),
                redactedBy.isEmpty() ? null : unb64(redactedBy));
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
