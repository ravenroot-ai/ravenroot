package ai.ravenroot.api.node.service;

import java.util.Objects;

/** Stable, sanitized refusal from a package service. */
public final class NodePackageServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Sanitized categories that a package may use for retry, presentation, or telemetry decisions. */
    public enum Reason {
        /** The runtime did not compose the requested service for this package. */
        SERVICE_UNAVAILABLE,
        /** No credential could be obtained for the supplied opaque reference. */
        CREDENTIAL_UNAVAILABLE,
        /** Policy forbids the requested destination before a connection is made. */
        DESTINATION_FORBIDDEN,
        /** The reference could not be resolved under the delivered authority. */
        RESOLUTION_REFUSED,
        /** TLS policy or peer verification refused the connection. */
        TLS_REFUSED,
        /** The request exceeded an operator-enforced outbound budget. */
        REQUEST_TOO_LARGE,
        /** The response exceeded an operator-enforced inbound budget. */
        RESPONSE_TOO_LARGE,
        /** The requested deadline elapsed before a terminal result was available. */
        DEADLINE_EXCEEDED,
        /** The caller cancelled the managed operation. */
        CANCELLED,
        /** The request was rejected during service admission. */
        ADMISSION_REFUSED,
        /** The remote peer or protocol exchange was unacceptable. */
        PROTOCOL_REFUSED,
        /** A transport failure occurred without exposing transport-specific details. */
        TRANSPORT_FAILED
    }

    /** Sanitized reason retained independently of the exception message. */
    private final Reason reason;

    /**
     * Creates a refusal with a stable message containing no destination, credential, or peer data.
     *
     * @param reason classification safe to expose to a package
     */
    public NodePackageServiceException(Reason reason) {
        super("Node package service refused the operation: " + Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    /**
     * Returns the stable refusal classification.
     *
     * @return a value suitable for package-level handling without parsing exception text
     */
    public Reason reason() {
        return reason;
    }
}
