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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reference {@link AuditTrail} adapter, held entirely in process memory. Does not declare
 * {@link AuditCapability#DURABLE}: nothing here survives process death, exactly like
 * {@code InMemoryExecutionStore} does not declare {@code StoreCapability.DURABLE}.
 *
 * <p>The chain (the record list) and the head watermark are two separate fields, updated together
 * under one lock on every {@link #append}. This mirrors {@link ai.ravenroot.core.audit.FileAuditTrail}'s
 * two-file design at reference-adapter scale, so the same class of tamper — mutating or shortening the
 * chain without correspondingly updating the watermark — is representable against both adapters, even
 * though only the file adapter's split has any real-world attacker relevance.</p>
 *
 * <p>This class exposes no mutation backdoor. It briefly carried package-private {@code *ForTest}
 * methods that rewrote and deleted stored records; they were removed because nothing called them, and a
 * production class carrying uncalled methods whose entire purpose is to corrupt an audit chain is a
 * liability that earns its keep only while a test actually uses it. The durable adapter's tamper tests
 * edit real on-disk bytes instead, which is both the realistic attack and a stronger test.</p>
 */
public final class InMemoryAuditTrail implements AuditTrail {
    private final Object lock = new Object();
    private final Map<String, List<AuditRecord>> chains = new HashMap<>();
    private final Map<String, AuditRecord> heads = new HashMap<>();
    private final Clock clock;
    private final Duration minimumRetention;

    public InMemoryAuditTrail() {
        this(Clock.systemUTC(), Duration.ofHours(24));
    }

    public InMemoryAuditTrail(Clock clock, Duration minimumRetention) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.minimumRetention = Objects.requireNonNull(minimumRetention, "minimumRetention");
    }

    @Override
    public AuditRecord append(AuditEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        synchronized (lock) {
            List<AuditRecord> chain = chains.computeIfAbsent(envelope.tenantId(), t -> new ArrayList<>());
            AuditRecord previous = heads.get(envelope.tenantId());
            Instant now = clock.instant();
            AuditRecord record = previous == null
                    ? AuditRecord.genesis(envelope, now)
                    : AuditRecord.chainedAfter(previous, envelope, now);
            chain.add(record);
            heads.put(envelope.tenantId(), record);
            return record;
        }
    }

    @Override
    public List<AuditRecord> read(String tenantId, long afterSequence, int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new AuditTrailException(new AuditTrailFailure.InvalidRequest("tenantId cannot be blank"));
        }
        if (limit < 0) {
            throw new AuditTrailException(new AuditTrailFailure.InvalidRequest("limit cannot be negative"));
        }
        synchronized (lock) {
            List<AuditRecord> chain = chains.getOrDefault(tenantId, List.of());
            var result = new ArrayList<AuditRecord>();
            for (AuditRecord record : chain) {
                if (record.sequence() > afterSequence && result.size() < limit) {
                    result.add(record);
                }
            }
            return List.copyOf(result);
        }
    }

    @Override
    public Optional<AuditRecord> head(String tenantId) {
        synchronized (lock) {
            return Optional.ofNullable(heads.get(tenantId));
        }
    }

    @Override
    public ChainVerificationResult verify(String tenantId) {
        synchronized (lock) {
            List<AuditRecord> chain = chains.getOrDefault(tenantId, List.of());
            List<ChainAnomaly> anomalies = ChainWalker.walk(chain);
            AuditRecord observedHead = chain.isEmpty() ? null : chain.get(chain.size() - 1);
            AuditRecord claimedHead = heads.get(tenantId);
            long claimedSeq = claimedHead == null ? 0 : claimedHead.sequence();
            EventDigest claimedDigest = claimedHead == null ? null : claimedHead.digest();
            long observedSeq = observedHead == null ? 0 : observedHead.sequence();
            EventDigest observedDigest = observedHead == null ? null : observedHead.digest();
            // Compared by (sequence, digest) rather than full record equality: a redaction legitimately
            // changes a record's envelope (its detail) without moving its position in the chain, and
            // that must never be confused with the head having silently moved. The comparison is
            // directional for the reason FileAuditTrail's Javadoc gives at length — a watermark behind
            // the chain is the crash window the write ordering deliberately creates, not tamper.
            ChainWalker.watermarkAnomaly(chain, claimedSeq, claimedDigest, observedSeq, observedDigest)
                    .ifPresent(anomalies::add);
            long checkedThrough = chain.isEmpty() ? 0 : chain.get(chain.size() - 1).sequence();
            return new ChainVerificationResult(tenantId, checkedThrough, anomalies, clock.instant());
        }
    }

    @Override
    public AuditRecord redact(String tenantId, long fromSequenceInclusive, long toSequenceInclusive,
                              String reason, String redactedBy) {
        synchronized (lock) {
            List<AuditRecord> chain = chains.getOrDefault(tenantId, List.of());
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
                seals.put(sequence, original.redactionSeal());
            }
            // Tombstone first, then the redaction, mirroring FileAuditTrail so both adapters leave the
            // same state behind if interrupted and the conformance suite can assert one behaviour.
            AuditEnvelope tombstone = AuditEnvelope.of(tenantId, redactedBy, AuditCategory.ADMINISTRATION,
                    RedactionTombstone.ACTION, "audit-trail", tenantId, AuditOutcome.ALLOWED,
                    reason + " [range " + fromSequenceInclusive + ".." + toSequenceInclusive + "]",
                    UUID.randomUUID().toString(), now,
                    OpaquePayload.of(RedactionTombstone.encode(fromSequenceInclusive, toSequenceInclusive, seals),
                            RedactionTombstone.contentType()));
            AuditRecord tombstoneRecord = append(tombstone);
            for (long sequence = fromSequenceInclusive; sequence <= toSequenceInclusive; sequence++) {
                int index = (int) (sequence - 1);
                chain.set(index, chain.get(index).redactedCopy(
                        OpaquePayload.of("[redacted: retention]".getBytes(StandardCharsets.UTF_8), "text/plain"),
                        now, reason, redactedBy));
            }
            return tombstoneRecord;
        }
    }

    @Override
    public AuditRetentionPolicy retentionPolicy() {
        return new AuditRetentionPolicy(minimumRetention);
    }

    @Override
    public Set<AuditCapability> capabilities() {
        return Set.of();
    }

    @Override
    public void close() {
        // Nothing to release: this adapter owns no external resource.
    }

}
