package ai.ravenroot.api.persistence;

import java.util.Optional;

/**
 * The bounded, non-secret relationships an inventory row reports beyond the aggregate's own identity:
 * which deployment hosts the work, which workload it belongs to, and which correlation ties it to the
 * request that caused it.
 *
 * <h2>Why this is a separate value and not part of the aggregate</h2>
 * <p>ADR 0007 keeps {@link ai.ravenroot.api.application.ProcessInstance} pure, and none of these three
 * identities is state the domain reasons about — no transition is legal or illegal because of them.
 * They are descriptive relationships an operator needs in order to find work, so they live on the
 * store-side envelope for the same reason {@code StoredProcessInstance.updatedAt} does.</p>
 *
 * <h2>They are distinct identities, related explicitly</h2>
 * <p>A deployment id is not a graph version pin, a workload id is not a deployment id, and a
 * correlation id is none of them. Each is carried as its own opaque field so that a caller cannot
 * silently substitute one for another; the store attaches no meaning to any of them and never parses
 * them.</p>
 *
 * <h2>Annotation semantics: recorded when supplied, never cleared</h2>
 * <p>A batch that carries an origin writes every component it names. A batch that carries no origin,
 * or one whose component is absent, leaves the stored value exactly as it was. So a re-entry or a
 * recovery write that does not know the deployment cannot erase what creation recorded, and there is
 * no ordering in which a partially-informed caller destroys information.</p>
 *
 * <p>This is deliberately <em>not</em> the write-once rule {@link GraphVersionPin} follows. The pin is
 * write-once because replay correctness depends on it: replaying against a different definition is a
 * different execution. These are descriptive, and a redeployment genuinely can move hosting, so a
 * later value is an update rather than a contradiction.</p>
 *
 * @param deploymentId  the deployment hosting this execution, when it is deployment-hosted; absent for
 *                      a transient submission, which is the distinction the inventory has to preserve
 *                      without conflating either with a graph version
 * @param workloadId    the workload the execution belongs to, when the caller models one
 * @param correlationId the caller's correlation identity for the request that produced this execution
 */
public record ExecutionOrigin(Optional<String> deploymentId, Optional<String> workloadId,
                              Optional<String> correlationId) {

    /**
     * Bound on each component, in chars.
     *
     * <p>Present because the issue requires bounded non-secret fields and because these three are the
     * only free text a caller can put on an inventory row: without a bound, a row an operator lists is
     * an unbounded text sink that a listing then has to render.</p>
     */
    public static final int MAX_COMPONENT_LENGTH = 256;

    private static final ExecutionOrigin NONE =
            new ExecutionOrigin(Optional.empty(), Optional.empty(), Optional.empty());

    /** Normalises absent components and rejects blank or oversized ones. */
    public ExecutionOrigin {
        deploymentId = require(deploymentId, "deploymentId");
        workloadId = require(workloadId, "workloadId");
        correlationId = require(correlationId, "correlationId");
    }

    /**
     * The origin that names nothing.
     * @return an origin whose every component is absent.
     */
    public static ExecutionOrigin none() {
        return NONE;
    }

    /**
     * Builds an origin from nullable components, which is the shape a caller reading its own request
     * context usually has.
     * @param deploymentId hosting deployment, or {@code null}.
     * @param workloadId owning workload, or {@code null}.
     * @param correlationId caller correlation identity, or {@code null}.
     * @return the corresponding origin.
     */
    public static ExecutionOrigin of(String deploymentId, String workloadId, String correlationId) {
        return new ExecutionOrigin(Optional.ofNullable(deploymentId), Optional.ofNullable(workloadId),
                Optional.ofNullable(correlationId));
    }

    /**
     * Whether this origin names nothing at all, in which case a store has nothing to write.
     * @return {@code true} when every component is absent.
     */
    public boolean isEmpty() {
        return deploymentId.isEmpty() && workloadId.isEmpty() && correlationId.isEmpty();
    }

    /**
     * Applies {@code update} over this origin component by component, keeping the current value
     * wherever the update names none.
     *
     * <p>This is the annotation rule stated as a function, so both adapters share one definition of it
     * rather than each re-deriving "present overwrites, absent preserves" in its own write path.</p>
     * @param update the origin a batch supplied, possibly partial.
     * @return the origin to store.
     */
    public ExecutionOrigin mergedWith(ExecutionOrigin update) {
        if (update == null || update.isEmpty()) {
            return this;
        }
        return new ExecutionOrigin(update.deploymentId.or(() -> deploymentId),
                update.workloadId.or(() -> workloadId),
                update.correlationId.or(() -> correlationId));
    }

    private static Optional<String> require(Optional<String> value, String name) {
        if (value == null) {
            return Optional.empty();
        }
        value.ifPresent(text -> {
            if (text.isBlank()) {
                throw new IllegalArgumentException(name + " cannot be blank when present");
            }
            if (text.length() > MAX_COMPONENT_LENGTH) {
                throw new IllegalArgumentException(name + " exceeds " + MAX_COMPONENT_LENGTH + " chars");
            }
        });
        return value;
    }
}
