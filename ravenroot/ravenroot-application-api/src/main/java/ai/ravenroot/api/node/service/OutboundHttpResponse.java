package ai.ravenroot.api.node.service;

import java.util.List;
import java.util.Map;

/** Bounded response returned by the managed HTTP executor. */
public final class OutboundHttpResponse {
    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    /**
     * Captures a policy-bounded response with immutable headers and a defensive body copy.
     *
     * @param statusCode response status as received from the managed transport
     * @param headers response headers copied into an immutable map
     * @param body response bytes copied defensively; {@code null} becomes empty
     */
    public OutboundHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = OutboundHttpRequest.immutableHeaders(headers);
        this.body = body == null ? new byte[0] : body.clone();
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
}
