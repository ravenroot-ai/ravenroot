package ai.ravenroot.core.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A graph refused because of what it declares about a node's runtime nature (ADR 0024 §2).
 *
 * <h2>The public/diagnostic split, and why this one is strict</h2>
 * <p>{@code BehaviorPropertySchema.BehaviorPropertyException} interpolates the offending value and
 * the node id straight into {@link #getMessage()}. That is tolerable for an ordinary schema defect —
 * a type mismatch in a field the author owns. It is not tolerable here, because this exception is
 * raised on the <em>privilege</em> path: it fires exactly when a submitted graph tried to claim a
 * lifecycle the trusted catalog did not grant it, which is the moment the server has decided the
 * input is not trustworthy. Echoing that input back is the information-disclosure asymmetry
 * {@code GraphMlRejection} was written to remove from the GraphML layer, and this class follows the
 * same discipline rather than reproducing the older one.</p>
 *
 * <p>So: {@link #getMessage()} is assembled exclusively from text authored in this file — the
 * property name, which is a platform constant, and a sentence naming the rule that was broken.
 * Everything derived from the document — node id, declared value, the behavior name, the permitted
 * set — reaches {@link #diagnosticDetail()} and nothing else. A caller that renders the message into
 * an HTTP response leaks nothing; an operator with the server-side record can still say precisely
 * which node did what.</p>
 *
 * <p>This deliberately makes the public message less immediately actionable than the schema
 * exception's. That is the trade the ruling took: a privilege refusal names what it refused and the
 * rule it refused it under, and the operator channel carries the rest.</p>
 */
public final class NodeRuntimeNatureException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /**
     * Why a graph was refused. Each constant owns its complete public sentence, so no call site can
     * pass a {@code String} into the message and interpolating graph content is not expressible.
     */
    public enum Reason {
        DECLARED_ON_NON_BEHAVIOR_NODE(
                "was declared on a node that is not a behavior node. Only a behavior node has a "
                        + "catalog entry, and only a catalog entry can permit a nature"),
        DECLARED_BY_UNCATALOGUED_BEHAVIOR(
                "was declared on a node whose behavior is not in the trusted catalog. A nature is a "
                        + "privilege granted by the catalog, so a behavior the catalog does not know "
                        + "cannot grant one, whatever the deployment's unknown-behavior mode allows "
                        + "for execution"),
        UNKNOWN_NATURE(
                "names a runtime nature that does not exist. It is refused rather than defaulted, "
                        + "because defaulting would turn a typo into a silent demotion to a worker"),
        NATURE_NOT_PERMITTED(
                "names a runtime nature the trusted catalog does not permit for this behavior. Graph "
                        + "content may choose among the natures a descriptor allows and may never "
                        + "escalate a behavior into a source or an authority the catalog withheld"),
        RESIDENCY_NOT_IMPLEMENTED(
                "resolves to a runtime nature whose residency contract is not implemented yet "
                        + "(#345, and cluster-wide ownership additionally #316). It is refused before "
                        + "any actor is created rather than silently given a per-pod resident, which "
                        + "is the shape ADR 0024 forbids wearing the declaration's name");

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

    NodeRuntimeNatureException(Reason reason, Map<String, Object> diagnosticDetail) {
        super("Graph property '" + ai.ravenroot.api.catalog.NodeRuntimeNatureProperty.NAME + "' "
                + reason.sentence());
        this.reason = reason;
        this.diagnosticDetail = Map.copyOf(new LinkedHashMap<>(diagnosticDetail));
    }

    public Reason reason() {
        return reason;
    }

    /**
     * Everything derived from the submitted graph: the node id, the behavior name, the declared value
     * and the permitted set. Never part of {@link #getMessage()}; route it to a server-side sink the
     * way the surrounding module already records diagnostics.
     */
    public Map<String, Object> diagnosticDetail() {
        return diagnosticDetail;
    }
}
