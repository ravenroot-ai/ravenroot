package ai.ravenroot.api.persistence;

import java.util.Arrays;
import java.util.Objects;

/**
 * The exact canonical executable GraphML bytes of one graph definition, with their content address.
 *
 * <h2>What "canonical" means here, and what it deliberately does not mean</h2>
 * <p>The canonical executable form of a graph definition is <strong>the exact document that passed
 * ingest validation</strong>. Nothing re-serialises it, normalises its whitespace, rewrites its
 * namespace prefixes or reorders its attributes. Ravenroot already treats those bytes as
 * authoritative — an imported document is written back out verbatim rather than regenerated — and a
 * recovering runtime needs the document it was accepted against, not a document that merely means
 * the same thing.</p>
 *
 * <p>Defining canonical form as "the accepted bytes" rather than as the output of an XML
 * canonicaliser is a decision with a cost, stated plainly: two documents that differ only in
 * whitespace are two definitions. It buys byte-for-byte recovery, an address that is stable for the
 * life of the document, and no dependency on a canonicalisation algorithm whose output could change
 * and silently re-address every definition already stored.</p>
 *
 * <p>This is deliberately a final class rather than a record. A {@code byte[]} record component
 * inherits reference equality, which would make every assertion comparing two definitions pass or
 * fail for the wrong reason. Copies are defensive on construction and on access, so stored bytes can
 * never be mutated through a caller's array — the same reasoning {@link OpaquePayload} records.</p>
 *
 * <p>No size limit is enforced here. A limit belongs to the store, which publishes its own bound
 * through {@link GraphDefinitionStore#maxDefinitionBytes()} and rejects an oversized definition with
 * a classified {@link GraphDefinitionStoreFailure.DefinitionTooLarge} rather than an unclassified
 * argument exception. {@link OpaquePayload} and {@link ExecutionStore#maxPayloadBytes()} divide the
 * same responsibility the same way.</p>
 */
public final class CanonicalGraphMl {

    /**
     * Encoding version of the canonical form these bytes are in.
     *
     * <p>It is recorded with every stored definition so that a future change to what "canonical"
     * means is detectable on read instead of silently reinterpreting bytes written under the older
     * rule.</p>
     */
    public static final int CURRENT_FORMAT_VERSION = 1;

    private final int formatVersion;
    private final byte[] bytes;
    private final GraphContentId contentId;

    private CanonicalGraphMl(int formatVersion, byte[] bytes, GraphContentId contentId) {
        this.formatVersion = formatVersion;
        this.bytes = bytes;
        this.contentId = contentId;
    }

    /**
     * Wraps validated GraphML bytes as the current canonical form.
     *
     * @param validatedDocumentBytes the exact document bytes that passed GraphML ingest validation.
     * @return canonical form carrying a defensive copy of those bytes and their content address.
     */
    public static CanonicalGraphMl of(byte[] validatedDocumentBytes) {
        return of(CURRENT_FORMAT_VERSION, validatedDocumentBytes);
    }

    /**
     * Wraps bytes stated to be in a specific canonical-form version, as a store does when reading a
     * definition back.
     *
     * @param formatVersion positive encoding version the bytes were written under.
     * @param canonicalBytes the exact canonical document bytes.
     * @return canonical form carrying a defensive copy of those bytes and their content address.
     */
    public static CanonicalGraphMl of(int formatVersion, byte[] canonicalBytes) {
        if (formatVersion < 1) {
            throw new IllegalArgumentException("canonical format version must be positive");
        }
        if (canonicalBytes == null) {
            throw new IllegalArgumentException("canonical GraphML bytes cannot be null");
        }
        if (canonicalBytes.length == 0) {
            throw new IllegalArgumentException("canonical GraphML bytes cannot be empty");
        }
        byte[] copy = canonicalBytes.clone();
        return new CanonicalGraphMl(formatVersion, copy, GraphContentId.of(copy));
    }

    /**
     * Returns the encoding version of the canonical form these bytes are in.
     *
     * @return positive canonical-form encoding version.
     */
    public int formatVersion() {
        return formatVersion;
    }

    /**
     * Returns a defensive copy of the canonical document bytes.
     *
     * @return byte copy isolated from the stored value.
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Returns the document length without exposing its contents.
     *
     * @return number of canonical document bytes.
     */
    public int size() {
        return bytes.length;
    }

    /**
     * Returns the address these exact bytes hash to.
     *
     * @return content address computed from the canonical bytes.
     */
    public GraphContentId contentId() {
        return contentId;
    }

    /**
     * Compares canonical form by encoding version and by document bytes.
     *
     * @param other value to compare against.
     * @return whether both carry the same format version and byte-identical documents.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof CanonicalGraphMl canonical
                && formatVersion == canonical.formatVersion
                && Arrays.equals(bytes, canonical.bytes);
    }

    /**
     * Returns a hash consistent with byte equality.
     *
     * @return hash derived from the format version and the document bytes.
     */
    @Override
    public int hashCode() {
        return Objects.hash(formatVersion, Arrays.hashCode(bytes));
    }

    /**
     * Renders the address and length, never the document.
     *
     * <p>A graph definition is authored content and this string reaches logs.</p>
     *
     * @return diagnostic naming the format version, content address and byte length.
     */
    @Override
    public String toString() {
        return "CanonicalGraphMl[formatVersion=" + formatVersion
                + ", contentId=" + contentId.value() + ", size=" + bytes.length + "]";
    }
}
