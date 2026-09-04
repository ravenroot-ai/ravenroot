package ai.ravenroot.api.persistence;

import ai.ravenroot.api.payload.PayloadKind;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Bounded declaration of the response a durable human task accepts.
 *
 * @param contentType exact media type accepted from the responder.
 * @param schema bounded schema identifier or definition.
 * @param schemaVersion exact schema version accepted from the responder.
 * @param kind exact first-party payload kind accepted from the responder.
 * @param maxBytes maximum encoded response size.
 */
public record HumanTaskResponseSchema(String contentType, String schema, String schemaVersion,
                                      PayloadKind kind, int maxBytes) {
    /** Maximum encoded schema size. */
    public static final int MAX_SCHEMA_UTF8_BYTES = 16 * 1024;

    /** Validates the bounded response contract. */
    public HumanTaskResponseSchema {
        contentType = HandlerRegistration.requireBoundedKey(contentType, "contentType");
        schemaVersion = HandlerRegistration.requireBoundedKey(schemaVersion, "schemaVersion");
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema cannot be blank");
        }
        int bytes = schema.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_SCHEMA_UTF8_BYTES) {
            throw new IllegalArgumentException("schema is " + bytes + " UTF-8 bytes, above the "
                    + MAX_SCHEMA_UTF8_BYTES + "-byte bound");
        }
        kind = Objects.requireNonNull(kind, "kind");
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes cannot be negative");
    }
}
