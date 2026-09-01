package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.StableEdgeId;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Minimal durable body for an {@code EDGE_TRAVERSED} journal event.
 *
 * <p>The edge id is encoded directly as strict UTF-8 under a dedicated media type. No source,
 * target, outcome, payload or diagnostic is duplicated into the journal: the stable handle is the
 * only fact replay cannot recover from the existing invocation structure without ambiguity.</p>
 */
public final class EdgeTraversalEventData {

    /** Media type used for the strict UTF-8 stable edge identity. */
public static final String CONTENT_TYPE = "application/vnd.ravenroot.edge-traversal-id; charset=utf-8";

    private EdgeTraversalEventData() {
    }

    /**
 * Creates the immutable event body for one non-blank stable edge identity.
* @param edgeId stable identity of the traversed edge
* @return immutable payload using the dedicated edge-traversal media type
 */
    public static OpaquePayload payload(String edgeId) {
        String stableId = StableEdgeId.requireValid(edgeId);
        return OpaquePayload.of(stableId.getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }

    /**
 * Reads an edge identity only from this contract's exact media type and strict UTF-8 bytes.
 * Malformed or foreign payloads stay absent rather than becoming a plausible-looking identity.
* @param payload payload to encode, inspect, or forward
* @return decoded stable edge identity, or empty for foreign or malformed data
 */
    public static Optional<String> edgeId(OpaquePayload payload) {
        if (payload == null || !CONTENT_TYPE.equals(payload.contentType())) {
            return Optional.empty();
        }
        if (payload.size() > StableEdgeId.MAX_UTF8_BYTES) {
            return Optional.empty();
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload.bytes())).toString();
            return Optional.of(StableEdgeId.requireValid(value));
        } catch (CharacterCodingException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
}
