package ai.ravenroot.server.assistant.provider;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The seam a model provider plugs into under the provider-seam contract.
 *
 * <h2>What an implementation of this interface is, and what it must never become</h2>
 * <p>It is a <b>translator</b>. It converts the provider-neutral turn below into one provider's wire
 * format, performs exactly one request, and converts the answer back. It holds no retry policy, no
 * loop, no tool execution and no conversation state — all four live in
 * {@code AssistantService}, because all four are decisions about the author's session rather than
 * about a wire format, and a per-provider copy of them is four places for them to diverge.</p>
 *
 * <p><b>It does not construct its own HTTP client.</b> The composition root builds one through
 * {@code EgressHttpClients} — the single constructor required by SEC-10, which cannot follow a
 * redirect, cannot be proxied and cannot skip TLS validation — and hands it in. An adapter that called
 * {@code HttpClient.newHttpClient()} itself would open a socket outside the only egress control this
 * product has, which is the exact hole ADR 0025's "browser-direct provider calls" rejection exists to
 * keep closed on the other side of the wire.</p>
 *
 * <h2>Why the port expresses tool calling while remaining read-only</h2>
 * <p>Because read-only constrains what a tool <em>does</em>, not whether tools exist. The worked
 * use case — look at a failing run, read the runtime view, then the event
 * stream, and work out why — requires the model to decide <em>mid-turn</em> which context it needs.
 * Pre-composed context cannot express that: the composer would have to guess before the question is
 * read. So the port carries tool calls, and every tool behind it is a read
 * performed under the author's own authorization.</p>
 */
public interface AssistantProvider {

    /** The operator-facing provider id, e.g. {@code anthropic}. A display name; never a destination. */
    String id();

    /** The single destination this adapter will call. Validated against the operator allowlist. */
    URI endpoint();

    /**
     * Whether calling this adapter sends bytes out of the process.
     *
     * <h4>Why this is an abstract method and not a default, and not a check on the provider id</h4>
     * <p>It is what the consent gate is keyed on. {@code AssistantService} refuses to hold any
     * adapter that answers {@code true} while no {@link ai.ravenroot.server.assistant.AssistantConsentStore}
     * is present, so the exemption belongs to <em>adapters that do not egress</em> rather than to one
     * named provider.</p>
     *
     * <p>The earlier shape gated the Anthropic path specifically. A second network provider added by
     * copying the scripted adapter's early return — the most visible pattern in the file — would have
     * bypassed consent entirely, and no test would have caught it. Keying on identity means the gate
     * has to be remembered once per adapter; keying on a property the adapter must declare means it
     * cannot be forgotten.</p>
     *
     * <p><b>No default</b>, for the same reason {@code AssistantOutcome.Reason#wireToken} is a
     * constructor argument: a default is a value nobody chose, and the safe default here
     * ({@code true}) would be the one an author of a genuinely local adapter would quietly override
     * while the unsafe one ({@code false}) would silently exempt every new network adapter. Making it
     * abstract turns "does this thing reach the network?" into a question the compiler forces every
     * adapter author to answer.</p>
     */
    boolean egresses();

    /**
     * Performs one provider turn.
     *
     * @throws AssistantProviderException for every fault, carrying the named reason the panel will
     *                                    show. Deliberately a checked exception: an adapter that
     *                                    swallowed a transport failure and returned an empty turn
     *                                    would reintroduce the empty-assistant-turn defect at the one
     *                                    boundary this design most needs it closed.
     */
    Turn complete(Request request) throws AssistantProviderException;

    /**
     * One provider turn's input.
     *
     * @param model        operator-configured model id
     * @param system       the assembled instruction pack. Product content, assembled server-side.
     * @param messages     the conversation so far, oldest first, ending with the author's turn or with
     *                     the tool results the previous turn asked for
     * @param tools        the tools the model may call this turn. Empty is legitimate.
     * @param maxTokens    output ceiling for this turn; bounds thinking and visible text together on
     *                     providers whose reasoning is billed as output
     */
    record Request(String model, String system, List<Message> messages, List<ToolSpec> tools,
                   int maxTokens) {
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    /** {@code author} is the human; {@code assistant} is the model. No third role is representable. */
    enum Role { AUTHOR, ASSISTANT }

    record Message(Role role, List<Content> content) {
        public Message {
            content = List.copyOf(content);
        }

        public static Message author(String text) {
            return new Message(Role.AUTHOR, List.of(new Content.Text(text)));
        }
    }

    /**
     * A content block. Sealed, so the adapter's translation is an exhaustive switch and a new block
     * type cannot reach a provider as a silently dropped element.
     */
    sealed interface Content {
        /** Plain text, in either direction. */
        record Text(String text) implements Content { }

        /**
         * The model asking for a tool. {@code input} is the provider's own JSON object for the tool's
         * arguments, carried opaquely: the service parses it against the tool's schema, and nothing
         * between here and there interprets it.
         */
        record ToolUse(String id, String name, String inputJson) implements Content { }

        /**
         * The answer to a {@link ToolUse}. {@code isError} is carried rather than encoded into the
         * text so that a tool refusal — including an <em>authorization denial</em> — reaches the model
         * as a failed tool call it can reason about, instead of as prose it might mistake for data.
         */
        record ToolResult(String toolUseId, String content, boolean isError) implements Content { }
    }

    /**
     * A tool the model may call.
     *
     * @param inputSchemaJson a JSON Schema object, authored in product code. Never derived from a
     *                        graph, a payload or a chat turn.
     */
    record ToolSpec(String name, String description, String inputSchemaJson) { }

    /**
     * What one provider turn produced. Sealed for the same reason {@code AssistantOutcome} is: the
     * service's handling of it is an exhaustive switch.
     */
    sealed interface Turn {
        /** The model's answer. {@code truncated} means it stopped at the output ceiling. */
        record Answer(String text, String model, boolean truncated) implements Turn { }

        /**
         * The model wants tools run. {@code assistantContent} is the assistant turn exactly as the
         * provider returned it, carried back verbatim on the next request — providers reject an echoed
         * turn that has been reconstructed rather than replayed.
         */
        record ToolCalls(List<Content.ToolUse> calls, String model, List<Content> assistantContent)
                implements Turn {
            public ToolCalls {
                calls = List.copyOf(calls);
                assistantContent = List.copyOf(assistantContent);
            }
        }

        /**
         * The provider's safety classifiers declined. A distinct member because it arrives as a
         * <b>successful 200</b>, not an error status, and treating it as one produces either a
         * fabricated empty answer or a misleading "provider unavailable".
         */
        record Refused(String category) implements Turn { }
    }

    /** Diagnostics the service may log. Never contains a credential; see {@code AssistantCredential}. */
    default Map<String, String> describe() {
        return Map.of("provider", id(), "endpoint", endpoint().toString());
    }
}
