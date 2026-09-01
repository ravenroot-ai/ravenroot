package ai.ravenroot.extensions.ai;

/**
 * The only failure the {@code agent} node lets out, carrying a closed vocabulary and nothing else.
 *
 * <h2>Why a second exception type rather than more codes on {@link LlmPromptException}</h2>
 * <p>The two nodes fail differently, and the difference is the loop. {@code llm-prompt} can only
 * fail at one call; this node can exhaust turns, exhaust tokens, or be handed a tool call it cannot
 * resolve — three failures the other node has no way to reach. Widening one vocabulary to cover both
 * would leave every reader of either node to work out which half of the enum applies to it, reproducing
 * the ambiguity that splitting {@code UnconfiguredAdapterRefusal} avoids.</p>
 *
 * <p>The rule the two types <em>do</em> share is the one that matters: <b>no message interpolates a
 * prompt, a payload, a credential reference, an endpoint, a tool argument or a response body.</b>
 * Instructions and an objective render graph content, so they can carry personal data; a tool result
 * and a model answer are remote text. A failure message reaches the execution record and the
 * structured log, and is the quietest way for either to escape.</p>
 */
public final class AgentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** What went wrong, in terms an operator can act on and a graph author cannot mine. */
    public enum Code {
        /** The node's {@code provider} property names no profile this deployment declared. */
        PROFILE_UNKNOWN,
        /** The instructions or the objective could not be rendered against the incoming value. */
        TEMPLATE_UNRENDERABLE,
        /** The managed channel refused the destination, the protocol or the name resolution. */
        DESTINATION_REFUSED,
        /** The managed channel had no credential for the profile's binding. */
        CREDENTIAL_UNAVAILABLE,
        /** The endpoint answered with a status this bundle does not accept. */
        ENDPOINT_REJECTED,
        /** The response could not be read as an OpenAI-compatible chat turn. */
        RESPONSE_UNREADABLE,
        /** The response was larger than the profile's ceiling. */
        RESPONSE_TOO_LARGE,
        /** The endpoint reported a content filter rather than a turn. */
        COMPLETION_REFUSED,
        /** The endpoint answered with neither an answer nor a tool call. */
        COMPLETION_EMPTY,
        /**
         * The turn asked for tools and not one of the requests could be acted on.
         *
         * <p>Its own code, and not {@link #COMPLETION_EMPTY}: such a turn usually carries text as
         * well — a planning preamble — so "empty" would be false, and classifying it as an answer
         * would end the loop on turn one and report the preamble as the agent's result. A tool call
         * without an id cannot be answered at all, because the reply has to name the call it
         * answers.</p>
         */
        TOOL_CALL_UNREADABLE,
        /**
         * The loop reached {@code maxTurns} while the model was still asking for tools.
         *
         * <p>Its own code, and not {@link #DEADLINE_EXCEEDED}: a model that loops between two tools
         * inside a generous deadline and a model that is merely slow need different answers from an
         * operator, and one code for both would send them to the wrong one.</p>
         */
        TURN_BUDGET_EXHAUSTED,
        /** Reported token usage passed the node's cumulative ceiling. */
        TOKEN_BUDGET_EXHAUSTED,
        /** The deadline passed, or the call was cancelled. */
        DEADLINE_EXCEEDED,
        /** This package holds no managed HTTP grant, or the profile's admission is full. */
        CAPACITY_UNAVAILABLE,
        /**
         * The node's {@code mcpServers} property names a server this deployment did not declare.
         *
         * <p>Separate from {@link #PROFILE_UNKNOWN} because the two send an operator to two different
         * variables — {@code RAVENROOT_LLM_PROFILE_<hex(name)>} and
         * {@code RAVENROOT_MCP_SERVER_<hex(name)>} — and a node may well have a correct model profile
         * and a mistyped server. The hint carries the server name, which is author-written and
         * masked, and never the endpoint.</p>
         */
        MCP_PROFILE_UNKNOWN,
        /**
         * A declared MCP server did not answer during discovery, or answered with a rejected status.
         *
         * <p>The three server codes are separate on purpose, and the reason is the same one that
         * separated {@link #TURN_BUDGET_EXHAUSTED} from {@link #DEADLINE_EXCEEDED}: an operator
         * looking at "the server failed" cannot tell which of their three settings to change, while
         * "unreachable", "timed out" and "answered too much" each name one.</p>
         *
         * <p>These are discovery failures — before the model has been told anything, so a declared
         * tool that does not exist for this run makes the node unbuildable. The identical conditions
         * met <em>during</em> the loop are not these codes at all: they become {@code tool} messages
         * the model reads, because by then it has already been promised the tool and can still finish
         * another way. See {@link McpRefusal}.</p>
         */
        MCP_SERVER_UNREACHABLE,
        /** A declared MCP server did not answer inside its deadline, or the run's, during discovery. */
        MCP_SERVER_TIMED_OUT,
        /** A declared MCP server's discovery answer was larger than the profile's ceiling. */
        MCP_RESPONSE_TOO_LARGE,
        /** A declared MCP server's discovery answer was not a readable JSON-RPC response. */
        MCP_RESPONSE_UNREADABLE,
        /**
         * Two declared servers would expose one tool under the same name.
         *
         * <p>A refusal and not a tie-break. Resolving it by declaration order is precisely how a call
         * reaches the wrong server with nobody noticing, which is the defect the exposed-name scheme
         * of {@link McpProfile#exposedName(String)} exists to make impossible.</p>
         */
        MCP_TOOL_NAME_COLLISION,
        /**
         * The node declares more MCP servers than {@code AgentNodeBehavior.MAX_MCP_SERVERS}.
         *
         * <p>A refusal and not a truncation. Truncating would give the author an agent missing tools
         * they declared, working well enough to reach production before anyone noticed which ones
         * were gone.</p>
         */
        MCP_TOO_MANY_SERVERS,
        /** Transport failed below the application layer. */
        TRANSPORT_UNAVAILABLE
    }

    private final Code code;
    private final String hint;

    public AgentException(Code code) {
        this(code, "");
    }

    /**
     * @param hint a short, author-written token this failure is about — a template token, a profile
     *     name. NEVER a payload, a response body, a tool argument, a credential reference or an
     *     endpoint.
     */
    public AgentException(Code code, String hint) {
        super(hint == null || hint.isEmpty() ? code.name() : code.name() + ": " + hint);
        this.code = code;
        this.hint = hint == null ? "" : hint;
    }

    public Code code() {
        return code;
    }

    /** The token this failure is about, or the empty string. */
    public String hint() {
        return hint;
    }
}
