package ai.ravenroot.api.persistence;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque bytes plus a content type, as ruled by ADR 0010 section 10.
 *
 * <p>This is deliberately a final class rather than a record: a {@code byte[]} record component
 * inherits reference equality, which would silently make every conformance assertion that compares
 * payloads pass or fail for the wrong reason. Copies are defensive on both construction and access,
 * so a stored payload can never be mutated behind an adapter's back.</p>
 *
 * <p>{@code Object} payloads are rejected by construction: no remote adapter can persist an
 * arbitrary JVM object, and Java serialization would be a security defect. Payload <em>schema</em>
 * remains deferred to API-01; this type carries only a content type and bytes.</p>
 */
public final class OpaquePayload {
    private final byte[] bytes;
    private final String contentType;

    private OpaquePayload(byte[] bytes, String contentType) {
        this.bytes = bytes;
        this.contentType = contentType;
    }

/**
 * Creates an immutable opaque payload from defensive byte storage.
 * @param bytes opaque bytes protected by the value object.
 * @param contentType media type of the opaque payload.
 * @return payload whose bytes cannot be mutated through the caller's array.
 */
    public static OpaquePayload of(byte[] bytes, String contentType) {
        if (bytes == null) throw new IllegalArgumentException("payload bytes cannot be null");
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("payload contentType cannot be blank");
        }
        return new OpaquePayload(bytes.clone(), contentType);
    }

/**
 * Empty payload of the given content type; useful for work items that carry no data.
 * @param contentType media type of the opaque payload.
 * @return zero-length payload carrying the requested media type.
 */
    public static OpaquePayload empty(String contentType) {
        return of(new byte[0], contentType);
    }

/**
 * Returns a defensive copy of the opaque payload bytes.
 * @return byte copy isolated from the stored value.
 */
    public byte[] bytes() {
        return bytes.clone();
    }

/**
 * Returns the payload length without exposing its contents.
 * @return number of stored bytes.
 */
    public int size() {
        return bytes.length;
    }

/**
 * Returns the media type carried with the opaque bytes.
 * @return non-blank payload content type.
 */
    public String contentType() {
        return contentType;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OpaquePayload payload
                && contentType.equals(payload.contentType)
                && Arrays.equals(bytes, payload.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentType, Arrays.hashCode(bytes));
    }

    /** Never renders the bytes: a payload may carry sensitive data and this string reaches logs. */
    @Override
    public String toString() {
        return "OpaquePayload[contentType=" + contentType + ", size=" + bytes.length + "]";
    }
}
