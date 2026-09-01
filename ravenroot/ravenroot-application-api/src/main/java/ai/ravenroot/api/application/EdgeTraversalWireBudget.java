package ai.ravenroot.api.application;

/**
 * Canonical auxiliary-field budget for one {@link ExecutionEventType#EDGE_TRAVERSED} observation.
 *
 * <p>The stable edge identity owns at most 49,152 bytes of a 65,536-byte SSE frame after JSON
 * escaping. The remaining 16,384 bytes are not merely documentation: this class divides that
 * reserve into 4,096 bytes for field names, framing, numbers, UUIDs, timestamps and source-authored
 * descriptions, and a strict 12,287-byte ceiling for every other JSON-escaped string value. The
 * one-byte gap makes the complete frame strictly smaller than the client ceiling even when both
 * budgets are saturated.</p>
 *
 * <p>Only traversal events use this contract. Existing non-traversal event fields therefore retain
 * their compatibility behavior. Values are measured without trimming, normalization, replacement or
 * truncation; malformed UTF-16 and an exceeded combined budget fail before persistence or emission.</p>
 */
public final class EdgeTraversalWireBudget {

    /** Reserved maximum for fixed JSON/SSE syntax and fixed-width values in either wire projection. */
    public static final int FIXED_PROJECTION_RESERVE_BYTES = 4 * 1024;

    /** Maximum combined escaped bytes for all caller-controlled non-edge string values. */
    public static final int MAX_AUXILIARY_ESCAPED_VALUE_BYTES =
            StableEdgeId.SSE_NON_ID_RESERVE_BYTES - FIXED_PROJECTION_RESERVE_BYTES - 1;

    private EdgeTraversalWireBudget() {
    }

    /**
 * Validates every string serialized by either the public live projection or its structured log.
* @param tenantId authenticated tenant that owns the operation or event
* @param requestId request correlation identity established at ingress
* @param engineId identity of the execution engine that emitted the event
* @param graphVersion pinned graph version for the execution
* @param nodeId graph node identity associated with the operation or event
* @param publicReason bounded public classifier, or {@code null} when none applies
* @param detail bounded trusted diagnostic text
* @param nodeCatalogKey bounded catalog identity used for node-type correlation
* @param deploymentId owning deployment identity, or {@code null} outside a deployment
* @param workloadId deployment-scoped unit-of-work identity, or {@code null} outside a deployment
 */
    public static void requireLiveProjection(String tenantId, String requestId, String engineId,
                                             String graphVersion, String nodeId, String publicReason,
                                             String detail, String nodeCatalogKey, String deploymentId,
                                             String workloadId) {
        requireWithinBudget("live EDGE_TRAVERSED auxiliary fields", tenantId, requestId, engineId,
                graphVersion, nodeId, publicReason, detail, nodeCatalogKey, deploymentId, workloadId);
    }

    /**
 * Validates every caller-controlled string serialized by the durable traversal projection.
* @param eventType domain event type recorded in the durable journal
* @param graphVersion pinned graph version for the execution
* @param nodeId graph node identity associated with the operation or event
 */
    public static void requireDurableProjection(String eventType, String graphVersion, String nodeId) {
        requireWithinBudget("durable EDGE_TRAVERSED auxiliary fields", eventType, graphVersion, nodeId);
    }

    /**
 * Exact UTF-8 size after the server's JSON string escaping rules are applied.
* @param value candidate stable edge identity or text value
* @return exact UTF-8 byte count after JSON string escaping
 */
    public static int jsonEscapedUtf8Length(String value) {
        if (value == null) {
            return 0;
        }
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '"'
                    || character == '\n' || character == '\r' || character == '\t'
                    || character == '\b' || character == '\f') {
                bytes += 2;
            } else if (character < 0x20) {
                bytes += 6;
            } else if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("traversal wire text must be valid UTF-8 text");
                }
                index++;
                bytes += 4;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("traversal wire text must be valid UTF-8 text");
            } else if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else {
                bytes += 3;
            }
            if (bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("traversal wire text is too large");
            }
        }
        return (int) bytes;
    }

    private static void requireWithinBudget(String description, String... values) {
        long used = 0;
        for (String value : values) {
            used += jsonEscapedUtf8Length(value);
            if (used > MAX_AUXILIARY_ESCAPED_VALUE_BYTES) {
                throw new IllegalArgumentException(description + " exceed "
                        + MAX_AUXILIARY_ESCAPED_VALUE_BYTES + " JSON-escaped UTF-8 bytes");
            }
        }
    }
}
