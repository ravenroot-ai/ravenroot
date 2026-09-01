package ai.ravenroot.core.runtime;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.graph.ReservedGraphProperties;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates a graph's behavior properties against the trusted catalog (SEC-09).
 *
 * <h2>What the catalog is authoritative for</h2>
 * <p>The catalog — {@link NodeTypeDescriptor} obtained from the {@link BehaviorRegistry} — decides
 * <em>which</em> properties a behavior has, their {@link NodePropertyType}, whether they are
 * required, and which values are permitted. Graph content supplies only the <em>values</em>. A graph
 * cannot introduce an operative property, change a property's type, or widen its allowed values,
 * because nothing here reads a schema out of the graph.</p>
 *
 * <h2>Declared, unknown, reserved</h2>
 * <p>Three classes of property, and only the first two can appear in a valid graph:</p>
 * <ul>
 *   <li><strong>Declared</strong> — named by the behavior's catalog entry. Type-checked here, and
 *   interpreted at execution.</li>
 *   <li><strong>Unknown</strong> — anything else. <strong>Left entirely alone.</strong> Not
 *   type-checked, not defaulted, not interpreted, and preserved byte-for-byte on export. This is a
 *   requirement of SEC-09, not an omission: rejecting unknown properties would break the lossless
 *   round trip and violate the requirement rather than satisfy it.</li>
 *   <li><strong>Reserved</strong> — the {@code ravenroot.} namespace, already refused at ingest by
 *   {@link ai.ravenroot.core.graph.GraphManager}. It cannot reach this class through a parsed
 *   document, and is re-checked here only because a {@link GraphDefinition} can also be built
 *   programmatically, which bypasses the parser.</li>
 * </ul>
 *
 * <h2>Why validation happens at composition rather than at execution</h2>
 * <p>Before SEC-09 a malformed operative property surfaced as an
 * {@link IllegalArgumentException} from {@code NodeProperties} at the moment the node ran — that is,
 * after the graph was accepted, hashed, recorded in the execution store, and after every node
 * upstream of the faulty one had already produced its effects. Validating the whole graph before any
 * node is spawned turns that into a refused submission.</p>
 *
 * <h2>The one declared exception: a node that names no adapter (CORE-07)</h2>
 * <p>The rule above is composition-time validation of <em>graph defects</em>, and it holds for every
 * behavior. CORE-07 draws one boundary inside it: a property declared through
 * {@link NodePropertyDescriptor#adapterId} names a server-configured adapter, and leaving it blank
 * is not a defect in the graph — it is a node the deployment has not configured. Whether an adapter
 * exists is a fact about the server, and this class validates documents, not deployments.</p>
 * <p>So when a node leaves its adapter binding blank, required-ness is suspended <em>for that node
 * only</em>, the graph is admitted, and the node refuses when a traversal reaches it, on the same
 * path CORE-05 built for an adapter id that resolves to nothing. This removes an inconsistency
 * rather than adding one: before CORE-07, naming a provider that did not exist produced a graph you
 * could open and run, while leaving the same field blank refused the whole submission, so writing a
 * made-up id worked strictly better than being honest that the node was not configured yet.</p>
 * <p>Three things this exception deliberately does <strong>not</strong> do. It does not suspend type
 * or allowed-value checking: a value that is present is checked exactly as before. It does not
 * suspend the reserved-namespace refusal, which runs unconditionally and first. And it does not
 * reach any behavior that has not declared an adapter binding, so for every SDK-declared node a
 * missing required property remains a refused submission. The exemption is a property of the
 * catalog entry, not of this class — no behavior name appears here.</p>
 *
 * <p>Nodes whose behavior is <strong>not</strong> in the catalog are deliberately skipped: what
 * happens to an unknown behavior is SEC-09's third rule, which is handled separately
 * here. The pass-through path is left exactly as it was.</p>
 */
public final class BehaviorPropertySchema {

    private final BehaviorRegistry behaviors;

    public BehaviorPropertySchema(BehaviorRegistry behaviors) {
        this.behaviors = Objects.requireNonNull(behaviors, "behaviors");
    }

    /** Validates every behavior node in {@code graph}, throwing on the first violation. */
    public void validate(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        for (GraphNode node : graph.nodes()) {
            validateNode(node);
        }
    }

    private void validateNode(GraphNode node) {
        for (String key : node.properties().keySet()) {
            if (ReservedGraphProperties.isReserved(key)) {
                throw new BehaviorPropertyException(node.id(), key,
                        "the '" + ReservedGraphProperties.PREFIX + "' namespace is reserved for Ravenroot's own "
                                + "operative state and cannot be set by graph content");
            }
        }
        if (node.kind() != NodeKind.BEHAVIOR) {
            return;
        }
        Optional<NodeTypeDescriptor> catalogued = behaviors.descriptor(node.behavior());
        if (catalogued.isEmpty()) {
            // Unknown behavior. Separate from SEC-09 rules 1 and 2; the pass-through path is
            // unchanged and its fail-closed treatment belongs to rule 3.
            return;
        }
        List<NodePropertyDescriptor> declared = catalogued.get().properties();
        boolean unconfigured = namesNoAdapter(node, declared);
        for (NodePropertyDescriptor property : declared) {
            validateProperty(node, property, unconfigured, declared);
        }
    }

    /**
     * Whether {@code node} leaves an adapter binding blank, which makes it an unconfigured node
     * rather than a defective one (CORE-07).
     *
     * <p>Driven entirely by {@link NodePropertyDescriptor#adapterBinding()}, so no behavior name
     * appears here and a behavior that declares no binding can never reach the exemption.</p>
     *
     * <p>"Names no adapter" is decided by {@link NodePropertyDescriptor#adapterIdOf}, which is the
     * same call the behavior factories use to decide whether to refuse. That sharing is required, not
     * stylistic: this method grants an exemption from required-ness, the factory performs the
     * refusal that makes the exemption safe, and any value the two classify differently is one the
     * validator admits and the factory does not refuse. Spelling the check inline here — as
     * {@code isBlank()}, {@code trim()} or otherwise — is what reopens that gap. See
     * {@link NodePropertyDescriptor#adapterIdOf} for the shared predicate: this is no longer a two-party agreement,
     * the Ravenroot UI editor carries a JavaScript port of the same predicate.</p>
     */
    private static boolean namesNoAdapter(GraphNode node, List<NodePropertyDescriptor> declared) {
        for (NodePropertyDescriptor property : declared) {
            if (!property.adapterBinding()) {
                continue;
            }
            if (NodePropertyDescriptor.adapterIdOf(node.properties().get(property.name())).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this property is required for THIS node's current values.
     *
     * <h2>{@code requiredWhen} is the only thing a condition may change here</h2>
     * <p>A property with no {@code requiredWhen} is required exactly as declared, which is every
     * descriptor authored without a condition.
     *
     * <h2>{@code visibleWhen} deliberately has NO effect on validation</h2>
     * <p>This is the rule most easily satisfied in appearance and violated in fact, so it is
     * spelled out. A property the editor hides is still present in the payload, and there are only
     * two coherent treatments of it:
     * <ul>
     *   <li>skip validation for hidden properties — that is a <b>bypass</b>: hand-authored GraphML
     *       could carry any value at all for a hidden field and never be checked;</li>
     *   <li>enforce required-ness for hidden properties — that is a <b>UX trap</b>: a field nobody
     *       can see is mandatory and the graph cannot be saved.</li>
     * </ul>
     * <p>Neither is acceptable, so visibility is not consulted at all. A hidden property is never
     * <em>required</em> (its {@code requiredWhen}, if any, is what decides), and its value if present
     * is <b>still type-checked and still allowed-value-checked</b> by the calls below. Hidden means
     * "not shown", never "not validated".
     *
     * <h2>The obligation this cannot enforce, stated rather than implied</h2>
     * <p>Values of hidden properties are <b>preserved, not removed</b> — removing them would break
     * the lossless round trip this class already guarantees for unknown properties. A preserved
     * hidden value is inert <em>only if behaviors do not read it</em>, and nothing here stops a
     * behavior factory reading one from a hand-authored graph. Enforcing that needs a resolved view
     * the factories consult, which this schema does not build. Treat "hidden properties do not become
     * authority" as satisfied for validation and as a documented obligation for behaviors.
     */
    private static boolean requiredHere(GraphNode node, NodePropertyDescriptor property) {
        if (property.requiredWhen() == null) {
            return property.required();
        }
        Object sibling = node.properties().get(property.requiredWhen().property());
        return property.requiredWhen().holds(sibling == null ? null : sibling.toString());
    }

    private void validateProperty(GraphNode node, NodePropertyDescriptor property, boolean unconfigured,
                                  List<NodePropertyDescriptor> declared) {
        Object raw = node.properties().get(property.name());
        String value = raw == null ? null : raw.toString();

        if (value == null || value.isBlank()) {
            // Required-ness is suspended for a node that names no adapter, and only for
            // that node. Nothing else is suspended — a value that IS present still goes through the
            // type and allowed-value checks below, and the reserved-namespace refusal above already
            // ran unconditionally.
            if (requiredHere(node, property) && !unconfigured) {
                throw new BehaviorPropertyException(node.id(), property.name(),
                        "is required by behavior '" + node.behavior() + "' but is absent or blank");
            }
            // An absent optional property keeps its catalog default at execution. Deliberately not
            // materialised into the graph: writing defaults back would change the document, and the
            // round trip must be lossless.
            return;
        }

        requireType(node, property, value);
        requireAllowedValue(node, property, value);
    }

    /**
     * Type-checks one non-free-text value.
     *
     * <h2>Canonical lexical form, and why it is not pedantry</h2>
     * <p>A typed value must carry no surrounding whitespace. The reason is that this class must
     * accept exactly what the <em>interpreter</em> accepts, and the interpreter is inconsistent:
     * {@code NodeProperties.required} trims while {@code NodeProperties.string} does not, so
     * {@code " 42"} reaches {@code Long.parseLong} trimmed through one accessor and untrimmed through
     * the other.</p>
     *
     * <p>Trimming here would have been worse than useless. {@code Boolean.parseBoolean("TRUE ")} is
     * {@code false} — it maps every string that is not exactly {@code "true"} to {@code false},
     * case-insensitively — so a validator that trimmed would certify {@code "TRUE "} as a valid
     * <em>true</em> and the runtime would then read it as <em>false</em>. For a property gating a
     * safety control that is the worst available outcome: the author asked for on, got off, and
     * nothing anywhere reported a problem.</p>
     *
     * <p>Requiring the canonical form is strictly safer than either accessor, so the verdict holds
     * whichever one a behavior happens to use, and it cannot silently disagree with execution.</p>
     *
     * <p><strong>Currently redundant, deliberately kept.</strong> Every parser below happens to
     * reject surrounding whitespace on its own — {@code Long.parseLong}, {@code BigDecimal},
     * {@code new URI} and the case-insensitive boolean comparison all refuse it — so removing this
     * guard today changes no outcome, and a mutation that deletes it survives the suite. It stays
     * because it states the rule <em>type-independently</em>: the day someone adds a property type
     * whose parser is lenient about padding, the hazard returns silently, and this is the line that
     * prevents it rather than the line that happens to duplicate four other lines.</p>
     */
    private static void requireType(GraphNode node, NodePropertyDescriptor property, String value) {
        if (property.type() == NodePropertyType.STRING || property.type() == NodePropertyType.TEXT
                || property.type() == NodePropertyType.CEL_EXPRESSION) {
            // Free text by design; lexical whitespace is meaningful and is preserved. CEL_EXPRESSION
            // is deliberately not compiled here: the CEL environment is built per node at execution
            // with its own variable declarations, and a second, necessarily different compilation
            // would either reject valid expressions or accept ones the real environment refuses.
            return;
        }

        if (!value.equals(value.strip())) {
            throw typeFailure(node, property, value,
                    "written without surrounding whitespace, because the runtime accessors do not "
                            + "agree on trimming and a padded value can validate as one thing and "
                            + "execute as another");
        }

        switch (property.type()) {
            case BOOLEAN -> {
                // Case-insensitive to match Boolean.parseBoolean, but nothing else: "yes", "1" and
                // "on" are refused rather than silently read as false.
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw typeFailure(node, property, value, "exactly 'true' or 'false'");
                }
            }
            case INTEGER -> {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException notAnInteger) {
                    throw typeFailure(node, property, value, "an integer");
                }
            }
            case DECIMAL -> {
                try {
                    new BigDecimal(value);
                } catch (NumberFormatException notADecimal) {
                    throw typeFailure(node, property, value, "a decimal number");
                }
            }
            case URI -> {
                URI parsed;
                try {
                    parsed = new URI(value);
                } catch (java.net.URISyntaxException notAUri) {
                    throw typeFailure(node, property, value, "a valid URI");
                }
                if (!parsed.isAbsolute()) {
                    throw typeFailure(node, property, value, "an absolute URI including a scheme");
                }
            }
            case SECRET_REFERENCE -> {
                // A reference names a server-side secret; it is never the secret. Whitespace and
                // control characters are refused because a reference is an identifier, and a padded
                // one is a typo the author would far rather meet here — with a node id and a property
                // name — than as an unresolvable lookup much later at execution.
                //
                // WHAT THIS CHECK IS NOT. It is not what keeps the readers of this type
                // consistent. Previously the HTTP node trimmed its
                // credentialRef, the LLM node did not, and the difference was inert only because
                // nothing padded could get past this line. Readers are now verbatim by decision — see
                // NodePropertyType.SECRET_REFERENCE — so relaxing this refusal admits references that
                // fail to resolve, never references that resolve to the wrong secret. That
                // independence is deliberate and is asserted by SecretReferenceReaderFidelityTest,
                // which drives the readers with values this line refuses, rather than being left for
                // the next author to rediscover.
                //
                // Reachability, stated because it is easy to read this line as covering more than it
                // does: an entirely blank value never arrives here at all. validateProperty treats
                // blank as absent and returns above, so "   " is admitted and reaches the readers.
                if (value.chars().anyMatch(Character::isWhitespace)
                        || value.chars().anyMatch(Character::isISOControl)) {
                    throw typeFailure(node, property, value,
                            "a credential reference without embedded whitespace or control characters");
                }
            }
            case STRING, TEXT, CEL_EXPRESSION -> throw new IllegalStateException("handled above");
        }
    }

    private static void requireAllowedValue(GraphNode node, NodePropertyDescriptor property, String value) {
        List<String> allowed = property.allowedValues();
        if (allowed.isEmpty() || allowed.contains(value.trim())) {
            return;
        }
        throw new BehaviorPropertyException(node.id(), property.name(),
                "must be one of " + allowed + " but was '" + value + "'");
    }

    private static BehaviorPropertyException typeFailure(GraphNode node, NodePropertyDescriptor property,
                                                          String value, String expectation) {
        return new BehaviorPropertyException(node.id(), property.name(),
                "must be " + expectation + " for behavior '" + node.behavior() + "' but was '" + value + "'");
    }

    /** A graph whose operative properties do not satisfy the trusted catalog schema. */
    public static final class BehaviorPropertyException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private final String nodeId;
        private final String propertyName;

        BehaviorPropertyException(String nodeId, String propertyName, String problem) {
            super("Node '" + nodeId + "' property '" + propertyName + "' " + problem);
            this.nodeId = nodeId;
            this.propertyName = propertyName;
        }

        public String nodeId() {
            return nodeId;
        }

        public String propertyName() {
            return propertyName;
        }
    }

    /**
     * The behavior names {@code graph} uses that are not in the trusted catalog, sorted and distinct
     * (CORE-06).
     *
     * <h2>Reports, decides nothing</h2>
     * <p>This is a <em>query</em>. It does not validate, does not throw, and does not change what
     * happens to an unknown behavior when the graph runs — that question is SEC-09's third rule,
     * which is escalated, and the pass-through path is untouched. Calling this method has no effect
     * on execution at all.</p>
     *
     * <p>It exists because CORE-06 makes third-party node types a first-class deployment concern, and
     * the failure mode that follows is specific: an operator installs a node package, the package is
     * not loaded — a typo in the allowlist, a jar that never made it into the image, a behavior name
     * that does not match what the graph says — and the only symptom is that a node quietly does
     * nothing. "Which behaviors in this graph does this deployment not know about?" is the question
     * that turns that into a one-line answer, and nothing in the runtime could answer it before.</p>
     *
     * <p>Deliberately returns data rather than logging. The names are graph-supplied text of
     * unbounded length, so the decision about where they are safe to render — a structured API
     * response, an operator console, a test assertion — belongs to the caller that knows its own
     * output channel, not to this class.</p>
     */
    public List<String> unregisteredBehaviors(GraphDefinition graph) {
        Objects.requireNonNull(graph, "graph");
        return graph.nodes().stream()
                .filter(node -> node.kind() == NodeKind.BEHAVIOR)
                .map(GraphNode::behavior)
                .filter(Objects::nonNull)
                .filter(behavior -> behaviors.descriptor(behavior).isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    /** Exposed for diagnostics: the declared property names of {@code behavior}, empty when unknown. */
    public Map<String, NodePropertyType> declaredProperties(String behavior) {
        return behaviors.descriptor(behavior)
                .map(descriptor -> descriptor.properties().stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                NodePropertyDescriptor::name, NodePropertyDescriptor::type)))
                .orElseGet(Map::of);
    }
}
