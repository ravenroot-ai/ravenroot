package ai.ravenroot.core.runtime;

/** Typed refusal of a syntactically valid command that the target catalog does not admit. */
public final class NodeCommandAdmissionException extends IllegalArgumentException {
    private final String nodeId;
    private final String command;

    public NodeCommandAdmissionException(String nodeId, String command) {
        super("Node command is not admitted by the target catalog");
        this.nodeId = nodeId;
        this.command = command;
    }

    public String nodeId() { return nodeId; }
    public String command() { return command; }
}
