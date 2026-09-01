package ai.ravenroot.api.node.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Immutable WebSocket handshake intent.
 *
 * <p>The two optional per-request ceilings — complete-message bytes and fragments per message —
 * let a package narrow, for one session, the WebSocket limits the operator granted it. They can
 * only narrow: the managed bridge applies the smaller of the request value and the operator
 * ceiling, so an absent value means "exactly the operator policy" and a larger value is clamped
 * back to the operator policy rather than refused.</p>
 */
public final class OutboundWebSocketRequest {
    private final URI destination;
    private final Map<String, List<String>> headers;
    private final List<String> subprotocols;
    private final Duration deadline;
    private final OutboundCredentialBinding credential;
    private final Long maximumMessageBytes;
    private final Integer maximumFragments;

    /**
     * Retained five-argument form; both per-request ceilings are absent, which is exactly the
     * operator policy behaviour.
     * @param destination absolute destination subject to operator policy
     * @param headers handshake headers copied into an immutable map
     * @param subprotocols requested subprotocols in preference order
     * @param deadline non-null caller deadline, subject to service policy
     * @param credential optional operator-owned credential placement selector
     */
    public OutboundWebSocketRequest(URI destination, Map<String, List<String>> headers,
                                    List<String> subprotocols, Duration deadline,
                                    OutboundCredentialBinding credential) {
        this(destination, headers, subprotocols, deadline, credential, null, null);
    }

    /**
     * Creates a WebSocket request with explicit framing limits.
     * @param maximumMessageBytes optional complete-message ceiling in bytes; when present it must
     *                            be positive and at most {@link Integer#MAX_VALUE}
     * @param maximumFragments    optional per-message fragment ceiling; when present it must be
     *                            positive
     * @param destination absolute destination subject to operator policy
     * @param headers handshake headers copied into an immutable map
     * @param subprotocols requested subprotocols in preference order
     * @param deadline non-null caller deadline, subject to service policy
     * @param credential optional operator-owned credential placement selector
     */
    public OutboundWebSocketRequest(URI destination, Map<String, List<String>> headers,
                                    List<String> subprotocols, Duration deadline,
                                    OutboundCredentialBinding credential,
                                    Long maximumMessageBytes, Integer maximumFragments) {
        this.destination = Objects.requireNonNull(destination, "destination");
        this.headers = OutboundHttpRequest.immutableHeaders(headers);
        this.subprotocols = List.copyOf(subprotocols == null ? List.of() : subprotocols);
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.credential = credential;
        this.maximumMessageBytes = boundedBytes(maximumMessageBytes);
        this.maximumFragments = positive(maximumFragments);
    }

    /**
     * Obtains the WebSocket destination evaluated by outbound policy.
     *
     * @return destination submitted for policy admission
     */
    public URI destination() { return destination; }
    /**
     * Obtains the immutable headers sent with the opening handshake.
     *
     * @return immutable handshake headers copied at construction
     */
    public Map<String, List<String>> headers() { return headers; }
    /**
     * Obtains the subprotocols requested during the opening handshake.
     *
     * @return immutable requested subprotocols in caller preference order
     */
    public List<String> subprotocols() { return subprotocols; }
    /**
     * Obtains the deadline requested before managed-policy clamping.
     *
     * @return caller-requested deadline before service-level clamping
     */
    public Duration deadline() { return deadline; }
    /**
     * Obtains the optional selector for operator-managed credential placement.
     *
     * @return optional credential-placement selector; never raw credential material
     */
    public Optional<OutboundCredentialBinding> credential() { return Optional.ofNullable(credential); }

/**
 * Per-request complete-message byte ceiling, or empty for the operator ceiling.
     * @return requested ceiling in bytes, or empty to use the operator ceiling unchanged
 */
    public OptionalLong maximumMessageBytes() {
        return maximumMessageBytes == null ? OptionalLong.empty() : OptionalLong.of(maximumMessageBytes);
    }

/**
 * Per-request fragment ceiling for one assembled message, or empty for the operator ceiling.
     * @return requested fragment ceiling, or empty to use the operator ceiling unchanged
 */
    public OptionalInt maximumFragments() {
        return maximumFragments == null ? OptionalInt.empty() : OptionalInt.of(maximumFragments);
    }

    private static Long boundedBytes(Long value) {
        if (value == null) return null;
        if (value <= 0) {
            throw new IllegalArgumentException("maximumMessageBytes must be positive");
        }
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maximumMessageBytes exceeds the maximum representable buffer");
        }
        return value;
    }

    private static Integer positive(Integer value) {
        if (value == null) return null;
        if (value <= 0) throw new IllegalArgumentException("maximumFragments must be positive");
        return value;
    }
}
