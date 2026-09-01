package ai.ravenroot.api.node.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Immutable request intent submitted to the managed HTTP executor. */
public final class OutboundHttpRequest {
    private final URI destination;
    private final String method;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final Duration deadline;
    private final OutboundCredentialBinding credential;
    private final OutboundHttpSigning signing;

    /**
     * Creates a request without a dynamic signing grant.
     *
     * @param destination absolute destination to be admitted against operator policy
     * @param method HTTP method, trimmed; an absent value becomes the empty string
     * @param headers multi-value request headers copied into an immutable map
     * @param body request bytes copied defensively; {@code null} becomes empty
     * @param deadline non-null caller deadline, subject to service policy
     * @param credential optional operator-owned credential binding selector
     */
    public OutboundHttpRequest(URI destination, String method, Map<String, List<String>> headers,
                               byte[] body, Duration deadline, OutboundCredentialBinding credential) {
        this(destination, method, headers, body, deadline, credential, null);
    }

    /**
     * Creates a request with an optional operator-owned dynamic signing selector.
     *
     * @param destination absolute destination to be admitted against operator policy
     * @param method HTTP method, trimmed; an absent value becomes the empty string
     * @param headers multi-value request headers copied into an immutable map
     * @param body request bytes copied defensively; {@code null} becomes empty
     * @param deadline non-null caller deadline, subject to service policy
     * @param credential optional operator-owned credential binding selector
     * @param signing optional operator-owned signing grant selector, never key material
     */
    public OutboundHttpRequest(URI destination, String method, Map<String, List<String>> headers,
                               byte[] body, Duration deadline, OutboundCredentialBinding credential,
                               OutboundHttpSigning signing) {
        this.destination = Objects.requireNonNull(destination, "destination");
        this.method = method == null ? "" : method.strip();
        this.headers = immutableHeaders(headers);
        this.body = body == null ? new byte[0] : body.clone();
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.credential = credential;
        this.signing = signing;
    }

    /**
     * Obtains the destination URI evaluated by outbound policy.
     *
     * @return destination submitted for policy admission; callers must treat it as immutable
     */
    public URI destination() { return destination; }
    /**
     * Obtains the normalized HTTP method requested by the package.
     *
     * @return trimmed method token, possibly empty when the caller did not supply one
     */
    public String method() { return method; }
    /**
     * Obtains the headers retained with this request intent.
     *
     * @return immutable header names and values copied at construction
     */
    public Map<String, List<String>> headers() { return headers; }
    /**
     * Copies the request body for the caller.
     *
     * @return a fresh copy of request bytes so a caller cannot mutate this intent
     */
    public byte[] body() { return body.clone(); }
    /**
     * Obtains the deadline requested before managed-policy clamping.
     *
     * @return caller-requested deadline before service-level clamping
     */
    public Duration deadline() { return deadline; }
    /**
     * Obtains the optional selector for operator-managed credential placement.
     *
     * @return optional credential-placement selector; no secret material is exposed
     */
    public Optional<OutboundCredentialBinding> credential() { return Optional.ofNullable(credential); }
    /**
     * Obtains the optional selector for dynamic request signing.
     *
     * @return optional dynamic-signing selector; no signing key is exposed
     */
    public Optional<OutboundHttpSigning> signing() { return Optional.ofNullable(signing); }

    static Map<String, List<String>> immutableHeaders(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return source.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                entry -> Objects.requireNonNull(entry.getKey(), "header name"),
                entry -> List.copyOf(Objects.requireNonNull(entry.getValue(), "header values"))));
    }
}
