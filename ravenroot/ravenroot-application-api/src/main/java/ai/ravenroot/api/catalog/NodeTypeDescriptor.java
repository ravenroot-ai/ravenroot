package ai.ravenroot.api.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Public catalog entry used by every adapter and by the visual editor.
 *
 * @param defaultNature the runtime nature a node of this type has when it declares none, or
 * {@code null} for a descriptor that says nothing — see
 * {@link #effectiveDefaultNature()} for why the null is kept rather than
 * normalised to {@link NodeRuntimeNature#WORKER}
 * @param allowedNatures the natures graph content may choose from, or empty for a descriptor that
 * says nothing — see {@link #effectiveAllowedNatures()} for the fail-closed
 * reading of empty
 * @param commands the application commands this behavior <em>admits</em> — an allowlist, not a
 * suggestion list; see the note on {@link #commands()} below
 * @param outcomes the outcomes this behavior can produce, empty for a descriptor that
 * says nothing — see {@link #resolveOutcomes(java.util.function.Function)}
 * @param behavior unique behavior identifier used to select the node implementation
 * @param displayName editor-facing name; defaults to {@code behavior} when absent
 * @param category editor grouping; defaults to {@code General} when absent
 * @param description human-readable behavior description, or the empty string when absent
 * @param visualType renderer hint; defaults to {@code actor} when absent
 * @param agentic whether the behavior is eligible for agent-oriented presentation
 * @param properties immutable property descriptors accepted by this node type
 * @param capabilities immutable capabilities declared by the behavior
 * @param runtimeConcurrency trusted default and ceiling for platform-owned per-node admission
 */
public record NodeTypeDescriptor(
        String behavior,
        String displayName,
        String category,
        String description,
        String visualType,
        boolean agentic,
        List<NodePropertyDescriptor> properties,
        Set<String> capabilities,
        NodeRuntimeNature defaultNature,
        Set<NodeRuntimeNature> allowedNatures,
        Set<String> commands,
        List<NodeOutcomeDescriptor> outcomes,
        NodeRuntimeConcurrency runtimeConcurrency) {

/**
 * Normalizes optional display metadata and rejects outcome/nature combinations that a graph could
 * not resolve consistently.
 */
    public NodeTypeDescriptor {
        if (behavior == null || behavior.isBlank()) {
            throw new IllegalArgumentException("Behavior name cannot be blank");
        }
        displayName = displayName == null || displayName.isBlank() ? behavior : displayName;
        category = category == null || category.isBlank() ? "General" : category;
        description = description == null ? "" : description;
        visualType = visualType == null || visualType.isBlank() ? "actor" : visualType;
        properties = properties == null ? List.of() : List.copyOf(properties);
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        // Absent stays absent. Normalising a null default to WORKER here would erase the difference
        // between "this descriptor said nothing" and "this descriptor declared WORKER", and
        // BehaviorRegistry's source-capability derivation depends on exactly that difference: it may
        // supply SOURCE to a descriptor that said nothing, and must refuse a descriptor that said
        // WORKER while implementing InboundSourceCapable. Collapse the two and every legacy source
        // package looks like a contradiction. The same reasoning applies to property conditions.
        allowedNatures = allowedNatures == null ? Set.of() : Set.copyOf(allowedNatures);
        commands = commands == null ? Set.of() : commands.stream()
                .map(ai.ravenroot.api.execution.NodeCommand::application)
                .map(ai.ravenroot.api.execution.NodeCommand::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        runtimeConcurrency = runtimeConcurrency == null ? NodeRuntimeConcurrency.DEFAULT : runtimeConcurrency;
        // A property-derived outcome that names a property this descriptor does not declare cannot be
        // resolved by anyone: the editor has no field to read and no default to fall back to, so the
        // outcome would silently vanish from the suggestions instead of being wrong loudly. Catching it
        // at construction makes it a defect in the behavior that declared it, found the first time its
        // factory runs, rather than an absence a graph author is left to explain.
        for (NodeOutcomeDescriptor outcome : outcomes) {
            if (!outcome.parameterized()) continue;
            boolean declared = properties.stream()
                    .anyMatch(property -> property.name().equals(outcome.fromProperty()));
            if (!declared) {
                throw new IllegalArgumentException("Behavior '" + behavior + "' declares an outcome read "
                        + "from property '" + outcome.fromProperty() + "', which the same descriptor does "
                        + "not declare; nothing could resolve that outcome for a configured node");
            }
        }
        if (defaultNature != null && !allowedNatures.isEmpty() && !allowedNatures.contains(defaultNature)) {
            throw new IllegalArgumentException("Behavior '" + behavior + "' declares default runtime "
                    + "nature " + defaultNature + ", which is not among its allowed natures "
                    + new java.util.TreeSet<>(allowedNatures) + "; a node that declares nothing would "
                    + "then resolve to a nature the same descriptor forbids graph content from choosing");
        }
    }

    /**
 * The nature a node of this type has when it declares none: the declared default, or
 * {@link NodeRuntimeNature#DEFAULT}.
 *
 * <p>This is the accessor every consumer should use. {@link #defaultNature()} answers the
 * different question of whether the descriptor said anything, which only catalog registration
 * needs.</p>
 * @return declared default when present, otherwise {@link NodeRuntimeNature#DEFAULT}
 */
    public NodeRuntimeNature effectiveDefaultNature() {
        return defaultNature == null ? NodeRuntimeNature.DEFAULT : defaultNature;
    }

    /**
 * The natures graph content may choose for a node of this type.
 *
 * <p><strong>Empty means exactly the effective default, never "anything".</strong> The permissive
 * reading is the one that has to be argued against, because it is what an empty collection usually
 * means elsewhere: under it, every descriptor authored before runtime natures existed would
 * silently permit a graph to declare {@link NodeRuntimeNature#AUTHORITY} on any node, which is the
 * privilege escalation this rule prevents, delivered by the default. Fail-closed costs a
 * descriptor that wants choice one explicit declaration.</p>
 * @return declared allowed natures, or a singleton containing the effective default
 */
    public Set<NodeRuntimeNature> effectiveAllowedNatures() {
        return allowedNatures.isEmpty() ? Set.of(effectiveDefaultNature()) : allowedNatures;
    }

    /**
 * The application commands this behavior admits.
 *
 * <h4>This is an admission allowlist, and it is empty for every built-in on purpose</h4>
 * <p>It reads like a list of commands a behavior "supports", and the editor does use it to suggest
 * values for an edge's Command field. That is a secondary use. Its primary use is enforcement:
 * {@code GraphRunner} refuses a delivery whose command carries {@link
 * ai.ravenroot.api.execution.NodeDirective#APPLICATION} unless the target's descriptor lists that
 * command's name, both at admission time over the reachable graph and again on the delivery itself.
 * {@link ai.ravenroot.api.execution.NodeCommand} states the same contract: named commands are inert
 * until the target's trusted catalog descriptor admits them.</p>
 *
 * <p>So an empty datalist in the editor is not an oversight to be filled in. None of
 * the nine built-in behaviors interprets an application command — each one does its single job and
 * ignores the structural instruction — and adding names here to populate a suggestion list would
 * widen a security allowlist to admit deliveries no built-in handles, in exchange for a UI
 * convenience. The suggester is empty because there is genuinely nothing to suggest; it fills in
 * when a node package that actually interprets commands is installed.</p>
 * @return immutable allowlist of application command names
 */
    public Set<String> commands() {
        return commands;
    }

/**
 * Whether this descriptor says anything at all about runtime nature.
 * @return {@code true} when the descriptor explicitly constrains runtime nature
 */
    public boolean declaresNature() {
        return defaultNature != null || !allowedNatures.isEmpty();
    }

/**
 * This descriptor with the given nature constraint, for catalog-load derivation.
 * @param newDefault replacement default nature, or {@code null} to leave it unspecified
 * @param newAllowed replacement set of natures graph content may select
 * @return copy retaining all catalog metadata with the supplied nature constraints
 */
    public NodeTypeDescriptor withNature(NodeRuntimeNature newDefault, Set<NodeRuntimeNature> newAllowed) {
        return new NodeTypeDescriptor(behavior, displayName, category, description, visualType, agentic,
                properties, capabilities, newDefault, newAllowed, commands, outcomes, runtimeConcurrency);
    }

    /**
 * This descriptor with the given outcome declaration.
 *
 * <p>A builder rather than a twelfth argument at every call site: a behavior's outcomes belong
 * next to nothing else in the descriptor, and the alternative was to make nine built-in factories
 * spell out {@code null, Set.of(), Set.of()} for the three components between {@code capabilities}
 * and {@code outcomes} that they do not declare. Validation is not skipped — this constructs
 * through the canonical constructor, so a property-derived outcome naming a property this
 * descriptor does not declare is refused here exactly as it would be there.</p>
 * @param declared outcome declarations to attach to this descriptor
 * @return copy retaining all catalog metadata with the supplied outcomes
 */
    public NodeTypeDescriptor withOutcomes(NodeOutcomeDescriptor... declared) {
        return new NodeTypeDescriptor(behavior, displayName, category, description, visualType, agentic,
                properties, capabilities, defaultNature, allowedNatures, commands, List.of(declared),
                runtimeConcurrency);
    }

    /**
 * Copy with a trusted runtime-admission default and ceiling.
* @param constraint trusted runtime-admission default and ceiling
* @return a descriptor copy carrying the supplied admission constraint
 */
    public NodeTypeDescriptor withRuntimeConcurrency(NodeRuntimeConcurrency constraint) {
        return new NodeTypeDescriptor(behavior, displayName, category, description, visualType, agentic,
                properties, capabilities, defaultNature, allowedNatures, commands, outcomes, constraint);
    }

    /**
 * Every outcome a node of this type can produce, resolved against that node's configured
 * properties.
 *
 * <p>Parameterization is why this takes a lookup rather than being a constant: {@code cel-decision}
 * and {@code http-request} read their outcome names out of node properties, so the answer is a
 * property of the <em>node</em>, not of the type. {@code propertyValues} returns the raw value the
 * node carries under a property name, or {@code null} when it carries none — the same distinction
 * {@code NodeProperties.string} makes, and {@link NodeOutcomeDescriptor#resolve} depends on it.</p>
 *
 * <p>The result is deliberately a set: two properties set to the same name are one outcome, because
 * one outcome is all {@code nextEdges} can distinguish.</p>
 *
 * <p><strong>This is not the set of outcomes a traversal can act on.</strong> A runner that finds no
 * edge matching the produced outcome retries the lookup with {@code continue}, so an edge wired to
 * {@code continue} may fire for an outcome that is not in this set at all. Any reader deciding that
 * an edge is unreachable must exempt {@code continue} for that reason.</p>
 *
 * <p><strong>An empty set means "this descriptor says nothing", never "this behavior emits
 * nothing".</strong> That reading is the one with the most ways to go wrong: most node packages
 * declare no outcomes — seven of the nine extension modules shipped here, and any package written
 * against an older version of this record — so a consumer treating empty as a closed set would
 * call every edge leaving every one of them unreachable. Empty is the absence of an answer.</p>
 *
 * <p><strong>For a behavior in this repository that does declare outcomes, the resolved set is
 * exact</strong>, and the editor-side check relies on that. The qualification this paragraph
 * used to carry — that an outcome property may be "bound to a value only known at run time" — is
 * not true of any behavior shipped here: {@code NodeProperties.render} is applied to {@code url},
 * {@code body}, {@code source}, {@code template}, {@code prompt} and {@code objective}, and to no
 * outcome property at all. {@code cel-decision}, {@code http-request} and the SpEL decision node
 * read {@code trueOutcome}/{@code falseOutcome}/{@code successOutcome}/{@code failureOutcome}
 * verbatim at composition time.</p>
 *
 * <p>It remains true of a <em>third-party</em> behavior, which is free to pass its own outcome
 * property through a template renderer before emitting it. For that one the value resolved here is
 * the template text and not the outcome produced, so a consumer turning this set into a verdict
 * owes that case an exemption too — see {@code node-outcomes.js}, which treats a resolved name
 * still holding a {@code {{…}}} token as unknowable rather than as wrong.</p>
 * @param propertyValues lookup of configured property values by property name
 * @return immutable distinct outcome names after property-derived outcomes are resolved
 */
    public Set<String> resolveOutcomes(java.util.function.Function<String, String> propertyValues) {
        var resolved = new LinkedHashSet<String>();
        for (NodeOutcomeDescriptor outcome : outcomes) {
            resolved.add(outcome.resolve(
                    outcome.parameterized() ? propertyValues.apply(outcome.fromProperty()) : null,
                    declaredDefaultOf(outcome.fromProperty())));
        }
        return java.util.Collections.unmodifiableSet(resolved);
    }

    private String declaredDefaultOf(String propertyName) {
        return properties.stream()
                .filter(property -> property.name().equals(propertyName))
                .map(NodePropertyDescriptor::defaultValue)
                .findFirst()
                .orElse("");
    }

    /**
 * The canonical shape before {@code defaultNature} and {@code allowedNatures}, retained
 * so that node packages and plugin bundles already compiled against it keep linking.
 *
 * <p>Additive, not breaking — but only because this overload exists. Adding a record component
 * changes the canonical constructor's descriptor, which is a <em>binary</em> break for any caller
 * already compiled against the older class file and not merely a source break for one being
 * recompiled. That claim is proved rather than asserted here: see
 * {@code NodeTypeDescriptorBinaryCompatibilityTest}, which also carries a negative control so
 * "every published shape still links" cannot be confused with "the harness detects nothing".</p>
 *
 * <p>Both nature components default to absent, which is the correct reading of a descriptor
 * written before the concept existed: it said nothing, so its nodes are workers unless the
 * source-capability derivation finds a declaration in the code itself.</p>
 * @param behavior unique identifier used to select the behavior implementation
 * @param displayName editor label, normalized to {@code behavior} when absent
 * @param category editor grouping, normalized to {@code General} when absent
 * @param description author-facing explanation, normalized to the empty string when absent
 * @param visualType renderer hint, normalized to {@code actor} when absent
 * @param agentic whether agent-oriented presentation is allowed for the behavior
 * @param properties immutable behavior-property schema
 * @param capabilities immutable capabilities advertised by the behavior
 */
    public NodeTypeDescriptor(String behavior, String displayName, String category, String description,
                              String visualType, boolean agentic, List<NodePropertyDescriptor> properties,
                              Set<String> capabilities) {
        this(behavior, displayName, category, description, visualType, agentic, properties, capabilities,
                null, Set.of(), Set.of(), List.of(), NodeRuntimeConcurrency.DEFAULT);
    }

    /**
 * Compatibility constructor preserving the ten-argument canonical descriptor.
 *
 * @param behavior unique identifier used to select the behavior implementation
 * @param displayName editor label, normalized to {@code behavior} when absent
 * @param category editor grouping, normalized to {@code General} when absent
 * @param description author-facing explanation, normalized to the empty string when absent
 * @param visualType renderer hint, normalized to {@code actor} when absent
 * @param agentic whether agent-oriented presentation is allowed for the behavior
 * @param properties immutable behavior-property schema
 * @param capabilities immutable capabilities advertised by the behavior
 * @param defaultNature nature used when the graph omits {@code runtime.nature}, or {@code null}
 * when this versioned shape says nothing about nature
 * @param allowedNatures natures graph content may select, or empty when nature is undeclared
 */
    public NodeTypeDescriptor(String behavior, String displayName, String category, String description,
                              String visualType, boolean agentic, List<NodePropertyDescriptor> properties,
                              Set<String> capabilities, NodeRuntimeNature defaultNature,
                              Set<NodeRuntimeNature> allowedNatures) {
        this(behavior, displayName, category, description, visualType, agentic, properties, capabilities,
                defaultNature, allowedNatures, Set.of(), List.of(), NodeRuntimeConcurrency.DEFAULT);
    }

    /**
 * Compatibility constructor preserving the eleven-argument canonical descriptor, which was the
 * canonical shape before {@code outcomes}.
 *
 * <p>Same reasoning as the two overloads above, and the same proof: adding a record component
 * changes the canonical constructor's descriptor, which is a binary break for any node package or
 * plugin bundle already compiled against the eleven-argument shape.
 * {@code NodeTypeDescriptorBinaryCompatibilityTest} carries this shape as a published case.</p>
 *
 * <p>Declaring no outcomes is the correct reading of a descriptor written before the concept
 * existed: it says nothing, and an editor offers no suggestions rather than inventing some.</p>
 * @param behavior unique identifier used to select the behavior implementation
 * @param displayName editor label, normalized to {@code behavior} when absent
 * @param category editor grouping, normalized to {@code General} when absent
 * @param description author-facing explanation, normalized to the empty string when absent
 * @param visualType renderer hint, normalized to {@code actor} when absent
 * @param agentic whether agent-oriented presentation is allowed for the behavior
 * @param properties immutable behavior-property schema
 * @param capabilities immutable capabilities advertised by the behavior
 * @param defaultNature nature used when the graph omits {@code runtime.nature}, or {@code null}
 * when this versioned shape says nothing about nature
 * @param allowedNatures natures graph content may select, or empty when nature is undeclared
 * @param commands application commands admitted by the behavior; empty for none
 */
    public NodeTypeDescriptor(String behavior, String displayName, String category, String description,
                              String visualType, boolean agentic, List<NodePropertyDescriptor> properties,
                              Set<String> capabilities, NodeRuntimeNature defaultNature,
                              Set<NodeRuntimeNature> allowedNatures, Set<String> commands) {
        this(behavior, displayName, category, description, visualType, agentic, properties, capabilities,
                defaultNature, allowedNatures, commands, List.of(), NodeRuntimeConcurrency.DEFAULT);
    }

    /**
 * Compatibility constructor preserving the twelve-argument canonical descriptor.
* @param behavior unique identifier used to select the node implementation
* @param displayName editor-facing behavior name
* @param category editor category used to group the behavior
* @param description human-readable behavior description
* @param visualType renderer hint used by the visual editor
* @param agentic whether the editor may present the behavior as agent-oriented
* @param properties immutable property descriptors accepted by the node type
* @param capabilities immutable capabilities declared by the behavior
* @param defaultNature runtime nature selected when graph content declares none
* @param allowedNatures runtime natures graph content may select
* @param commands application command names admitted by this behavior
* @param outcomes outcomes the behavior can produce
 */
    public NodeTypeDescriptor(String behavior, String displayName, String category, String description,
                              String visualType, boolean agentic, List<NodePropertyDescriptor> properties,
                              Set<String> capabilities, NodeRuntimeNature defaultNature,
                              Set<NodeRuntimeNature> allowedNatures, Set<String> commands,
                              List<NodeOutcomeDescriptor> outcomes) {
        this(behavior, displayName, category, description, visualType, agentic, properties, capabilities,
                defaultNature, allowedNatures, commands, outcomes, NodeRuntimeConcurrency.DEFAULT);
    }

    /**
 * Sorted identifiers of {@link #effectiveAllowedNatures()}, for stable serialization.
 *
 * @return immutable identifiers in {@link NodeRuntimeNature} declaration order, including the
 * effective default for a descriptor that declares no allowlist
 */
    public List<String> allowedNatureIdentifiers() {
        var sorted = new LinkedHashSet<String>();
        for (NodeRuntimeNature nature : NodeRuntimeNature.values()) {
            if (effectiveAllowedNatures().contains(nature)) {
                sorted.add(nature.name());
            }
        }
        return List.copyOf(sorted);
    }
}
