package ai.ravenroot.api.correlation;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import java.util.*;

/** Platform-owned authoring properties shared by START, SOURCE and explicit context derivation. */
public final class CorrelationMappingProperties {
    /** Explicit application-ID creation policy. */
    public static final String MODE = "correlation.mode";
    /** JSON Pointer over the actual exposed ingress payload, with no implicit envelope. */
    public static final String SELECTOR = "correlation.selector";
    /** Optional second mapping that must agree with the first when both are present. */
    public static final String SECONDARY_SELECTOR = "correlation.secondarySelector";
    /** Distinct expected business contribution. */
    public static final String CONTRIBUTION = "correlation.contributionSelector";
    /** Optional contribution kind/partition label. */
    public static final String KIND = "correlation.kindSelector";
    /** Explicit correlated-join generation. */
    public static final String ROUND = "correlation.roundSelector";
    /** Public non-secret history lookup reference. */
    public static final String REFERENCE = "correlation.referenceSelector";

    private CorrelationMappingProperties() { }

    /** Compiles one shared mapping before listener activation or initial admission.
 * @param properties authored node properties compiled before source activation or initial admission
 * @param limits operator-owned extraction and input bounds
 * @return validated shared mapping compiled from the authored properties
 */
    public static CorrelationMapping read(Map<String, ?> properties, CorrelationLimits limits) {
        Object rawMode = properties.get(MODE);
        CorrelationMapping.Mode mode;
        try {
            mode = rawMode == null ? CorrelationMapping.Mode.GENERATE_NEW
                    : CorrelationMapping.Mode.valueOf(requireText(rawMode, MODE));
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("invalid " + MODE); }
        var application = new ArrayList<CorrelationSelector>();
        selector(properties, SELECTOR, limits).ifPresent(application::add);
        selector(properties, SECONDARY_SELECTOR, limits).ifPresent(application::add);
        return new CorrelationMapping(mode, application, selector(properties, CONTRIBUTION, limits),
                selector(properties, KIND, limits), selector(properties, ROUND, limits),
                selector(properties, REFERENCE, limits), limits);
    }

    /** Descriptor-driven controls; extraction preview uses {@link CorrelationMapping#extract}.
 * @param limits operator-owned extraction and input bounds
 * @return platform authoring controls for explicit application identity and contribution selection
 */
    public static NodePropertyDescriptor[] descriptors(CorrelationLimits limits) {
        var controls = new ArrayList<NodePropertyDescriptor>();
        controls.add(new NodePropertyDescriptor(MODE, "Application ID policy", NodePropertyType.STRING, false,
                "Generate a new history, accept an external ID or require an existing authorized history.",
                "GENERATE_NEW", List.of("GENERATE_NEW", "ACCEPT_OR_CREATE", "REQUIRE_EXISTING"), false, null, null));
        for (var entry : Map.of(SELECTOR, "Application ID selector", SECONDARY_SELECTOR, "Confirming ID selector",
                CONTRIBUTION, "Contribution ID selector", KIND, "Contribution kind selector", ROUND, "Join round selector",
                REFERENCE, "Public reference selector").entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            controls.add(NodePropertyDescriptor.boundedText(entry.getKey(), entry.getValue(), NodePropertyType.STRING,
                    false, "Explicit RFC 6901 JSON Pointer over the exposed event payload. No expression evaluation or fallback.",
                    "", limits.maxSelectorCharacters(), 1, limits.maxSelectorCharacters()));
        }
        return controls.toArray(NodePropertyDescriptor[]::new);
    }

    private static Optional<CorrelationSelector> selector(Map<String, ?> properties, String name,
                                                          CorrelationLimits limits) {
        if (!properties.containsKey(name)) return Optional.empty();
        return Optional.of(new CorrelationSelector(requireText(properties.get(name), name), limits));
    }
    private static String requireText(Object value, String name) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("invalid " + name);
        return text;
    }
}
