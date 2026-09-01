package ai.ravenroot.extensions.ai;

/**
 * The only failure this bundle lets out, carrying a closed vocabulary and nothing else.
 *
 * <p>No message interpolates a prompt, a payload, a credential reference, an endpoint or a response
 * body. A prompt renders {@code {{payload}}}, so it can carry personal data; a response is remote
 * text. Both would otherwise reach the execution record and the structured log through a failure
 * message, which is the quietest way for content to escape a runtime that is careful everywhere
 * else.</p>
 */
public final class LlmPromptException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** What went wrong, in terms an operator can act on and a graph author cannot mine. */
    public enum Code {
        /** The node's {@code provider} property names no profile this deployment declared. */
        PROFILE_UNKNOWN,
        /** The prompt could not be rendered against the value that arrived on the incoming edge. */
        PROMPT_UNRENDERABLE,
        /** The managed channel refused the destination, the protocol or the name resolution. */
        DESTINATION_REFUSED,
        /** The managed channel had no credential for the profile's binding. */
        CREDENTIAL_UNAVAILABLE,
        /** The endpoint answered with a status this bundle does not accept. */
        ENDPOINT_REJECTED,
        /** The response could not be read as an OpenAI-compatible chat completion. */
        RESPONSE_UNREADABLE,
        /** The response was larger than the profile's ceiling. */
        RESPONSE_TOO_LARGE,
        /** The endpoint reported a content filter rather than a completion. */
        COMPLETION_REFUSED,
        /** The endpoint answered successfully with no text in it. */
        COMPLETION_EMPTY,
        /** The deadline passed, or the call was cancelled. */
        DEADLINE_EXCEEDED,
        /** This package holds no managed HTTP grant, or the profile's admission is full. */
        CAPACITY_UNAVAILABLE,
        /** Transport failed below the application layer. */
        TRANSPORT_UNAVAILABLE
    }

    private final Code code;
    private final String hint;

    public LlmPromptException(Code code) {
        this(code, "");
    }

    /**
     * @param hint a short, author-written token this failure is about -- a prompt token, a profile
     *     name. NEVER a payload, a response body, a credential reference or an endpoint. There is
     *     exactly one caller today, {@code PromptTemplate}, and it passes a template token.
     */
    public LlmPromptException(Code code, String hint) {
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
