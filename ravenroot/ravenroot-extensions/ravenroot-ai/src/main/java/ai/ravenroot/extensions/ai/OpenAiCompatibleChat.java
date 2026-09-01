package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bundle's own OpenAI-compatible {@code /v1/chat/completions} adapter: the wire document, and
 * nothing else.
 *
 * <h2>Why the adapter is inside the bundle rather than beside it</h2>
 * <p>The issue's requirement is that this bundle carry its own adapter, and the runtime leaves no
 * other shape available. {@code ravenroot-adapter-openai-compatible} implements the
 * {@code ModelProvider} SPI and reaches the network through {@code ai.ravenroot.core.security.egress}
 * — the embedding route the embedding-surface contract redesignates that SPI for. A bundle takes the other
 * route: it implements {@code NodeBehavior} and reaches the network through the managed channel,
 * where the credential is placed by the runtime and never seen here. Two mechanically incompatible
 * transports, so what is reusable is the <em>document</em>, which is what this class is.</p>
 *
 * <p>Depending on that module as an artifact would not have worked either, and the reason is worth
 * recording rather than rediscovering: {@code plugin.sh build} gathers a bundle's third-party
 * dependencies with {@code dependency:copy-dependencies -DexcludeGroupIds=ai.ravenroot}, and Maven's
 * group filter is a prefix match — {@code ai.ravenroot.adapters} is excluded by it. The adapter jar
 * would never have entered the bundle.</p>
 *
 * <p><b>So this is a derivation, and the source is named:</b>
 * {@code ravenroot-adapter-openai-compatible}'s {@code OpenAiCompatibleModelProvider}, from which the
 * request writer, the finish-reason vocabulary, the parameter allowlist, the response reader and the
 * three rules stated below are carried over. The known cost is the copy's: a correction made there
 * does not reach here. {@code OpenAiCompatibleChatTest} pins what this copy promises.</p>
 *
 * <h2>Three rules carried over verbatim, because each of them is a control</h2>
 * <ol>
 *   <li><b>{@code stream: false} is stated, not assumed.</b> A server whose default is
 *   {@code text/event-stream} — at least one in this family can be configured that way — hands back a
 *   document {@link #readCompletion} cannot read, and the failure would point at the response rather
 *   than at the setting.</li>
 *   <li><b>Graph content goes in the {@code user} turn and never in a {@code system} message.</b> The
 *   system turn is the deployment's authority channel; putting an author's prompt there would let a
 *   graph grant itself standing the operator never gave it. This class sends no system message at
 *   all, so there is no branch to forget.</li>
 *   <li><b>{@code finish_reason} is read before the content is touched.</b> A safety refusal arrives
 *   as HTTP 200, and code that reads the content unconditionally hands the graph whatever the refusal
 *   left there as if it were the answer.</li>
 * </ol>
 */
final class OpenAiCompatibleChat {

    /** Stated rather than left to the far end's default. See rule 1 on this class. */
    static final boolean STREAM = false;

    /**
     * The finish reasons this bundle is willing to repeat. Anything else becomes {@code ""}.
     *
     * <p>{@code finish_reason} is a string the far end chooses. Passing it through would put
     * unaudited remote text into a node attribute named as if Ravenroot had authored it.</p>
     */
    private static final Set<String> KNOWN_FINISH_REASONS =
            Set.of("stop", "length", "content_filter", "tool_calls", "function_call");

    private OpenAiCompatibleChat() {
    }

    /**
     * One completion, projected onto the fields this bundle is willing to state.
     *
     * @param text the answer, never empty — an empty one is {@code COMPLETION_EMPTY} instead
     * @param finishReason a member of {@link #KNOWN_FINISH_REASONS}, or {@code ""}
     * @param truncated whether {@code finishReason} was {@code length}; unlike the SPI route, this
     *     one has somewhere to put it — the node writes it as an attribute, so a truncated answer is
     *     distinguishable from a complete one
     * @param promptTokens reported prompt tokens, when the endpoint reports them
     * @param completionTokens reported completion tokens, when the endpoint reports them
     */
    record Completion(String text, String finishReason, boolean truncated,
                      Optional<Long> promptTokens, Optional<Long> completionTokens) {
    }

    /**
     * Tuning a graph may ask for. A closed set: these four are declared properties of the node type,
     * so a graph can tune a completion and cannot reshape a request.
     */
    record Tuning(Optional<Long> maxTokens, Optional<Double> temperature,
                  Optional<Double> topP, Optional<Long> seed) {
    }

    /**
     * Writes the request as a {@link PayloadValue} tree serialised by the audited writer, rather than
     * assembling it from string fragments with an escaper. The tree form is what matters: a prompt is
     * graph content, and a hand-written writer is exactly what an author with a quote, a backslash or
     * a lone surrogate in their prompt would put to the test.
     */
    static byte[] writeRequest(String model, String prompt, Tuning tuning) {
        var userMessage = new LinkedHashMap<String, PayloadValue>();
        userMessage.put("role", PayloadValue.of("user"));
        userMessage.put("content", PayloadValue.of(prompt));

        var root = new LinkedHashMap<String, PayloadValue>();
        root.put("model", PayloadValue.of(model));
        root.put("stream", PayloadValue.of(STREAM));
        root.put("messages", PayloadValue.list(PayloadValue.map(userMessage)));
        tuning.maxTokens().ifPresent(value -> root.put("max_tokens", PayloadValue.of(value)));
        tuning.temperature().ifPresent(value -> root.put("temperature", PayloadValue.of(value)));
        tuning.topP().ifPresent(value -> root.put("top_p", PayloadValue.of(value)));
        tuning.seed().ifPresent(value -> root.put("seed", PayloadValue.of(value)));
        return PayloadJson.write(PayloadValue.map(root)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A 2xx body becomes a completion, or a named failure. It never becomes an empty answer.
     *
     * @param body the response bytes
     * @param maxResponseBytes the profile's ceiling, used as the reader's own encoded-byte budget so
     *     the transport bound and the parse bound cannot drift into a window where bytes are accepted
     *     off the wire only to be rejected once resident
     */
    static Completion readCompletion(byte[] body, int maxResponseBytes) {
        if (body.length > maxResponseBytes) {
            throw new LlmPromptException(LlmPromptException.Code.RESPONSE_TOO_LARGE);
        }
        PayloadValue parsed;
        try {
            // Deliberately not PayloadLimits.DEFAULTS: that profile caps a single text value at
            // 32 KiB, which a long model answer exceeds -- a control-plane payload budget applied to
            // a model response would turn "the model wrote a lot" into a parse failure.
            parsed = PayloadJson.read(body, new PayloadLimits(Math.max(1, maxResponseBytes), 32, 10_000,
                    200_000, Math.max(1, maxResponseBytes / 2), 256));
        } catch (RuntimeException unreadable) {
            // One clause covers a budget rejection, a malformed document and a text/event-stream body:
            // every one of them means this build could not read the response, so nothing was answered.
            throw new LlmPromptException(LlmPromptException.Code.RESPONSE_UNREADABLE);
        }
        if (!(parsed instanceof PayloadValue.MapValue root)) {
            throw new LlmPromptException(LlmPromptException.Code.RESPONSE_UNREADABLE);
        }
        Map<String, PayloadValue> entries = root.entries();
        if (!(entries.get("choices") instanceof PayloadValue.ListValue choices)
                || choices.values().isEmpty()
                || !(choices.values().get(0) instanceof PayloadValue.MapValue choice)) {
            throw new LlmPromptException(LlmPromptException.Code.RESPONSE_UNREADABLE);
        }
        Map<String, PayloadValue> firstChoice = choice.entries();

        // Rule 3: before the content is touched.
        String finishReason = knownFinishReason(text(firstChoice.get("finish_reason")));
        if ("content_filter".equals(finishReason)) {
            throw new LlmPromptException(LlmPromptException.Code.COMPLETION_REFUSED);
        }

        String answer = firstChoice.get("message") instanceof PayloadValue.MapValue message
                ? text(message.entries().get("content"))
                : null;
        if (answer == null || answer.isEmpty()) {
            // Covers three real shapes at once: no message object, `"content": null` (which several
            // endpoints send when the turn carried only tool calls -- which this bundle does not
            // support and must not silently render as an empty answer), and an empty string.
            throw new LlmPromptException(LlmPromptException.Code.COMPLETION_EMPTY);
        }
        return new Completion(answer, finishReason, "length".equals(finishReason),
                count(entries, "prompt_tokens"), count(entries, "completion_tokens"));
    }

    private static Optional<Long> count(Map<String, PayloadValue> entries, String member) {
        if (entries.get("usage") instanceof PayloadValue.MapValue usage
                && usage.entries().get(member) instanceof PayloadValue.IntegerValue value) {
            return Optional.of(value.value());
        }
        return Optional.empty();
    }

    /** {@code ""} for anything outside {@link #KNOWN_FINISH_REASONS}, {@code null} included. */
    private static String knownFinishReason(String reported) {
        return reported != null && KNOWN_FINISH_REASONS.contains(reported) ? reported : "";
    }

    private static String text(PayloadValue value) {
        return value instanceof PayloadValue.TextValue textValue ? textValue.value() : null;
    }
}
