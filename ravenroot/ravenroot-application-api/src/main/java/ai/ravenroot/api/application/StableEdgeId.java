package ai.ravenroot.api.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Canonical validation contract for an edge identity that crosses a runtime observation boundary.
 *
 * <p>The browser runtime client refuses an SSE frame above {@value #SSE_FRAME_MAX_BYTES} bytes.
 * JSON can expand one UTF-8 input byte to a six-byte Unicode escape, so the identity owns at
 * most one sixth of the frame after reserving {@value #SSE_NON_ID_RESERVE_BYTES} bytes for every
 * other field and the SSE framing. {@link EdgeTraversalWireBudget} enforces that auxiliary reserve.
 * The resulting {@value #MAX_UTF8_BYTES}-byte bound is shared by
 * programmatic graphs, GraphML imports, live and durable events, and server projections.</p>
 *
 * <p>Validation never truncates, trims, normalizes or replaces malformed text. The exact identity is
 * either accepted or refused before dispatch, so every accepted value remains a stable lookup key.</p>
 */
public final class StableEdgeId {

    /** Runtime-client ceiling for one complete SSE frame. */
    public static final int SSE_FRAME_MAX_BYTES = 64 * 1024;

    /** Reserved budget for the complete EDGE_TRAVERSED frame excluding its edge-id value. */
    public static final int SSE_NON_ID_RESERVE_BYTES = 16 * 1024;

    /** Maximum JSON output bytes one strict UTF-8 identity byte can require. */
    public static final int MAX_JSON_ESCAPED_BYTES_PER_ID_BYTE = 6;

    /** Maximum accepted strict UTF-8 byte length of one stable edge identity. */
    public static final int MAX_UTF8_BYTES =
            (SSE_FRAME_MAX_BYTES - SSE_NON_ID_RESERVE_BYTES) / MAX_JSON_ESCAPED_BYTES_PER_ID_BYTE;

    private StableEdgeId() {
    }

    /**
 * Returns {@code value} unchanged when it is a valid stable identity.
 *
 * @throws IllegalArgumentException when the value is null, blank, malformed UTF-16, or exceeds
 * {@link #MAX_UTF8_BYTES} strict UTF-8 bytes
* @param value candidate stable edge identity or text value
* @return the unchanged valid identity
 */
    public static String requireValid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("edgeId cannot be blank");
        }
        int length = strictUtf8Length(value);
        if (length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("edgeId exceeds " + MAX_UTF8_BYTES + " UTF-8 bytes");
        }
        return value;
    }

    /**
 * Returns the strict UTF-8 byte length, refusing malformed surrogate input instead of replacing it.
* @param value candidate stable edge identity or text value
* @return strict UTF-8 byte length of the supplied text
 */
    public static int strictUtf8Length(String value) {
        if (value == null) {
            throw new IllegalArgumentException("edgeId cannot be null");
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            return encoded.remaining();
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("edgeId must be valid UTF-8 text", malformed);
        }
    }
}
