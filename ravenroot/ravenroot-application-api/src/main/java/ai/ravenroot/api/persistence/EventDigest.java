package ai.ravenroot.api.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The SHA-256 integrity digest of an {@link EventEnvelope} (ADR 0011, PERS-07).
 *
 * <p>A final class rather than a record for the reason {@link OpaquePayload} already documents: a
 * {@code byte[]} record component inherits reference equality, which would make every assertion that
 * compares digests pass or fail for the wrong reason. Copies are defensive on construction and
 * access, so a stored digest cannot be mutated behind the store's back.</p>
 *
 * <h2>This is integrity, and it is deliberately not authentication</h2>
 * <p>A digest is unkeyed. Anyone able to rewrite a journal row can recompute the digest over the
 * rewritten content, so this detects <em>corruption</em> and accidental truncation, and it detects a
 * tamper only by an actor who cannot also rewrite the digest column. It is <strong>not</strong> the
 * "authenticated versioned envelope" that threat-model INV-14 requires before an external broker is
 * enabled; that needs a keyed MAC or a signature and is tracked as SEC-22, not here. This paragraph
 * exists because "digest" and "authenticated" read as the same guarantee to a hurried reader, and a
 * later change that enables a broker on the strength of this class would be relying on a property it
 * does not have.</p>
 */
public final class EventDigest {

    /** SHA-256 output width. A digest of any other length did not come from this algorithm. */
    public static final int LENGTH = 32;

    private final byte[] value;

    private EventDigest(byte[] value) {
        this.value = value;
    }

/**
 * Wraps an already-computed digest, as when reading one back out of storage.
 * @param value validated SHA-256 digest bytes.
 * @return immutable digest copied from the validated SHA-256 bytes.
 */
    public static EventDigest of(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("digest bytes cannot be null");
        }
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("a SHA-256 digest is " + LENGTH + " bytes, got " + value.length);
        }
        return new EventDigest(value.clone());
    }

    /** Computes the digest of an already-canonicalized byte sequence. */
    static EventDigest over(byte[] canonicalForm) {
        try {
            return new EventDigest(MessageDigest.getInstance("SHA-256").digest(canonicalForm));
        } catch (NoSuchAlgorithmException impossible) {
            // Every conforming JRE ships SHA-256; treating this as recoverable would invent a code
            // path no test could reach and no operator could act on.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }

/**
 * Returns a defensive copy of the SHA-256 output.
 * @return digest bytes isolated from this value object.
 */
    public byte[] value() {
        return value.clone();
    }

/**
 * Lower-case hex, which is what reaches logs, diagnostics and stored columns.
 * @return lower-case hexadecimal rendering of the digest bytes.
 */
    public String hex() {
        var text = new StringBuilder(LENGTH * 2);
        for (byte current : value) {
            text.append(Character.forDigit((current >> 4) & 0xF, 16));
            text.append(Character.forDigit(current & 0xF, 16));
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EventDigest digest && Arrays.equals(value, digest.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "EventDigest[" + hex() + "]";
    }
}
