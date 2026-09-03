package ai.ravenroot.api.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Content address of one exact canonical executable GraphML document.
 *
 * <p>The address is the lowercase hexadecimal SHA-256 digest of the canonical bytes and nothing
 * else. It is computed by the server from the bytes it actually holds and is never accepted from a
 * caller: a caller-supplied address would let a submission claim content it did not carry, which is
 * the same defect {@link ai.ravenroot.api.programming.ProgramArtifactIdentity} exists to prevent for
 * executable source.</p>
 *
 * <p>The digest is deliberately taken over the document bytes alone, with no domain tag and no
 * length prefix. That is not an oversight and it is not a weaker construction here: the input is one
 * complete XML document rather than a concatenation of independently chosen fields, so the
 * concatenation ambiguity a domain-separated, length-prefixed encoding defends against cannot arise.
 * What the plain digest buys instead is decisive — it is byte-identical to the graph version
 * reference an accepted execution already records in {@link GraphVersionPin}, so every pin written
 * before a definition store existed addresses a stored definition directly, with no data migration
 * and no second identity to keep in step.</p>
 *
 * <p>Byte identity is not semantic identity. Two documents that describe the same graph but differ
 * in whitespace, attribute order or encoding declaration have different addresses and are stored as
 * two definitions. That is the correct behaviour for a store whose job is to return the exact bytes
 * an execution was accepted against, and it is a different question from whether two graphs are the
 * same <em>revision</em>, which the graph versioning contract answers over a semantic canonical form
 * instead.</p>
 *
 * @param value lowercase hexadecimal SHA-256 digest of the canonical document bytes.
 */
public record GraphContentId(String value) {

    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Rejects an address that is not a lowercase hexadecimal SHA-256 digest. */
    public GraphContentId {
        if (value == null || !LOWERCASE_SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a graph content id must be exactly 64 lowercase hexadecimal characters");
        }
    }

    /**
     * Computes the address canonical document bytes hash to.
     *
     * @param canonicalBytes exact canonical executable GraphML bytes to address; never {@code null}.
     * @return the content address of those bytes.
     */
    public static GraphContentId of(byte[] canonicalBytes) {
        if (canonicalBytes == null) {
            throw new IllegalArgumentException("canonical bytes cannot be null");
        }
        try {
            return new GraphContentId(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
