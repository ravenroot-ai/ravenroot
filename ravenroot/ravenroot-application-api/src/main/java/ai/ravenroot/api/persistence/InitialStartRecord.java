package ai.ravenroot.api.persistence;

import ai.ravenroot.api.security.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Permanent deployment-scoped initial-START admission. This is not an expiring transport inbox.
 * The original input and authenticated identity are retained for acceptance/dispatch reconciliation;
 * a second requester can neither replace the input nor create a new traversal.
 *
 * @param deploymentId stable logical deployment, independent of incarnation
 * @param graphVersion immutable version accepted by this deployment
 * @param identity original authenticated caller, without credentials
 * @param input bounded original JSON input
 * @param acceptedAt store admission time
 * @param executionCreated permanent evidence that the execution aggregate was created
 */
public record InitialStartRecord(String deploymentId, String graphVersion, SecurityContext identity,
                                 OpaquePayload input, Instant acceptedAt, boolean executionCreated) {
    /** Validates bounded scope and retained work. */
    public InitialStartRecord {
        deploymentId = text(deploymentId, "deploymentId");
        graphVersion = text(graphVersion, "graphVersion");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }

    /** Proposes an admission before its execution has been created.
 * @param deploymentId logical deployment identity; a new incarnation does not change it
 * @param graphVersion exact immutable graph version admitted for this history
 * @param identity original authenticated caller retained for recovery of accepted initial work
 * @param input actual bounded ingress or workflow input to select; no implicit envelope is invented
 * @param acceptedAt time the original admission or contribution was accepted
 */
    public InitialStartRecord(String deploymentId, String graphVersion, SecurityContext identity,
                              OpaquePayload input, Instant acceptedAt) {
        this(deploymentId, graphVersion, identity, input, acceptedAt, false);
    }

    /** Stable process identity; different versions/incarnations cannot reset deployment allowance.
 * @return stable original initial-START process identity for this tenant and logical deployment
 */
    public UUID processInstanceId() {
        return UUID.nameUUIDFromBytes((identity.tenantId() + '\0' + deploymentId + "\0initial-start-v1")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Stable traversal identity belonging to this admission.
 * @return stable original initial START traversal identity derived from its admitted process
 */
    public UUID traversalId() {
        return UUID.nameUUIDFromBytes((processInstanceId() + "\0traversal")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank() || value.length() > ExecutionOrigin.MAX_COMPONENT_LENGTH)
            throw new IllegalArgumentException("invalid " + name);
        return value;
    }
}
