package ai.ravenroot.extensions.ai;

/**
 * Something an MCP server did, or failed to do, that this bundle will not act on.
 *
 * <h2>It is deliberately not an {@link AgentException}, and the difference is where it happens</h2>
 * <p>The same condition — a server that does not answer — has two correct outcomes depending on when
 * it occurs, and collapsing them onto one type is how the wrong one gets chosen:</p>
 * <ul>
 *   <li><b>During discovery</b>, before the model has been told anything, the node cannot be built:
 *   its author declared tools that do not exist for this run. {@link AgentNodeBehavior} translates
 *   this refusal into a named {@link AgentException} and the node fails — never with a
 *   {@link ai.ravenroot.api.execution.NodeResult}, because no model generated anything.</li>
 *   <li><b>During the loop</b>, the model has already been told the tool exists and has chosen to
 *   call it. Failing the traversal there would let a third party's outage terminate an execution that
 *   could still succeed, so the refusal becomes a {@code tool} message the model reads and can act on
 *   — the contract on {@link AgentTool}. The turn budget remains what stops a model that cannot.</li>
 * </ul>
 *
 * <p>{@link #forModel()} is the second half of that, and is the only text of this class a model ever
 * sees. It never carries the endpoint, the status code, the response body or the credential
 * reference: those are operator facts, and a model that repeats its context back is a way for them to
 * leave. The reason a model needs is what it can do differently, which is all these sentences say.</p>
 */
final class McpRefusal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** What the server did, in terms an operator can act on and a model cannot mine. */
    enum Reason {
        /** No answer arrived, or one arrived with a status this bundle does not accept. */
        SERVER_UNREACHABLE,
        /** The server did not answer inside the profile's deadline, or the run's. */
        SERVER_TIMED_OUT,
        /** The answer was larger than the profile's ceiling. */
        SERVER_RESPONSE_TOO_LARGE,
        /** The answer was not a readable JSON-RPC response. */
        SERVER_RESPONSE_UNREADABLE,
        /** The server answered with a JSON-RPC error rather than a result. */
        SERVER_REFUSED,
        /**
         * The managed channel refused to send the request at all.
         *
         * <p>Almost always one thing in practice: an operator's package grant that does not name the
         * headers MCP needs. This bundle sends {@code accept}, {@code mcp-protocol-version} and,
         * once a stateful server issues one, {@code mcp-session-id} — and a grant listing only
         * {@code content-type} rejects the request before it leaves. Its own reason because
         * "unreachable" would send that operator to check a server that is running perfectly.</p>
         */
        SERVER_REQUEST_REFUSED,
        /**
         * The model asked for a tool the operator's profile does not permit.
         *
         * <p>Reachable only through a name the model invented, because a tool outside the allow-list
         * is never placed in the tool list to begin with. It is nonetheless a real condition and not
         * a defensive impossibility: a model that saw {@code alpha__search} can write
         * {@code alpha__delete} without ever having been told it exists.</p>
         */
        TOOL_NOT_ALLOWED,
        /** The model's {@code arguments} were not a JSON object. */
        ARGUMENTS_UNREADABLE,
        /**
         * Two declared servers would expose one tool under the same name.
         *
         * <p>Discovery-only. Letting the second registration win, or the first, is the same defect
         * with a different victim: a call would reach a server nobody chose.</p>
         */
        EXPOSED_NAME_COLLISION
    }

    private final Reason reason;

    McpRefusal(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    /** What the model is told, which is what it can do about it and nothing else. */
    String forModel() {
        return switch (reason) {
            case SERVER_UNREACHABLE -> "The server providing this tool did not answer. Do not retry "
                    + "it; use another tool or answer without it.";
            case SERVER_TIMED_OUT -> "The server providing this tool took too long to answer. Do not "
                    + "retry it; use another tool or answer without it.";
            case SERVER_RESPONSE_TOO_LARGE -> "The server's answer was too large to read. Ask for "
                    + "less, or use another tool.";
            case SERVER_RESPONSE_UNREADABLE -> "The server's answer could not be read. Do not retry "
                    + "it; use another tool or answer without it.";
            case SERVER_REFUSED -> "The server refused this call. Check your arguments, or use "
                    + "another tool.";
            case SERVER_REQUEST_REFUSED -> "This tool cannot be reached in this deployment. Do not "
                    + "retry it; use another tool or answer without it.";
            case TOOL_NOT_ALLOWED -> "That tool is not available to you. Call only the tools listed "
                    + "in this request.";
            case ARGUMENTS_UNREADABLE -> "The arguments were not a JSON object. Call the tool again "
                    + "with arguments matching its schema.";
            case EXPOSED_NAME_COLLISION -> "That tool is not available to you. Call only the tools "
                    + "listed in this request.";
        };
    }

    /** The node-level failure this refusal becomes when it happens before the loop starts. */
    AgentException.Code asNodeFailure() {
        return switch (reason) {
            case SERVER_UNREACHABLE -> AgentException.Code.MCP_SERVER_UNREACHABLE;
            case SERVER_TIMED_OUT -> AgentException.Code.MCP_SERVER_TIMED_OUT;
            case SERVER_RESPONSE_TOO_LARGE -> AgentException.Code.MCP_RESPONSE_TOO_LARGE;
            case SERVER_RESPONSE_UNREADABLE, SERVER_REFUSED ->
                    AgentException.Code.MCP_RESPONSE_UNREADABLE;
            // The existing code, not a new one: the channel refused this destination-and-request, and
            // DESTINATION_REFUSED is where an operator already looks for "my grant did not allow it".
            case SERVER_REQUEST_REFUSED -> AgentException.Code.DESTINATION_REFUSED;
            case EXPOSED_NAME_COLLISION -> AgentException.Code.MCP_TOOL_NAME_COLLISION;
            // Neither is reachable during discovery: no model has spoken yet, so no name has been
            // invented and no arguments have been written. Mapped rather than thrown on, because an
            // exhaustive switch that cannot be completed is a maintenance trap for the next code.
            case TOOL_NOT_ALLOWED, ARGUMENTS_UNREADABLE -> AgentException.Code.MCP_RESPONSE_UNREADABLE;
        };
    }
}
