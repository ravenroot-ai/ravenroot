package ai.ravenroot.api.catalog;

/**
 * One outcome a behavior can produce, either as a fixed name or as the value of one of its own
 * properties.
 *
 * <h2>Why a record rather than a plain string</h2>
 * <p>An outcome is what selects an outgoing edge: {@code GraphDefinition#nextEdges(source, outcome)}
 * takes {@code source}'s edges, drops the failure routes (which fire on a failed attempt and
 * never on an outcome), and matches the rest on the outcome string alone. For nine of the eleven
 * built-in behaviors the value is fixed in
 * the source, so a plain string would have been enough. For {@code cel-decision} and
 * {@code http-request} it is not: those emit the value of {@code trueOutcome}/{@code falseOutcome} and
 * {@code successOutcome}/{@code failureOutcome}, which the graph author names. A fixed string would
 * therefore be wrong exactly where an author most needs the declaration, and would state as fact
 * something the node will never emit once the property is set to anything but its default.</p>
 *
 * <h2>Why a property reference rather than a resolution callback</h2>
 * <p>The consumer that needs this is the editor, which is a browser application fed by the JSON
 * {@code /v1/catalog} publishes. A Java {@code Function<GraphNode, Set<String>>} cannot cross that
 * boundary without a new per-node server route. A reference to a property name can: it serializes as
 * data, and the property's declared default already travels in the same descriptor, so the resolution
 * needs nothing the catalog does not already publish. This is the pattern {@code natureProperty}
 * established for runtime-nature properties — publish the property name so the editor and the server cannot drift about
 * which key a value is written under — applied to the value rather than the key.</p>
 *
 * @param name         the outcome, when it is fixed; empty when this outcome is property-derived
 * @param fromProperty the name of the property whose value is the outcome; empty when {@code name} is
 *                     fixed
 * @param description  author-facing text explaining when the behavior produces this outcome
 */
public record NodeOutcomeDescriptor(String name, String fromProperty, String description) {

    /**
     * What a blank outcome resolves to, mirroring {@link ai.ravenroot.api.execution.NodeResult}'s
     * compact constructor.
     *
     * <p>This is not a default chosen here. {@code NodeResult} coerces a null or blank outcome to
     * {@code continue} before a runner ever sees it, so a node whose outcome property is set to the
     * empty string genuinely emits {@code continue} at run time. Resolving to anything else — or to
     * nothing — would make the declaration disagree with the behavior it describes.</p>
     */
    public static final String BLANK_OUTCOME = "continue";

    /**
     * Normalizes optional text to empty strings and requires exactly one outcome source: a fixed name
     * or a property reference.
     */
    public NodeOutcomeDescriptor {
        name = name == null ? "" : name;
        fromProperty = fromProperty == null ? "" : fromProperty;
        description = description == null ? "" : description;
        if (name.isBlank() == fromProperty.isBlank()) {
            throw new IllegalArgumentException("An outcome declares either a fixed name or a property "
                    + "to read it from, never both and never neither; got name='" + name
                    + "', fromProperty='" + fromProperty + "'");
        }
    }

    /**
     * Creates an outcome emitted under a fixed name.
     *
     * @param name fixed outcome matched against outgoing graph edges
     * @param description author-facing explanation of when the behavior emits the outcome
     * @return a descriptor with no property-derived outcome source
     */
    public static NodeOutcomeDescriptor literal(String name, String description) {
        return new NodeOutcomeDescriptor(name, "", description);
    }

    /**
     * Creates an outcome whose name is read from one declared node property.
     *
     * @param property property whose configured or default value supplies the outcome name
     * @param description author-facing explanation of when the behavior emits the outcome
     * @return a descriptor with an empty fixed name and the supplied property reference
     */
    public static NodeOutcomeDescriptor fromProperty(String property, String description) {
        return new NodeOutcomeDescriptor("", property, description);
    }

    /**
     * Whether this outcome's name comes from a node property rather than from the behavior's source.
     *
     * @return {@code true} when {@link #fromProperty()} is non-blank
     */
    public boolean parameterized() {
        return !fromProperty.isBlank();
    }

    /**
     * The outcome this declaration resolves to for one configured node.
     *
     * <p>{@code configuredValue} is the raw value the node carries under {@link #fromProperty()}, or
     * {@code null} when the node does not carry that property at all; {@code declaredDefault} is the
     * property descriptor's {@code defaultValue}. The two are distinct on purpose and the order
     * mirrors the run-time path exactly:</p>
     *
     * <ul>
     *   <li><b>Property absent.</b> {@code NodeProperties.string} substitutes the behavior's default,
     *       which is the same value the property descriptor publishes.</li>
     *   <li><b>Property present but blank.</b> {@code NodeProperties.string} returns the blank —
     *       it substitutes the default for {@code null} only, never for an empty string — and
     *       {@code NodeResult} then coerces the blank to {@link #BLANK_OUTCOME}. So a blank property
     *       does <em>not</em> fall back to the declared default, and treating it as if it did would
     *       be the one case where this method invents a branch the graph cannot take.</li>
     *   <li><b>Property present and non-blank.</b> That value, uninspected. It is not trimmed here
     *       because it is not trimmed at run time either, and {@code nextEdges} matches with
     *       {@code equals}.</li>
     * </ul>
     * @param configuredValue configured value for {@link #fromProperty()}, or {@code null} when absent
     * @param declaredDefault catalog default used when the property is absent
     * @return the fixed name, the configured/default property value, or {@link #BLANK_OUTCOME} for a
     *         null or blank resolved value
     */
    public String resolve(String configuredValue, String declaredDefault) {
        if (!parameterized()) {
            return name;
        }
        String raw = configuredValue == null ? declaredDefault : configuredValue;
        return raw == null || raw.isBlank() ? BLANK_OUTCOME : raw;
    }
}
