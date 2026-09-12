package ai.ravenroot.api.node.service;

import java.util.List;
import java.util.Map;

/** Bounded response returned by the managed HTTP executor. */
public final class OutboundHttpResponse {
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final long effectiveMaximumOutputBytes;

    /**
     * Captures a policy-bounded response with immutable headers and a defensive body copy.
     *
     * @param statusCode response status as received from the managed transport
     * @param headers response headers copied into an immutable map
     * @param body response bytes copied defensively; {@code null} becomes empty
     */
    public OutboundHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        this(statusCode, headers, body, Long.MAX_VALUE);
    }

    /**
     * Captures a policy-bounded response and the final-output authority applied by the managed
     * transport.
     *
     * <p>The ceiling is carried with the response because transport bytes may expand when a node
     * projects them into a canonical payload. Callers must intersect it with their own structural
     * ceiling at that final projection boundary. The three-argument compatibility constructor uses
     * {@link Long#MAX_VALUE}, which lets existing transport doubles remain source compatible without
     * claiming that they applied an operator limit.</p>
     *
     * @param statusCode response status as received from the managed transport
     * @param headers response headers copied into an immutable map
     * @param body response bytes copied defensively; {@code null} becomes empty
     * @param effectiveMaximumOutputBytes effective final-output ceiling applied by the managed
     *        transport; must be positive
     */
    public OutboundHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body,
                                long effectiveMaximumOutputBytes) {
        if (effectiveMaximumOutputBytes < 1) {
            throw new IllegalArgumentException("effectiveMaximumOutputBytes must be positive");
        }
        this.statusCode = statusCode;
        this.headers = OutboundHttpRequest.immutableHeaders(headers);
        this.body = body == null ? new byte[0] : body.clone();
        this.effectiveMaximumOutputBytes = effectiveMaximumOutputBytes;
    }

    /**
     * Obtains the status code returned by the managed transport.
     *
     * @return transport response status without applying application-specific interpretation
     */
    public int statusCode() { return statusCode; }
    /**
     * Obtains the headers retained from the managed transport response.
     *
     * @return immutable response headers copied at construction
     */
    public Map<String, List<String>> headers() { return headers; }
    /**
     * Copies the bounded response body for the caller.
     *
     * @return a fresh copy of the bounded response body
     */
    public byte[] body() { return body.clone(); }

    /**
     * Obtains the final-output ceiling that survived managed operator-policy intersection.
     *
     * @return positive maximum bytes for caller projection or canonicalization; legacy response
     *         constructors return {@link Long#MAX_VALUE}
     */
    public long effectiveMaximumOutputBytes() { return effectiveMaximumOutputBytes; }
}
