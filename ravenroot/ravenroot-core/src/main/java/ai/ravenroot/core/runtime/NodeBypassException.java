package ai.ravenroot.core.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A graph refused because of what it declares about switching a node off.
 *
 * <h2>The public/diagnostic split</h2>
 * <p>Identical discipline to {@link NodeRuntimeNatureException}, and adopted here for consistency
 * rather than because a bypass is a privilege: {@link #getMessage()} is assembled exclusively from
 * text authored in this file, and everything derived from the submitted document — node id, node
 * kind, the declared value — reaches {@link #diagnosticDetail()} and nothing else. A caller that
 * renders the message into an HTTP response leaks nothing; an operator with the server-side record
 * can still say precisely which node did what.</p>
 *
 * <p>The reason it is worth the same discipline for a non-privilege refusal is that both refusals
 * leave by the same door. Once one graph-validation exception echoes the document and its neighbour
 * does not, whoever wires the response has to know which is which, and the safe default stops being
 * the default.</p>
 */
public final class NodeBypassException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * Why a graph was refused. Each constant owns its complete public sentence, so no call site can
     * pass a {@code String} into the message and interpolating graph content is not expressible.
     */
    public enum Reason {
        DECLARED_ON_NON_BEHAVIOR_NODE(
                "was declared on a node that has no behaviour to skip. Only a behavior node executes "
                        + "anything; on a start node, on the end or error terminal, or on a "
                        + "structural passthrough there is nothing to switch off, so the flag would "
                        + "be preserved in the document, shown in the editor and change nothing at "
                        + "run time — a promise the runtime cannot keep"),
        UNPARSEABLE_VALUE(
                "carries a value that is neither 'true' nor 'false'. It is refused rather than read "
                        + "as 'false', because reading it as 'false' would execute a node the author "
                        + "believed was switched off, which is the exact failure the flag exists to "
                        + "prevent");

        private final String sentence;

        Reason(String sentence) {
            this.sentence = sentence;
        }

        String sentence() {
            return sentence;
        }
    }

    private final transient Reason reason;
    private final transient Map<String, Object> diagnosticDetail;

    NodeBypassException(Reason reason, Map<String, Object> diagnosticDetail) {
        super("Graph property '" + ai.ravenroot.api.catalog.NodeBypassProperty.NAME + "' "
                + reason.sentence());
        this.reason = reason;
        this.diagnosticDetail = Map.copyOf(new LinkedHashMap<>(diagnosticDetail));
    }

    public Reason reason() {
        return reason;
    }

    /**
     * Everything derived from the submitted graph: the node id, its kind, the behavior name and the
     * declared value. Never part of {@link #getMessage()}; route it to a server-side sink the way the
     * surrounding module already records diagnostics.
     *
     * @return an immutable diagnostic map
     */
    public Map<String, Object> diagnosticDetail() {
        return diagnosticDetail;
    }
}
