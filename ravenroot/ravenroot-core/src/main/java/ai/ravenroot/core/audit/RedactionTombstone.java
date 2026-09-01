package ai.ravenroot.core.audit;

import ai.ravenroot.api.audit.AuditCategory;
import ai.ravenroot.api.audit.AuditRecord;
import ai.ravenroot.api.persistence.EventDigest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The machine-readable body of the {@link AuditCategory#ADMINISTRATION} record that {@code redact()}
 * writes, and the only thing {@code verify()} accepts as authority for a redacted record.
 *
 * <h2>Why a tombstone rather than a flag</h2>
 * <p>The redaction flag lives on the record it exempts. Anything able to edit the record can set the
 * flag, so a verifier that skips content checks because the flag is set has handed the attacker the
 * switch that disables their own detection: alter the content, set the flag, leave the stored digest
 * untouched, and nothing is ever compared.</p>
 *
 * <p>Sealing the redaction fields into a second digest does not fix it either, and this is the part
 * worth being explicit about. The digests here are unkeyed SHA-256. Any value the verifier can
 * recompute, an attacker with write access can also recompute, and a second digest that nothing else
 * depends on constrains nobody. What makes a tombstone different is not that it is hashed but that it
 * is <strong>chained</strong>: it occupies a sequence, carries a {@code previousDigest}, and every
 * record appended after it commits to its digest. Forging one is therefore not a local edit, it is the
 * whole-chain rewrite that {@code AuditTrail}'s Javadoc and ADR 0013 already document as beyond what an
 * unkeyed hash defends against.</p>
 *
 * <h2>Format</h2>
 * <pre>{@code redacted=<from>..<to>;seals=<sequence>:<hex>[,<sequence>:<hex>]*}</pre>
 * <p>The seals are {@link AuditRecord#redactionSeal()} values, digests over every field except the
 * {@code detail} redaction replaces. They are what keeps a legitimately redacted record's remaining
 * fields verifiable: without them, redacting a record once would leave its action, outcome and
 * principal permanently editable, since the content digest is expected not to match. Parsing is strict
 * — anything unrecognised is treated as no tombstone at all, because a tombstone that cannot be read
 * must not silently authorise the redactions it was supposed to name.</p>
 */
final class RedactionTombstone {

    static final String ACTION = "audit.redact";
    private static final String CONTENT_TYPE = "text/plain";

    private RedactionTombstone() {
    }

    /** Renders the body for a tombstone covering {@code from..to} with the given per-record seals. */
    static byte[] encode(long from, long to, Map<Long, EventDigest> seals) {
        var text = new StringBuilder("redacted=").append(from).append("..").append(to).append(";seals=");
        boolean first = true;
        for (Map.Entry<Long, EventDigest> seal : seals.entrySet()) {
            if (!first) {
                text.append(',');
            }
            text.append(seal.getKey()).append(':').append(seal.getValue().hex());
            first = false;
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * Reads the seals a tombstone commits to, or empty when {@code record} is not a well-formed
     * tombstone. Never throws: a malformed body is simply not authority for anything.
     */
    static Optional<Map<Long, EventDigest>> decode(AuditRecord record) {
        if (record.envelope().category() != AuditCategory.ADMINISTRATION
                || !ACTION.equals(record.envelope().action())) {
            return Optional.empty();
        }
        String body = new String(record.envelope().detail().bytes(), StandardCharsets.UTF_8);
        int sealsAt = body.indexOf(";seals=");
        if (!body.startsWith("redacted=") || sealsAt < 0) {
            return Optional.empty();
        }
        String sealList = body.substring(sealsAt + ";seals=".length());
        var seals = new LinkedHashMap<Long, EventDigest>();
        if (sealList.isEmpty()) {
            return Optional.of(seals);
        }
        for (String entry : sealList.split(",", -1)) {
            int colon = entry.indexOf(':');
            if (colon < 0) {
                return Optional.empty();
            }
            try {
                long sequence = Long.parseLong(entry.substring(0, colon));
                seals.put(sequence, EventDigest.of(hexToBytes(entry.substring(colon + 1))));
            } catch (RuntimeException unreadable) {
                return Optional.empty();
            }
        }
        return Optional.of(seals);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("odd-length digest");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
