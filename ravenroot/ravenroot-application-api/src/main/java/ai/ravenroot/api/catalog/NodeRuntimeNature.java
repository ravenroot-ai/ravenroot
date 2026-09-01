package ai.ravenroot.api.catalog;

import java.util.Optional;

/**
 * The declared runtime lifecycle of one node usage (ADR 0024 §2).
 *
 * <h2>Lifecycle, never concurrency or replica count</h2>
 * <p>A nature says <em>when a runtime entity for this node exists and how many of it may be
 * authoritative</em>. It does not say how much work runs in parallel, how many replicas a deployment
 * schedules, or what a node's throughput ceiling is. Those are capacity and placement, declared
 * separately by {@code runtime.maxConcurrency}, under a trusted catalog ceiling (ADR 0024 §5):
 * nature and capacity are independent, and graph content can never raise the catalog ceiling by
 * choosing a nature. Read the
 * two together and {@code AUTHORITY} looks like "one replica" — it is not; it is "one
 * <em>authoritative</em> entity for a declared scope", which is a correctness statement about
 * ownership, not a sizing statement about hardware.</p>
 *
 * <h2>Declared, never inferred</h2>
 * <p>ADR 0024 explicitly rejects deriving lifecycle from topology: incoming and outgoing edges do not
 * prove whether a behavior owns a listener, an authority or keyed state. Nothing in this package
 * looks at graph shape. The value comes from the trusted catalog, or from a graph choosing among what
 * the catalog permits, and from nowhere else.</p>
 *
 * <h2>Absence means {@link #WORKER}</h2>
 * <p>A descriptor that declares nothing and a node that declares nothing both resolve to
 * {@code WORKER}, which is what every graph authored before runtime natures existed means. Absence is deliberately
 * <em>not</em> represented by a {@code WORKER} constant stored in the model: {@link NodeTypeDescriptor}
 * keeps a null default and an empty allowed set to mean "said nothing", because "said nothing" and
 * "declared WORKER" are different statements and only one of them may be silently widened by the
 * source-capability derivation in {@code BehaviorRegistry}. This is the same distinction
 * {@link NodePropertyDescriptor}'s conditions draw for {@code visibleWhen}.</p>
 *
 * <h2>Serialized identifiers are stable</h2>
 * <p>{@link #name()} is the wire form, in the catalog API, in the {@code runtime.nature} graph
 * property and in every future persisted shape. Author-facing wording may differ — ADR 0024 names
 * "Authoritative manager" as an example — but that wording is a display concern owned elsewhere and
 * is deliberately absent from this enum, so no code can come to depend on a label that is still an
 * open product decision.</p>
 */
public enum NodeRuntimeNature {

    /**
     * Default. No runtime entity exists merely because the deployment is active; reaching the node
     * creates one isolated instance for that invocation, released on completion, terminal failure or
     * cancellation. Concurrent arrivals create distinct instances, subject to admission limits.
     *
     * <p>The independent per-node/per-traversal admission limit applies separately; choosing WORKER
     * itself neither raises nor lowers that limit.</p>
     */
    WORKER,

    /**
     * One demand-created runtime entity for one logical node in one traversal. Re-entering the same
     * node (including through a cycle) reuses that entity; a concurrent or later traversal receives
     * a different entity. The entity is released with its traversal and is never deployment-,
     * tenant-, or pod-resident.
     */
    TRAVERSAL,

    /**
     * A deployment-scoped inbound resource, started during deployment startup and ready before the
     * deployment reports {@code READY}. It may listen, poll or consume for a long time and creates new
     * traversals through trusted ingress.
     *
     * <p>Reached through {@code InboundSourceCapable}. {@code DefaultGraphDeployment} owns its inbound
     * resource, while {@code GraphRunner} owns a distinct resident dispatch actor for edge arrivals.
     * Its replica/partition policy is declared separately from its nature.</p>
     */
    SOURCE,

    /**
     * A uniquely authoritative manager or coordinator for a declared scope. In a cluster this requires
     * durable ownership, lease and fencing, and one local copy per pod is explicitly forbidden.
     *
     * <p><strong>Refused at deploy today.</strong> ADR 0023's durable registry, lease and fencing
     * token do not exist yet, so nothing can honour the uniqueness this value promises. A graph
     * whose effective nature resolves to {@code AUTHORITY} is refused before any actor is created
     * rather than silently given a per-pod resident, which would be the forbidden shape wearing the
     * declaration's name. It remains undeployable until those guarantees exist.</p>
     */
    AUTHORITY,

    /**
     * At most one authoritative runtime entity per declared logical key within its scope, supporting
     * stateful per-document, per-practice or per-customer processing without making the whole node a
     * global singleton.
     *
     * <p><strong>Refused at deploy today, and the key contract is deliberately unbuilt.</strong> Where
     * the key is declared, its syntax, its scope and its extraction remain unimplemented. Shipping a key
     * check here would mean shipping a check with nothing to reject; the deploy refusal below is the
     * only control currently applied to this value, and it is testable in both directions.</p>
     */
    KEYED;

    /** What a descriptor or a node that declares nothing means. */
    public static final NodeRuntimeNature DEFAULT = WORKER;

    /**
     * The nature named by {@code rawValue}, or empty when it names none.
     *
     * <p>Case-sensitive and exact after stripping, for the reason {@code RecoveryRepeatabilityProperty}
     * gives for the same choice: an approximate match here grants a lifecycle nobody declared. A value
     * this method rejects is refused loudly by {@code NodeRuntimeNatureValidator} rather than falling
     * back to {@link #DEFAULT} — a fallback would turn an author's typo into a silent demotion of an
     * {@code AUTHORITY} node to a worker, which is precisely the failure the declaration exists to
     * prevent.</p>
     * @param rawValue value read from {@code runtime.nature}; surrounding whitespace is ignored
     * @return the exactly named nature, or empty for {@code null}, blank, or unrecognized values
     */
    public static Optional<NodeRuntimeNature> parse(Object rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        String value = rawValue.toString().strip();
        for (NodeRuntimeNature nature : values()) {
            if (nature.name().equals(value)) {
                return Optional.of(nature);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this nature needs a resident runtime entity whose contract nothing implements yet.
     *
     * <p>The single definition behind the deploy refusal, so the set cannot drift between the check
     * and the diagnostic that explains it. Implementing residency must empty this predicate rather
     * than edit a list in two places, so the refusal tests invert visibly.</p>
     * @return {@code true} for {@link #AUTHORITY} and {@link #KEYED}, whose resident runtime contracts
     *         are not yet implemented
     */
    public boolean requiresUnimplementedResidency() {
        return this == AUTHORITY || this == KEYED;
    }
}
