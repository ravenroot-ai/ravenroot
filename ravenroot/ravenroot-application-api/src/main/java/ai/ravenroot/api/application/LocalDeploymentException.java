package ai.ravenroot.api.application;

import java.util.Map;
import java.util.Objects;

/**
 * A fail-closed, redaction-safe refusal from the process-local deployment lifecycle boundary.
 *
 * <p>Same discipline as {@link SourceSessionException}: the taxonomy is closed, every message is a
 * fixed string chosen before any request was seen, and nothing submitted by the caller — an id, a
 * graph fragment, a node name, an adapter's own text — reaches {@link #getMessage()}. Anything a
 * server-side diagnostic sink might want travels separately in {@link #diagnosticDetail()}, which is
 * never serialized to a client.</p>
 *
 * <p><b>Absence is not a refusal.</b> An unknown deployment id and a sibling tenant's deployment id
 * are not represented here at all: they are an empty lookup result, so that the two are answered
 * identically and neither discloses the other's existence. This type exists for the requests that are
 * refusable on their own terms.</p>
 *
 * <p><b>The taxonomy is exactly what this contract can refuse, and nothing else.</b> Every constant
 * below is thrown by {@link RavenrootApplication}'s deployment family and is reachable by an embedder
 * calling it directly. A refusal that only an adapter can produce does not belong here even when the
 * product does perform it: a mismatched deployment <em>scope</em> is the worked example — it is
 * refused, and refused rather than degraded, but the refusal is made by whatever adapter accepted a
 * scope token (the built-in HTTP one answers {@code 400 INVALID_REQUEST} on {@code /v1/deployments}),
 * because {@link RavenrootApplication#registerLocalDeployment} takes no scope argument and so has
 * nothing to refuse. A constant here for it would be a case every adapter's exhaustive mapping must
 * handle and no code path can ever produce — a published claim that a mechanism exists where it does
 * not. See {@link LocalDeploymentStatus} for where the scope actually is enforced.</p>
 */
public final class LocalDeploymentException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Closed taxonomy; messages contain no submitted identifiers, values or adapter text. */
    public enum Reason {
        /** The id is absent, over-long, or uses characters outside the accepted set. */
        DEPLOYMENT_ID_INVALID("the deployment id is invalid"),
        /** The id is already registered in this tenant and bound to a different graph version. */
        GRAPH_CONFLICT("the deployment id is already bound to a different graph version"),
        /** An effective SOURCE node in the graph has no trusted inbound-source capability. */
        SOURCE_CAPABILITY_MISMATCH("an effective SOURCE node has no trusted inbound-source capability");

        private final String message;
        Reason(String message) { this.message = message; }
        String publicMessage() { return message; }
    }

    private final transient Reason reason;
    private final transient Map<String, Object> diagnosticDetail;

    /**
     * A refusal carrying no server-side diagnostic detail.
     * @param reason closed-taxonomy refusal reason
     */
    public LocalDeploymentException(Reason reason) {
        this(reason, Map.of());
    }

    /**
     * A refusal carrying detail for a server-side diagnostic sink only.
     * @param reason closed-taxonomy refusal reason
     * @param diagnosticDetail submitted detail that must never be serialized to a client
     */
    public LocalDeploymentException(Reason reason, Map<String, Object> diagnosticDetail) {
        super(Objects.requireNonNull(reason, "reason").publicMessage());
        this.reason = reason;
        this.diagnosticDetail = Map.copyOf(Objects.requireNonNull(diagnosticDetail, "diagnosticDetail"));
    }

    /**
     * The closed-taxonomy reason an adapter maps to a wire status.
     * @return why the request was refused
     */
    public Reason reason() { return reason; }

    /**
     * Submitted graph detail for a server-side diagnostic sink only; never serialize this map.
     * @return bounded server-side diagnostic detail
     */
    public Map<String, Object> diagnosticDetail() { return diagnosticDetail; }
}
