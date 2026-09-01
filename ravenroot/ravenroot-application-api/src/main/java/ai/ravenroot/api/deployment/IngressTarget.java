package ai.ravenroot.api.deployment;

import java.util.Objects;
import java.util.Optional;

/**
 * Where in the graph an unsolicited inbound event starts a traversal.
 *
 * <h2>This type exists because the decision is open</h2>
 * <p>ADR 0021 lists ingress topology as unresolved: sources may target the graph's {@code START}
 * node, a dedicated inbound node, or an arbitrary node. Encoding any one of those as an assumption
 * — a {@code submit(event)} that silently meant "start" — would settle a documented decision by writing
 * code, which an earlier approach deliberately refused to do.
 *
 * <p>So the target is a <b>parameter</b>. Both shapes are expressible today and neither is
 * privileged; if one topology becomes canonical, one factory may be deprecated or a validation rule added,
 * and no caller signature changes.
 */
public final class IngressTarget {
    private static final IngressTarget START = new IngressTarget(null);

    private final String nodeId;

    private IngressTarget(String nodeId) {
        this.nodeId = nodeId;
    }

/**
 * The graph's declared start node, whatever it is — the deployment resolves it.
 * @return target that delegates node selection to the graph's declared start node.
 */
    public static IngressTarget start() {
        return START;
    }

    /**
     * A named node. Covers both the "dedicated inbound node" and "arbitrary node" topologies, which
     * differ by policy rather than by mechanism — a deployment that admits only a dedicated inbound
     * node enforces that as a rule over this value.
 * @param nodeId non-blank graph node identifier selected as the ingress target.
 * @return target bound to that named node.
     */
    public static IngressTarget node(String nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.isBlank()) {
            throw new IllegalArgumentException("An ingress target node id cannot be blank");
        }
        return new IngressTarget(nodeId);
    }

/**
 * The named node, or empty when this targets the graph's start node.
 * @return named target node, or empty when this value targets graph start.
 */
    public Optional<String> nodeId() {
        return Optional.ofNullable(nodeId);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IngressTarget target && Objects.equals(nodeId, target.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(nodeId);
    }

    @Override
    public String toString() {
        return nodeId == null ? "start" : "node(" + nodeId + ")";
    }
}
