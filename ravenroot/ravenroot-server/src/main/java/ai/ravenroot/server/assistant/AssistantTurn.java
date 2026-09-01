package ai.ravenroot.server.assistant;

import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;

import java.util.List;
import java.util.Map;

/**
 * One author turn as the panel sends it.
 *
 * <h2>The wire shape is the client's, not this file's</h2>
 * <p>{@code assistant-client.js}'s {@code send()} posts
 * {@code {prompt, context: {...}, attached: [...], document?: {...}}}, where {@code context} holds only
 * the classes the panel showed a chip for, {@code attached} names those actually attached, and the
 * optional document object binds a possible proposal to live editor state. This record reads that
 * shape and nothing wider: an unrecognised top-level key is ignored rather than forwarded, so a future
 * client that starts sending a field this build does not understand cannot smuggle it to a provider.</p>
 *
 * <h2>Bounded before it is anything else</h2>
 * <p>Parsing goes through {@link PayloadJson}, the bounded reader API-01 established, so an oversized
 * or deeply-nested body is refused <em>while</em> it is read rather than after a tree exists. The
 * budget is deliberately wider than {@link PayloadLimits#DEFAULTS}: the attached context legitimately
 * carries an open graph, and a control-plane payload budget applied to it would reject ordinary use.
 * Wider is not unbounded, which is the property that matters.</p>
 *
 * <h2>On delimiting the context in the prompt</h2>
 * <p>{@link #render()} wraps the attached context in labelled markers. That is formatting, and it is
 * <b>not counted as a security control</b> — ADR 0025 rejects prompt-level sandboxing by name, on the
 * grounds that a control which cannot fail a test earns no reliance. It is here so the model can tell
 * the author's question from the product data quoted alongside it, which is a quality property.</p>
 */
public record AssistantTurn(String prompt, String contextJson, List<String> attached,
                            String documentIncarnation, long documentRevision, String catalogDigest) {

    /**
     * Ceilings for one author turn.
     *
     * <p>1 MiB encoded is roughly four times the default control-plane payload budget and roughly a
     * tenth of the GraphML budget, which is the right band: the panel may attach a graph, but it is
     * attaching a description of one alongside a question, not submitting a program.</p>
     */
    public static final PayloadLimits TURN_LIMITS =
            new PayloadLimits(1024 * 1024, 32, 10_000, 100_000, 512 * 1024, 256);

    /** Longest author prompt accepted. Beyond this the panel is not asking a question. */
    public static final int MAX_PROMPT_CHARACTERS = 32_000;

    public AssistantTurn {
        attached = List.copyOf(attached);
        documentIncarnation = documentIncarnation == null || documentIncarnation.isBlank()
                ? null : documentIncarnation;
        catalogDigest = catalogDigest == null || catalogDigest.isBlank() ? null : catalogDigest;
        if (documentRevision < 0) {
            throw new IllegalArgumentException("the document revision cannot be negative");
        }
    }

    /** Compatibility constructor for read-only callers that have no open editor document. */
    public AssistantTurn(String prompt, String contextJson, List<String> attached) {
        this(prompt, contextJson, attached, null, 0L, null);
    }

    /**
     * Reads a turn, or explains why it is not one.
     *
     * @throws PayloadException when the body exceeds a budget or is not well-formed. The caller turns
     *                          that into one wire error rather than exposing the classified reason:
     *                          the author cannot act differently on {@code DEPTH_LIMIT_EXCEEDED} than
     *                          on {@code MALFORMED}, and the classified detail belongs in the
     *                          server-side record, not on the panel.
     */
    public static AssistantTurn read(byte[] body) throws PayloadException {
        PayloadValue parsed = PayloadJson.read(body, TURN_LIMITS);
        if (!(parsed instanceof PayloadValue.MapValue root)) {
            throw new IllegalArgumentException("an assistant turn must be a JSON object");
        }
        Map<String, PayloadValue> entries = root.entries();
        String prompt = entries.get("prompt") instanceof PayloadValue.TextValue text ? text.value() : "";
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("an assistant turn must carry a prompt");
        }
        if (prompt.length() > MAX_PROMPT_CHARACTERS) {
            throw new IllegalArgumentException("the prompt exceeds this deployment's limit");
        }
        String contextJson = entries.get("context") instanceof PayloadValue.MapValue context
                ? PayloadJson.write(context)
                : null;
        List<String> attached = entries.get("attached") instanceof PayloadValue.ListValue list
                ? list.values().stream()
                        .filter(PayloadValue.TextValue.class::isInstance)
                        .map(value -> ((PayloadValue.TextValue) value).value())
                        .toList()
                : List.of();
        String documentIncarnation = null;
        long documentRevision = 0L;
        String catalogDigest = null;
        if (entries.get("document") instanceof PayloadValue.MapValue document) {
            var documentEntries = document.entries();
            if (!documentEntries.keySet().equals(
                    java.util.Set.of("incarnation", "revision", "catalogDigest"))) {
                throw new IllegalArgumentException("the assistant document binding has unknown fields");
            }
            documentIncarnation = documentEntries.get("incarnation") instanceof PayloadValue.TextValue id
                    ? id.value() : null;
            documentRevision = documentEntries.get("revision") instanceof PayloadValue.IntegerValue revision
                    ? revision.value() : -1L;
            catalogDigest = documentEntries.get("catalogDigest") instanceof PayloadValue.TextValue digest
                    ? digest.value() : null;
            if (documentIncarnation == null || documentIncarnation.isBlank()
                    || documentIncarnation.length() > 256 || documentRevision < 0
                    || catalogDigest == null || catalogDigest.isBlank() || catalogDigest.length() > 256) {
                throw new IllegalArgumentException("the assistant document binding is invalid");
            }
        }
        return new AssistantTurn(prompt, contextJson, attached, documentIncarnation,
                documentRevision, catalogDigest);
    }

    /** Whether the service may offer the proposal-only tool for this turn. */
    public boolean hasDocumentBinding() {
        return documentIncarnation != null && catalogDigest != null;
    }

    /** The author's question, with whatever the panel disclosed alongside it. */
    public String render() {
        if (contextJson == null || contextJson.isBlank() || attached.isEmpty()) {
            return prompt;
        }
        return prompt
                + "\n\n--- Ravenroot context attached by the author's panel ("
                + String.join(", ", attached)
                + ") ---\n"
                + contextJson
                + "\n--- end of attached context ---";
    }
}
