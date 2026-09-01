package ai.ravenroot.api.catalog;

import java.util.List;

/**
 * The well-known node property through which a graph author chooses, <strong>per node usage</strong>,
 * among the runtime natures the trusted catalog permits (ADR 0024 §2).
 *
 * <h2>Why this is an ordinary unreserved name</h2>
 * <p>The {@code ravenroot.} namespace is refused from graph content outright — at ingest by
 * {@code GraphManager} and again for programmatically-built definitions by
 * {@code BehaviorPropertySchema}. A nature the graph may never write would satisfy no part of ADR
 * 0024 §2, which requires exactly that graph content <em>may choose an allowed nature</em>. So the
 * name is unreserved, deliberately, for the same reason {@link RecoveryRepeatabilityProperty} is: the
 * reserved namespace protects platform-asserted facts from author forgery, and this is an author
 * choice made from a set the platform already fixed. Choosing it grants no authority the author
 * lacks, because the allowlist is what decides, and the allowlist is not in the graph.
 * {@code ReservedGraphProperties} therefore stays closed, with no exception.</p>
 *
 * <h2>Unreserved, but not a behavior property</h2>
 * <p>This is where it parts company with {@code recovery.repeatable}, and the difference is the whole
 * design. {@code recovery.repeatable} is a property a node package <em>declares</em> and whose shape
 * is then pinned. This one is <strong>platform-owned</strong>: no descriptor may declare it at all.
 * Its constraint lives in {@link NodeTypeDescriptor#defaultNature()} and
 * {@link NodeTypeDescriptor#allowedNatures()}, not in a {@link NodePropertyDescriptor}, because a
 * behavior that could declare the property's own type, allowed values or default would hold a second
 * authority over the same key and the two could disagree. {@link #validateShape(NodeTypeDescriptor)}
 * refuses that at catalog load, which is the anti-collision this class owes.</p>
 *
 * <h2>Consequently it is not validated by {@code BehaviorPropertySchema}</h2>
 * <p>That class returns early for non-behavior nodes and for behaviors absent from the catalog, which
 * is correct for the behavior properties it owns and would be a hole here: a {@code START} node or an
 * uncatalogued behavior carrying {@code runtime.nature=AUTHORITY} would pass unchecked. The nature is
 * therefore checked by {@code NodeRuntimeNatureValidator}, which runs unconditionally over every node
 * and shares no early return with it.</p>
 *
 * <h2>Reading is structural</h2>
 * <p>There is deliberately no accessor that takes instance values alone. Resolution needs the
 * descriptor, because "absent" means the descriptor's default and the descriptor is the only thing
 * that knows it; a raw string lookup would read a stray author key as a lifecycle declaration on a
 * node whose catalog entry never permitted one, and would produce a plausible answer while doing it.
 * That is the failure {@link RecoveryRepeatabilityProperty#read} documents, and the same shape is
 * used here for the same reason.</p>
 */
public final class NodeRuntimeNatureProperty {

    /** The well-known name. Unreserved, and deliberately so — see this class's Javadoc. */
    public static final String NAME = "runtime.nature";

    private NodeRuntimeNatureProperty() {
    }

    /**
     * The stable serialized identifiers, in declaration order, for editors and diagnostics.
     *
     * @return immutable identifiers for every supported {@link NodeRuntimeNature}
     */
    public static List<String> allowedValues() {
        return java.util.Arrays.stream(NodeRuntimeNature.values()).map(Enum::name).toList();
    }

    /**
     * The nature this node usage effectively has: what it declared, or the descriptor's default.
     *
     * <p>Only ever called after {@code NodeRuntimeNatureValidator} has accepted the graph, so an
     * unparseable or disallowed value cannot reach here — it was refused before any actor existed. If
     * one somehow does, this method returns the descriptor's effective default rather than guessing at
     * the author's intent, and the validator, not this method, is the control that must have failed.
     *
     * @param descriptor     the trusted catalog entry, or {@code null} for a node with no catalog
     *                       entry — a non-behavior node or an uncatalogued behavior, both of which are
     *                       {@link NodeRuntimeNature#DEFAULT} because they may not declare a nature
     *                       at all
     * @param instanceValues the node's properties
     * @return the parsed declared nature, or the descriptor's effective default when no valid
     *         declaration is available
     */
    public static NodeRuntimeNature effectiveNature(NodeTypeDescriptor descriptor,
                                                    java.util.Map<String, Object> instanceValues) {
        NodeRuntimeNature fallback = descriptor == null
                ? NodeRuntimeNature.DEFAULT
                : descriptor.effectiveDefaultNature();
        if (descriptor == null || instanceValues == null) {
            return fallback;
        }
        Object declared = instanceValues.get(NAME);
        if (declared == null) {
            return fallback;
        }
        return NodeRuntimeNature.parse(declared).orElse(fallback);
    }

    /**
     * Whether {@code instanceValues} carries the property at all, however malformed its value.
     *
     * @param instanceValues node property map, possibly {@code null}
     * @return {@code true} when the map contains {@link #NAME}, including a {@code null} or invalid value
     */
    public static boolean declaredBy(java.util.Map<String, Object> instanceValues) {
        return instanceValues != null && instanceValues.containsKey(NAME);
    }

    /**
     * Refuses a descriptor that declares the platform-owned name as one of its own properties.
     *
     * <p>Called by {@link NodeTypeDescriptorValidator#validate(NodeTypeDescriptor)}, so it runs on the
     * one path every registration takes — built-ins, the legacy handler path, SDK node packages and
     * plugin bundles alike. A behavior that declared this name would give a graph two sources of truth
     * for the same key: the descriptor's own type and allowed values on one side, and
     * {@link NodeTypeDescriptor#allowedNatures()} on the other. They could disagree, and whichever was
     * consulted second would silently decide a lifecycle.</p>
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
                    + "constrain it through the descriptor's defaultNature and allowedNatures instead, "
                    + "or the descriptor and the nature allowlist become two authorities over one key "
                    + "that can disagree");
        }
    }
}
