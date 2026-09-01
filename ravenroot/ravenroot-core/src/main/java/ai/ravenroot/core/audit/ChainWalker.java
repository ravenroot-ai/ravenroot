package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.audit.ChainAnomaly;
import ai.ravenroot.api.persistence.EventDigest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The shared chain-walking algorithm both in-tree {@code AuditTrail} adapters use for
 * {@code verify()}, so the tamper/gap detection logic exists exactly once rather than being
 * reimplemented per adapter and possibly drifting.
 *
 * <p>Records must be supplied in ascending storage order (whatever order the adapter actually holds
 * them in — not necessarily contiguous sequence order, since that is exactly the property under
 * test). {@link #walk} makes no assumption that the list it is given is well-formed.</p>
 *
 * <h2>Two passes, because a redaction is authorised by a record that comes later</h2>
 * <p>A redaction tombstone is appended <em>after</em> the records it covers, so whether a redacted
 * record at sequence 3 is legitimate cannot be decided while standing on sequence 3. The first pass
 * therefore collects every tombstone's seals and the second walks the chain. Deciding redaction
 * legitimacy inline would mean trusting the record's own flag, which is precisely the defect this
 * class was rewritten to remove — see {@link RedactionTombstone}.</p>
 */
final class ChainWalker {
    private ChainWalker() {
    }

    static List<ChainAnomaly> walk(List<AuditRecord> chain) {
        return walk(chain, List.of());
    }

    /**
     * @param malformed records that could not be parsed at all, reported as-is rather than thrown
     */
    static List<ChainAnomaly> walk(List<AuditRecord> chain, List<ChainAnomaly.MalformedRecord> malformed) {
        var anomalies = new ArrayList<ChainAnomaly>(malformed);

        Map<Long, EventDigest> authorisedRedactions = new HashMap<>();
        for (AuditRecord record : chain) {
            RedactionTombstone.decode(record).ifPresent(authorisedRedactions::putAll);
        }

        long expectedSequence = 1;
        EventDigest previousActualDigest = null;
        for (AuditRecord record : chain) {
            if (record.sequence() > expectedSequence) {
                anomalies.add(new ChainAnomaly.Gap(expectedSequence - 1, record.sequence() - 1));
            }
            if (!Objects.equals(record.previousDigest(), previousActualDigest)) {
                anomalies.add(new ChainAnomaly.TamperedLinkage(record.sequence(), record.previousDigest(),
                        previousActualDigest));
            }
            if (record.redacted()) {
                verifyRedacted(record, authorisedRedactions, anomalies);
            } else if (!record.digestMatchesContent()) {
                anomalies.add(new ChainAnomaly.TamperedContent(record.sequence(), record.digest(),
                        record.recomputeDigest()));
            }
            previousActualDigest = record.digest();
            expectedSequence = record.sequence() + 1;
        }
        return anomalies;
    }

    /**
     * A redacted record is exempt from the content-digest check — its {@code detail} was deliberately
     * replaced — but only once a chained tombstone says so, and only for the {@code detail}.
     *
     * <p>Both halves are load-bearing. Without the tombstone, setting the flag is a self-service
     * exemption from verification. Without the seal, a record that has legitimately been redacted once
     * becomes permanently editable in every remaining field, because the mismatch that would have
     * revealed the edit is the mismatch redaction is expected to produce.</p>
     */
    /**
     * Compares the tenant's watermark with the chain actually stored, <strong>directionally</strong>.
     *
     * <p>The adapters write the record first and advance the watermark second, so the two disagree in
     * one direction as a matter of ordinary operation and in the other only when records have gone
     * missing:</p>
     * <ul>
     *   <li>{@code claimed > observed} — the watermark names a record the log no longer has. This is
     *   tail deletion, and it is the case the separate watermark exists to catch, because with the tail
     *   gone no surviving record's {@code previousDigest} is left to notice a broken link.</li>
     *   <li>{@code claimed == observed} with different digests — something replaced the head in place.</li>
     *   <li>{@code claimed < observed} — the log is ahead of the watermark, which is exactly what a
     *   crash between the two writes leaves behind. Not tamper. It is still checked rather than waved
     *   through: the record the watermark names must be present with the digest it claims, so a
     *   watermark rolled back to a value that never existed is still caught.</li>
     * </ul>
     */
    static Optional<ChainAnomaly.HeadMismatch> watermarkAnomaly(List<AuditRecord> chain, long claimedSequence,
                                                                EventDigest claimedDigest, long observedSequence,
                                                                EventDigest observedDigest) {
        var mismatch = Optional.of(new ChainAnomaly.HeadMismatch(claimedSequence, claimedDigest,
                observedSequence, observedDigest));
        if (claimedSequence == 0) {
            // No watermark has ever been written, so there is no claim to contradict.
            return Optional.empty();
        }
        if (claimedSequence > observedSequence) {
            return mismatch;
        }
        if (claimedSequence == observedSequence) {
            return Objects.equals(claimedDigest, observedDigest) ? Optional.empty() : mismatch;
        }
        return chain.stream()
                .filter(record -> record.sequence() == claimedSequence)
                .findFirst()
                .filter(record -> Objects.equals(record.digest(), claimedDigest))
                .map(record -> Optional.<ChainAnomaly.HeadMismatch>empty())
                .orElse(mismatch);
    }

    private static void verifyRedacted(AuditRecord record, Map<Long, EventDigest> authorisedRedactions,
                                       List<ChainAnomaly> anomalies) {
        EventDigest sealed = authorisedRedactions.get(record.sequence());
        if (sealed == null) {
            anomalies.add(new ChainAnomaly.UnaccountedRedaction(record.sequence()));
            return;
        }
        EventDigest actual = record.redactionSeal();
        if (!sealed.equals(actual)) {
            anomalies.add(new ChainAnomaly.TamperedContent(record.sequence(), sealed, actual));
        }
    }
}
