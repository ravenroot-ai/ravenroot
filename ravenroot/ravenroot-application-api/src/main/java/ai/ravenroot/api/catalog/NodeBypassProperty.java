package ai.ravenroot.api.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The well-known node property through which a graph author switches <strong>one node</strong> off:
 * the node does not execute its behaviour, and the traversal continues past it.
 *
 * <h2>What "switched off" means, exactly</h2>
 * <p>The node emits the default outcome — {@code GraphEdge.DEFAULT_OUTCOME}, {@code "continue"} — and
 * the payload it received, unchanged. That holds for a node that would normally <em>choose</em> among
 * several outcomes too: a decision node that did not run has no branch to pick, and simulating one
 * would be a fabricated answer wearing the node's name. So it takes the default edge, and an author
 * who switches off a decision node must know that its non-default branches are not taken. The runtime
 * says so rather than hiding it: those edges are reported in
 * {@code GraphExecutionResult#untakenEdges()}, the same declaration the command bypass already makes.
 * </p>
 *
 * <h2>Why this is an ordinary unreserved name</h2>
 * <p>The {@code ravenroot.} namespace is refused from graph content outright — at ingest by
 * {@code GraphManager} and again for programmatically-built definitions by
 * {@code BehaviorPropertySchema}. A flag the graph may never write would be a flag no author could
 * set, which is the whole feature. So the name is unreserved, deliberately, for the same reason
 * {@link NodeRuntimeNatureProperty} and {@link RecoveryRepeatabilityProperty} are: the reserved
 * namespace protects platform-asserted <em>facts</em> from author forgery, and this is an author
 * <em>choice</em>. It grants no authority the author lacks — it only subtracts execution from a node
 * the author already owns. {@code ReservedGraphProperties} therefore stays closed, with no
 * exception.</p>
 *
 * <p>The accepted cost is repeated here because this is where a reader
 * will look for it: an <strong>older runtime</strong> that receives a graph carrying this key does
 * not recognise it, preserves it as an ordinary unknown property, and <strong>executes the node the
 * author switched off</strong>. The degradation direction is "runs more than intended", not "runs
 * less". It is documented, not solved here; solving it would mean an exception to the reserved
 * namespace for a single field, and {@code runtime.nature} already accepted the same trade.</p>
 *
 * <h2>Platform-owned: no descriptor may declare it</h2>
 * <p>Same shape as {@link NodeRuntimeNatureProperty}, and for the same anti-collision reason. The
 * legal values are fixed here — {@code "true"} and {@code "false"} — so a behavior that declared a
 * property with this name would hold a second authority over one key: its own
 * {@link NodePropertyDescriptor#type()}, {@link NodePropertyDescriptor#allowedValues()} and
 * {@link NodePropertyDescriptor#defaultValue()} against the platform's. They could disagree, and
 * whichever was consulted second would silently decide whether a node runs.
 * {@link #validateShape(NodeTypeDescriptor)} refuses that at catalog load, on the one path every
 * registration takes.</p>
 *
 * <h2>Not validated by {@code BehaviorPropertySchema}</h2>
 * <p>That class returns early for non-behavior nodes and for behaviors absent from the catalog. The
 * first early return is a hole here — a {@code START} or {@code END} node carrying this flag is a
 * statement with no referent, because there is no behaviour to skip, and it must be refused
 * with a message that says why. So the flag is checked by {@code NodeBypassValidator}, which runs
 * unconditionally over every node, exactly as {@code NodeRuntimeNatureValidator} does.</p>
 *
 * <p>The <em>second</em> early return is deliberately not mirrored, and this is where this property
 * parts company with {@code runtime.nature}. A nature is a privilege the catalog grants, so an
 * uncatalogued behavior may not declare one. A bypass is the opposite: it removes execution. Its
 * motivating case is precisely a node the deployment cannot provision — an AI node with no adapter
 * configured, an artifact not created yet — so refusing the flag on an uncatalogued behavior would
 * refuse it on the graphs it exists for.</p>
 *
 * <h2>Reading is a plain map lookup, and here that is safe</h2>
 * <p>{@link NodeRuntimeNatureProperty#effectiveNature} deliberately refuses to answer from instance
 * values alone, because "absent" there means "the descriptor's default" and only the descriptor knows
 * it. Absent here means {@code false} for every node in every catalog, so there is no descriptor-held
 * default for a raw lookup to get wrong, and {@link #isBypassed(Map)} takes the map alone.</p>
 */
public final class NodeBypassProperty {

    /** The well-known name. Unreserved, and deliberately so — see this class's Javadoc. */
    public static final String NAME = "execution.bypass";

    private NodeBypassProperty() {
    }

    /**
     * The stable serialized values, for editors and diagnostics.
     *
     * @return the only two legal contents of {@link #NAME}
     */
    public static List<String> allowedValues() {
        return List.of("true", "false");
    }

    /**
     * Whether {@code instanceValues} carries the property at all, however malformed its value.
     *
     * @param instanceValues node property map, possibly {@code null}
     * @return {@code true} when the map contains {@link #NAME}, including a {@code null} or invalid value
     */
    public static boolean declaredBy(Map<String, Object> instanceValues) {
        return instanceValues != null && instanceValues.containsKey(NAME);
    }

    /**
     * Parses a declared value, strictly.
     *
     * <p>{@link Boolean} is accepted alongside {@link String} because GraphML carries typed scalars:
     * a document that declares {@code attr.type="boolean"} arrives here as a {@code Boolean}, and a
     * document that declares it a string arrives as {@code "true"}. Both spell the same author
     * intent, and refusing one of them would make the flag depend on how the exporting editor typed
     * the key rather than on what the author set.</p>
     *
     * <p>Anything else is {@link Optional#empty()} rather than {@code false}. Defaulting a typo to
     * "not bypassed" would run a node the author believed was switched off, which is the one failure
     * mode this flag exists to prevent; {@code NodeBypassValidator} turns the empty answer into a
     * refusal of the whole graph, before anything executes.</p>
     *
     * @param declared the raw property value, possibly {@code null}
     * @return the parsed flag, or empty when the value is neither {@code true} nor {@code false}
     */
    public static Optional<Boolean> parse(Object declared) {
        if (declared instanceof Boolean flag) {
            return Optional.of(flag);
        }
        if (declared instanceof CharSequence text) {
            String normalized = text.toString().trim();
            if (normalized.equalsIgnoreCase("true")) {
                return Optional.of(Boolean.TRUE);
            }
            if (normalized.equalsIgnoreCase("false")) {
                return Optional.of(Boolean.FALSE);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether this node usage is switched off. Absent means {@code false}.
     *
     * <p>Only ever called after {@code NodeBypassValidator} has accepted the graph, so an unparseable
     * value cannot reach here. If one somehow does, this answers {@code false} — the node executes —
     * because the alternative is silently skipping a node on the strength of a value nobody could
     * read, and the validator, not this method, is the control that must have failed.
     *
     * @param instanceValues the node's properties, possibly {@code null}
     * @return {@code true} only for an explicit, well-formed {@code true}
     */
    public static boolean isBypassed(Map<String, Object> instanceValues) {
        if (instanceValues == null) {
            return false;
        }
        return parse(instanceValues.get(NAME)).orElse(Boolean.FALSE);
    }

    /**
     * Refuses a descriptor that declares the platform-owned name as one of its own properties.
     *
     * <p>Called by {@link NodeTypeDescriptorValidator#validate(NodeTypeDescriptor)}, so it runs on the
     * one path every registration takes — built-ins, the legacy handler path, SDK node packages and
     * plugin bundles alike.</p>
     *
     * @param descriptor catalog entry whose behavior properties are checked; {@code null} is accepted
     * @throws IllegalArgumentException when the entry declares the platform-owned property name
     */
    public static void validateShape(NodeTypeDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        boolean declared = descriptor.properties().stream()
                .anyMatch(property -> NAME.equals(property.name()));
        if (declared) {
            throw new IllegalArgumentException("Behavior '" + descriptor.behavior() + "' property '"
                    + NAME + "' is platform-owned and cannot be declared as a behavior property; "
                    + "its only legal values are " + allowedValues() + ", fixed by the platform, "
                    + "and a descriptor declaring its own type, allowed values or default would be a "
                    + "second authority over the one key that decides whether a node runs at all");
        }
    }
}
