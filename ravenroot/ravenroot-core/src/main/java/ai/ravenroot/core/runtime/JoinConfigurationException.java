package ai.ravenroot.core.runtime;

/**
 * A fan-in node's join configuration is not valid for its topology (CORE-03).
 *
 * <p>Thrown at <em>composition</em>, from the {@link GraphRunner} constructor, for the same reason
 * {@link BehaviorPropertySchema} validates there: before SEC-09 a malformed property surfaced when
 * the node ran, which is after the graph was accepted, hashed, recorded and after every upstream
 * node had already produced its effects. A quorum of 5 on a 3-branch join is not a runtime
 * condition — it is unsatisfiable for every input the graph will ever see — so discovering it on the
 * first execution that happens to reach the join is discovering it too late.</p>
 */
public final class JoinConfigurationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final String property;

    public JoinConfigurationException(String nodeId, String property, String detail) {
        super("Join node '" + nodeId + "' property '" + property + "' " + detail);
        this.nodeId = nodeId;
        this.property = property;
    }

    public String nodeId() {
        return nodeId;
    }

    public String property() {
        return property;
    }
}
