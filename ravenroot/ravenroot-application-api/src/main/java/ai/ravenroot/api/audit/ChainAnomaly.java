package ai.ravenroot.api.audit;

import ai.ravenroot.api.persistence.EventDigest;

/**
 * One reported defect found by {@link AuditTrail#verify}, distinguishing the two audit-chain failure
 * shapes defined by SEC-13: tamper (a record present but wrong) and a gap (a record that should be
 * present but is not).
 */
public sealed interface ChainAnomaly {

/**
 * The record at {@code sequence} does not carry a valid unredacted digest for its own content.
 * @param sequence sequence of the malformed record
 * @param expected digest recomputed from that record's unredacted content
 * @param actual digest stored on the record
 */
    record TamperedContent(long sequence, EventDigest expected, EventDigest actual) implements ChainAnomaly {
    }

/**
 * The record at {@code sequence} does not chain to the digest its predecessor actually carries.
 * @param sequence sequence of the record whose backward link is invalid
 * @param expectedPreviousDigest digest carried by its actual predecessor
 * @param actualPreviousDigest predecessor digest stored on the record
 */
    record TamperedLinkage(long sequence, EventDigest expectedPreviousDigest, EventDigest actualPreviousDigest)
            implements ChainAnomaly {
    }

    /**
     * One or more sequence numbers in {@code (fromSequenceExclusive, toSequenceInclusive]} are missing
     * from the middle of the chain.
     *
     * <p>This Javadoc previously said "with no redaction tombstone accounting for them", describing a
     * check that did not exist. It has been removed rather than implemented, because it described
     * something that cannot happen: redaction never removes a record. It replaces the {@code detail} of
     * a record that keeps its sequence, its {@code previousDigest} and its position, precisely so the
     * chain's shape is unchanged. A missing sequence is therefore always a deletion and never a
     * redaction, and no tombstone could excuse one.
 * @param fromSequenceExclusive sequence immediately before the unexplained gap
 * @param toSequenceInclusive first surviving sequence after that gap
     */
    record Gap(long fromSequenceExclusive, long toSequenceInclusive) implements ChainAnomaly {
    }

    /**
     * The tenant's persisted chain head (the watermark advanced only by a successful
     * {@link AuditTrail#append}) disagrees with what the stored chain itself contains — most often
     * because the head claims a higher sequence, or a different digest at the same sequence, than the
     * last record actually present. This is what catches deletion of the <em>tail</em> of the chain,
     * which leaves no surviving record to notice a broken forward link.
 * @param claimedSequence sequence asserted by the redaction watermark
 * @param claimedDigest digest asserted by the redaction watermark
 * @param observedSequence sequence actually observed at the redaction boundary
 * @param observedDigest digest actually observed at that boundary
     */
    record HeadMismatch(long claimedSequence, EventDigest claimedDigest, long observedSequence,
                        EventDigest observedDigest) implements ChainAnomaly {
    }

    /**
     * The record at {@code sequence} claims to be redacted, but no {@link AuditCategory#ADMINISTRATION}
     * tombstone in the chain names it.
     *
     * <p>This exists because the redaction flag is stored beside the record and is therefore writable
     * by anyone who can write the record — while {@link AuditTrail#verify} must treat a redacted
     * record's content digest as legitimately non-matching. Trusting the flag alone hands an attacker
     * the switch that disables their own verification: edit the content, set the flag, leave the digest
     * untouched, and nothing is checked.
     *
     * <p>The tombstone is what closes that, and the reason it works is worth stating, because the two
     * more obvious fixes do not. Covering the redaction fields in a second digest computed at redaction
     * time does <strong>not</strong> help: the digest is unkeyed, so an attacker who alters the record
     * recomputes that digest exactly as easily as the verifier does, and no other record depends on it.
     * A tombstone is different in kind — it is a <em>chained</em> record, so forging one means inserting
     * a link that every later record's {@code previousDigest} must agree with. That reduces the forgery
     * to the whole-chain rewrite this design already documents, honestly, as outside what an unkeyed
     * hash can defend against (SEC-22).
 * @param sequence record sequence whose digest cannot be parsed
     */
    record UnaccountedRedaction(long sequence) implements ChainAnomaly {
    }

    /**
     * A stored record could not be parsed at all, reported rather than thrown.
     *
     * <p>{@link AuditTrail#verify}'s contract is that it never throws for a damaged chain, because the
     * damage <em>is</em> the report. A parse failure escaping as a raw exception would make the one
     * operation an investigator reaches for first the one that fails hardest on the evidence it exists
     * to examine.
     *
     * @param position the one-based position in storage order, which is all that is known when the
     *                 record's own sequence number is among the unparseable fields
 * @param detail safe parser diagnosis for the malformed stored record
     */
    record MalformedRecord(long position, String detail) implements ChainAnomaly {
    }
}
