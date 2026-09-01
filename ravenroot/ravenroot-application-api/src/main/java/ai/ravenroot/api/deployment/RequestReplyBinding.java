package ai.ravenroot.api.deployment;

import java.util.Objects;
import java.util.Optional;

/**
 * The runtime-owned isolation fence a projected request runs under.
 *
 * <p>This is the deployment-generation binding ADR 0027 §3 registers an exchange beneath, minus the
 * per-request identifiers: tenant, deployment, the generation that admitted the request, and — on a
 * source-scoped view — the node whose {@link InboundSourceContext} handed out that view. It exists so
 * a {@link RequestReplyProjection} can tell <em>which</em> isolated runtime it is building a payload
 * for without being able to change the answer.</p>
 *
 * <p>Every field is issued by the runtime. There is no public path that constructs a binding and
 * feeds it back into admission: a caller receives one inside a {@link RequestReplyContext} and can
 * only read it. Graph data and extension configuration therefore cannot select another tenant,
 * deployment, generation or node.</p>
 *
 * @param tenantId the tenant of the {@link ai.ravenroot.api.security.SecurityContext} that activated
 *                 the admitting generation
 * @param deploymentId the deployment that owns the traversal about to start
 * @param generation the deployment generation admitting this request; a view fenced to an earlier
 *                   generation is refused {@link RequestReplyRefusal#ADMISSION_CLOSED} and never
 *                   reaches a projection
 * @param nodeId the source node whose context exposed this capability, or empty for the
 *               deployment-wide view, which belongs to no single node
 */
public record RequestReplyBinding(String tenantId, String deploymentId, long generation,
                                  Optional<String> nodeId) {
/**
 * Rejects incomplete fencing metadata before a projection can observe its admitted runtime.
 */
    public RequestReplyBinding {
        tenantId = requireText(tenantId, "tenantId");
        deploymentId = requireText(deploymentId, "deploymentId");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isPresent()) {
            requireText(nodeId.get(), "nodeId");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
