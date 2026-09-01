package ai.ravenroot.api.execution;

/**
 * A message was sent to a node the engine knows but which no longer admits work.
 *
 * <p>This is deliberately a different type from the {@link IllegalArgumentException} raised for a
 * {@link NodeRef} the engine never issued. Losing a benign race against a concurrent stop is an
 * ordinary runtime outcome that a caller may retry or route elsewhere; sending to a reference that
 * does not exist is a defect in the caller. Reporting both as the same exception forced callers to
 * parse a message string to tell the two apart.</p>
 *
 * <p>It extends {@link IllegalStateException} so that code written against the previous contract,
 * which caught the broad unchecked types, keeps compiling and keeps catching.</p>
 */
public class NodeNotAcceptingException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient NodeRef node;
/**
 * Retains the state observed when this exception was constructed.
 */
    private final NodeLifecycleState state;

/**
 * Creates a refusal that distinguishes a known stopped node from an unknown reference.
 * @param node the engine-issued node that rejected admission
 * @param state the non-accepting lifecycle state observed for that node
 */
    public NodeNotAcceptingException(NodeRef node, NodeLifecycleState state) {
        super("Node " + node.value() + " no longer accepts messages: " + state);
        this.node = node;
        this.state = state;
    }

/**
 * The node that refused the message.
 * @return the node that rejected the message
 */
    public NodeRef node() {
        return node;
    }

/**
 * The lifecycle state that caused the refusal.
 * @return the lifecycle state that caused the refusal
 */
    public NodeLifecycleState state() {
        return state;
    }
}
