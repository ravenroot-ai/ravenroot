package ai.ravenroot.api.catalog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed validation of a {@link NodeTypeDescriptor}'s conditional metadata, at catalog load.
 *
 * <h2>Load time, not use time</h2>
 * <p>A malformed descriptor is a deployment defect and is reported as one, the way
 * {@code NodePackages} already treats a malformed node package. The alternative — discovering a
 * dangling condition reference when a user opens the inspector — turns an author's typo into an
 * operator's incident.
 *
 * <h2>Every registration path, not merely the SDK one</h2>
 * <p>This runs from {@code BehaviorRegistry.registerFactory}, which is the single insertion point for
 * built-ins, SDK node packages and plugin bundles alike. Validating only in {@code NodePackages}
 * would leave built-in descriptors unchecked, and a control that cannot fire on the paths that matter
 * is the defect class this repository has catalogued repeatedly.
 *
 * <h2>What is rejected, and why each rule exists</h2>
 * <ol>
 *   <li><b>Dangling reference</b> — a condition naming a property the type does not declare.
 *       Rejected outright, never evaluated — and evaluating it would not uniformly do what "could
 *       never hold" suggests. {@link PropertyCondition#holds} treats a missing sibling as {@code
 *       null}, which strips to {@code ""} — the empty string, not "blank" in this class's other
 *       sense of {@code String::isBlank}, and the two do not coincide here. The exact rule:
 *       {@code holds(null)} is {@code true} iff the operator is {@code BLANK}, or the operator is
 *       {@code EQUALS}/{@code ONE_OF} and its operand list contains the literal empty string {@code
 *       ""} — regardless of what else that list contains. {@code EQUALS(" ")} and {@code ONE_OF(" ")}
 *       have a blank operand and still evaluate {@code false}; {@code ONE_OF("", "X")} has a
 *       non-blank member and still evaluates {@code true}, because {@code ""} alone is what matches.
 *       So a pending {@code visibleWhen = BLANK} would make the guarded field <em>always</em> visible,
 *       and a pending {@code requiredWhen} naming {@code EQUALS}/{@code ONE_OF} with {@code ""} among
 *       its operands would make it <em>always</em> required — the opposite of never appearing. This
 *       rule removes the failure for every operator at once, by refusing the reference before any
 *       operator gets to evaluate it.</li>
 *   <li><b>Self-reference</b> — a property conditioned on itself. Decidable — it is {@link
 *       PropertyCondition#holds} applied to the property's own current, already-known value, an O(1)
 *       lookup with no fixed point to solve — but not meaningful: a field whose visibility or
 *       requiredness depends on its own value is not a condition on anything else, so rejecting it
 *       costs nothing.</li>
 *   <li><b>Cycle</b> — A visible when B, B visible when A. <b>Rejected, not resolved to a fixed
 *       point.</b> A fixed point gives a semantic no plugin author can predict and no reviewer can
 *       check by reading; "the field appears iff the solver says so" is not a contract.</li>
 *   <li><b>Inadmissible operand</b> — an operand outside the referenced sibling's
 *       {@code allowedValues}, when the sibling declares any. This is the check that
 *       {@link PropertyConditionOperator}'s positive-only design exists to make possible: a negated
 *       operator's satisfying set is open-ended and could not be checked here at all.</li>
 *   <li><b>Required-but-invisible</b> — {@code requiredWhen} must imply {@code visibleWhen}, or the
 *       descriptor could declare a field that is mandatory in a state where no editor shows it. The
 *       closed operator set makes the operands checkable (rule 4); it does not make this implication
 *       decidable. What is actually enforced is a conservative, syntax-only comparison of the two
 *       conditions' operators and operands ({@link #satisfyingSetContained}), which refuses any pair
 *       whose containment — the required operand set inside the visible one, not equivalence between
 *       the two — it cannot verify literally; including some pairs that are genuinely safe. This item
 *       names one rule but {@link #requiredImpliesVisible} throws it from two distinct sites with two
 *       distinct messages and two distinct tests: the two conditions naming different siblings, and
 *       the two naming the same sibling but failing {@code satisfyingSetContained}.</li>
 *   <li><b>Conditionally required with a default</b> — a default satisfies "required" trivially, so
 *       the pair is incoherent. Unreachable by any catalog authored before conditional properties, since
 *       {@code requiredWhen} is new, so fail-closed costs nothing here.</li>
 *   <li><b>Default outside allowedValues</b> — a non-blank {@code defaultValue} that is not a member
 *       of the same property's own {@code allowedValues}, when the property declares any. A
 *       blank {@code defaultValue} means "not declared" regardless of {@code allowedValues} and is not
 *       this rule's concern; only a non-blank one that the property's own closed set does not admit is
 *       incoherent, the same way a required-and-defaulted pair is incoherent above.</li>
 *   <li><b>Duplicate property names</b> — pre-existing gap, now closed: a sibling reference must
 *       resolve to exactly one property or the condition is ambiguous.</li>
 *   <li><b>Unknown condition contract</b> — a {@code visibleWhen}/{@code requiredWhen} whose
 *       {@link PropertyCondition#contract} does not match {@link PropertyCondition#CONTRACT}.
 *       Refused rather than guessed, because guessing it means guessing whether a field is
 *       required.</li>
 *   <li><b>{@code recovery.repeatable} shape anti-collision</b> — enforced by
 *       {@link RecoveryRepeatabilityProperty#validateShape} (PERS-04, ADR 0022): the property is
 *       unreserved, so a node package could declare it with a shape that widens a recovery
 *       contract.</li>
 *   <li><b>{@code runtime.nature} shape anti-collision</b> — enforced by
 *       {@link NodeRuntimeNatureProperty#validateShape} (ADR 0024 §2), for the same reason:
 *       the property is unreserved and could otherwise hold a second, conflicting authority over the
 *       same key.</li>
 *   <li><b>{@code execution.bypass} shape anti-collision</b> — enforced by
 *       {@link NodeBypassProperty#validateShape}, the third of the same family: the property
 *       is unreserved, its legal values are fixed by the platform, and a descriptor declaring the
 *       name would hold a second authority over the one key that decides whether a node runs.</li>
 *   <li><b>Malformed or reserved command name</b> — every entry in {@code descriptor.commands()} is
 *       parsed through {@link ai.ravenroot.api.execution.NodeCommand#application} at registration, so
 *       a bad name fails to load rather than becoming meaningful only because a graph spells it.</li>
 *   <li><b>Missing descriptor</b> — {@code validate(null)} is refused before any property is
 *       inspected: the first {@code throw} in {@link #validate}, and the one this list itself omitted
 *       when the count below still read twelve. Restricting the enumeration to conditional metadata
 *       cannot excuse leaving this out, because unknown contract, both anti-collisions and command
 *       parsing above are not conditional metadata either and are listed anyway.</li>
 * </ol>
 * <p>Fifteen {@code throw} sites feed this enumeration — eleven in this file plus the four delegated
 * validators above — but this list has fourteen items, because the required-but-invisible entry
 * (rule 5) covers two of those fifteen under one rule, as its own text now says. Neither "twelve" nor
 * "twelve items, fourteen throws" is the answer this class gets to give without recounting
 * {@code throw} against {@link #validate}. The descriptor-null guard and the {@code execution.bypass}
 * anti-collision are both included in the current count of fifteen.
 */
public final class NodeTypeDescriptorValidator {
    private NodeTypeDescriptorValidator() {
    }

    /**
     * Validates one node type descriptor before catalog registration, including conditional-property
     * coherence, recovery-repeatability shape, runtime-nature and bypass-flag ownership, and admitted
     * command names.
     *
     * @param descriptor catalog entry to validate; never {@code null}
     * @throws IllegalArgumentException when the descriptor is absent or violates a catalog rule
     */
    public static void validate(NodeTypeDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("A node type descriptor is required");
        }
        Map<String, NodePropertyDescriptor> byName = new LinkedHashMap<>();
        for (NodePropertyDescriptor property : descriptor.properties()) {
            if (byName.put(property.name(), property) != null) {
                throw reject(descriptor, property.name(),
                        "is declared more than once; a condition referencing it would be ambiguous");
            }
        }
        for (NodePropertyDescriptor property : descriptor.properties()) {
            check(descriptor, byName, property, property.visibleWhen(), "visibleWhen");
            check(descriptor, byName, property, property.requiredWhen(), "requiredWhen");
            requiredImpliesVisible(descriptor, property);
            requiredWhenHasNoDefault(descriptor, property);
            defaultValueIsAdmissible(descriptor, property);
        }
        rejectCycles(descriptor, byName);
        // PERS-04 (ADR 0022). `recovery.repeatable` is an ordinary unreserved property, so any
        // node package may declare it — and therefore any node package could declare it with a shape
        // that widens a recovery contract. Anti-collision belongs here rather than in a namespace
        // rule: a descriptor declaring the well-known name with a different shape fails registration
        // visibly, instead of being discovered at the first crash it mis-decides.
        RecoveryRepeatabilityProperty.validateShape(descriptor);
        // ADR 0024 §2. `runtime.nature` is unreserved, so a node package could declare it as
        // an ordinary property — and would then hold a second authority over the same key alongside
        // the descriptor's own allowedNatures. Anti-collision belongs here for the same reason
        // recovery.repeatable's does: registration is the one path every catalog entry takes, and a
        // descriptor that fails is visible now instead of at the first lifecycle it mis-decides.
        NodeRuntimeNatureProperty.validateShape(descriptor);
        // `execution.bypass` is unreserved for the same reason and carries the same collision:
        // its legal values are fixed by the platform at exactly {"true","false"}, so a descriptor
        // declaring the name with its own type, allowed values or default would be a second authority
        // over the one key that decides whether a node executes at all.
        NodeBypassProperty.validateShape(descriptor);
        NodeRuntimeMaxConcurrencyProperty.validateShape(descriptor);
        // Named commands are executable application vocabulary. Parse them at catalog load so a
        // malformed or reserved name cannot become meaningful merely because a graph spells it.
        descriptor.commands().forEach(ai.ravenroot.api.execution.NodeCommand::application);
    }

    private static void check(NodeTypeDescriptor descriptor, Map<String, NodePropertyDescriptor> byName,
                              NodePropertyDescriptor property, PropertyCondition condition, String slot) {
        if (condition == null) {
            return;
        }
        if (!PropertyCondition.CONTRACT.equals(condition.contract())) {
            throw reject(descriptor, property.name(), slot + " declares unknown contract '"
                    + condition.contract() + "'; an unrecognised condition is refused rather than "
                    + "guessed, because guessing it means guessing whether a field is required");
        }
        if (condition.property().equals(property.name())) {
            throw reject(descriptor, property.name(), slot + " refers to itself");
        }
        NodePropertyDescriptor sibling = byName.get(condition.property());
        if (sibling == null) {
            throw reject(descriptor, property.name(), slot + " refers to '" + condition.property()
                    + "', which this node type does not declare");
        }
        List<String> allowed = sibling.allowedValues();
        if (allowed.isEmpty()) {
            return;
        }
        for (String operand : condition.operands()) {
            if (!allowed.contains(operand)) {
                throw reject(descriptor, property.name(), slot + " tests '" + condition.property()
                        + "' against '" + operand + "', which is not one of that property's allowed "
                        + "values " + allowed + ". This check is only possible because the operator set "
                        + "is positive-only; under a negated operator the satisfying set is open-ended "
                        + "and a typo would silently widen visibility instead of failing here");
            }
        }
    }

    /**
     * {@code requiredWhen} must imply {@code visibleWhen}. Enforced conservatively, not decided: the
     * two must name the same sibling, and {@link #satisfyingSetContained} must verify by literal
     * comparison that the required operand set is contained in the visible one. Anything it cannot
     * verify that way is refused rather than approximated — a rule that sometimes admits a
     * required-but-invisible field is not a rule.
     */
    private static void requiredImpliesVisible(NodeTypeDescriptor descriptor, NodePropertyDescriptor property) {
        PropertyCondition required = property.requiredWhen();
        PropertyCondition visible = property.visibleWhen();
        if (required == null || visible == null) {
            return;
        }
        if (!required.property().equals(visible.property())) {
            throw reject(descriptor, property.name(), "requiredWhen tests '" + required.property()
                    + "' while visibleWhen tests '" + visible.property() + "'. They must name the same "
                    + "sibling, or the property can be required in a state where no editor shows it");
        }
        if (!satisfyingSetContained(required, visible)) {
            throw reject(descriptor, property.name(), "requiredWhen and visibleWhen name the same "
                    + "sibling, but this check could not establish that every value satisfying "
                    + "requiredWhen also satisfies visibleWhen -- it compares operators and operand "
                    + "lists literally, and refuses anything it cannot verify that way. That can mean "
                    + "the property really would be mandatory in a state where no editor shows it, or "
                    + "only that the two conditions are equivalent in a way this check does not "
                    + "evaluate. Restate both with the same operator and operand set so the "
                    + "containment is literal, or verify by hand that the field is never mandatory "
                    + "where nothing shows it");
        }
    }

    /**
     * Whether every value satisfying {@code required} is guaranteed, by this comparison alone, to
     * also satisfy {@code visible}. A conservative, syntax-only comparison of operators and operand
     * lists -- not the actual satisfying sets, which {@link PropertyCondition#holds} defines -- so it
     * can return {@code false} for a pair that is genuinely safe. Two known examples, neither of
     * which this method recognises because recognising it needs information this comparison does not
     * use:
     * <ul>
     *   <li>{@code EQUALS}/{@code ONE_OF} naming every value in the sibling's own
     *       {@code allowedValues} denotes the same real condition as {@code PRESENT} -- but that needs
     *       the sibling's descriptor, which this method is never given.</li>
     *   <li>{@code EQUALS} on the operand {@code ""} denotes the same real condition as {@code BLANK}
     *       -- {@link PropertyCondition#holds} strips the <em>sibling's</em> value, not the operand,
     *       before comparing, so a stripped sibling value equals {@code ""} in exactly the states
     *       where {@code BLANK} holds. This is specific to the empty-string operand: {@code
     *       EQUALS(" ")} (or any other blank-but-non-empty operand) compares the stripped sibling
     *       value against a string {@code holds} can never produce by stripping, so that condition
     *       holds for no sibling value at all -- a different gap this method also does not recognise,
     *       harmless today only because an empty satisfying set is contained in anything.</li>
     * </ul>
     * <p>This is intentional and stays that way: fail-closed on a pair this method cannot
     * verify is the correct default, and closing either gap above is separate, larger work than
     * making the rejection message honest about what this method did and did not establish -- which
     * is what the rejection message does. Do not widen this method to close either gap without saying so here first.
     */
    private static boolean satisfyingSetContained(PropertyCondition required, PropertyCondition visible) {
        return switch (visible.operator()) {
            case PRESENT -> switch (required.operator()) {
                // Any positive value match implies non-blank, unless it matches the blank string.
                case EQUALS, ONE_OF -> required.operands().stream().noneMatch(String::isBlank);
                case PRESENT -> true;
                case BLANK -> false;
            };
            case BLANK -> required.operator() == PropertyConditionOperator.BLANK;
            case EQUALS, ONE_OF -> switch (required.operator()) {
                case EQUALS, ONE_OF -> visible.operands().containsAll(required.operands());
                case PRESENT, BLANK -> false;
            };
        };
    }

    private static void requiredWhenHasNoDefault(NodeTypeDescriptor descriptor, NodePropertyDescriptor property) {
        if (property.requiredWhen() != null && !property.defaultValue().isBlank()) {
            throw reject(descriptor, property.name(), "declares requiredWhen and a non-blank "
                    + "defaultValue. The default satisfies the requirement in every state, so the "
                    + "condition would never refuse anything and the descriptor would claim a "
                    + "constraint it does not have");
        }
    }

    /**
     * A non-blank {@code defaultValue} must be a member of the property's own {@code allowedValues},
     * when it declares any. {@code allowedValues} is the closed set of values the property may
     * legitimately hold; a default outside it names a value the property could never actually take, the
     * same incoherence {@link #requiredWhenHasNoDefault} rejects for the required-and-defaulted pair.
     * A blank default means "not declared" and is left alone here regardless of what
     * {@code allowedValues} contains — this rule is about a default that is present but wrong, not
     * about the presence of a default at all.
     *
     * <h2>Why this message can never quote a resolved secret</h2>
     * <p>This method's rejection message names {@code defaultValue} and {@code allowedValues}
     * verbatim, and this class also validates {@link NodePropertyType#SECRET_REFERENCE} properties, so
     * that quoting has to be justified rather than assumed: the message looks generic but is not.
     * The reason is not that these fields are somehow immutable or computed once — they
     * are ordinary record components, and a third-party descriptor's {@code properties()} method is
     * arbitrary Java that could in principle compute either at call time. The reason is narrower and
     * does not depend on how a descriptor is written: {@code validate} has exactly one caller,
     * {@code BehaviorRegistry.registerFactory} ({@code ravenroot-core}, passing
     * {@code factory.descriptor()}), which runs once at catalog registration, before any graph is
     * admitted. Its argument is catalog metadata — the shape a behavior declares for itself — never
     * graph content and never a secret resolved at execution; nothing resolved at runtime is reachable
     * from here at all. And today's catalog closes the specific overlap this rule and
     * {@code SECRET_REFERENCE} could have: every {@code SECRET_REFERENCE} property in this repository
     * ({@code http.request}'s and {@code llm.prompt}'s {@code credentialRef}, {@code mail.send}'s) is
     * declared through {@code NodePropertyDescriptor.optional}, which fixes {@code allowedValues} to
     * empty — so this rule cannot fire on one at all today, independently of the reason above.
     */
    private static void defaultValueIsAdmissible(NodeTypeDescriptor descriptor, NodePropertyDescriptor property) {
        List<String> allowed = property.allowedValues();
        String defaultValue = property.defaultValue();
        if (allowed.isEmpty() || defaultValue.isBlank()) {
            return;
        }
        if (!allowed.contains(defaultValue)) {
            throw reject(descriptor, property.name(), "declares defaultValue '" + defaultValue
                    + "', which is not one of its own allowedValues " + allowed + ". allowedValues is "
                    + "the closed set of values this property may hold, so a default outside it names a "
                    + "value the property could never legitimately take");
        }
    }

    /** Depth-first over property -> referenced sibling edges. Any cycle is a rejection. */
    private static void rejectCycles(NodeTypeDescriptor descriptor, Map<String, NodePropertyDescriptor> byName) {
        Set<String> settled = new HashSet<>();
        for (String start : byName.keySet()) {
            if (settled.contains(start)) {
                continue;
            }
            Set<String> onPath = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            walk(descriptor, byName, start, onPath, settled, stack);
        }
    }

    private static void walk(NodeTypeDescriptor descriptor, Map<String, NodePropertyDescriptor> byName,
                             String name, Set<String> onPath, Set<String> settled, Deque<String> stack) {
        if (settled.contains(name)) {
            return;
        }
        if (!onPath.add(name)) {
            List<String> cycle = new ArrayList<>(stack);
            cycle.add(name);
            throw reject(descriptor, name, "takes part in a conditional cycle " + cycle
                    + ". Cycles are rejected rather than resolved to a fixed point: a solver's answer "
                    + "is not something a plugin author can predict or a reviewer can check by reading");
        }
        stack.addLast(name);
        NodePropertyDescriptor property = byName.get(name);
        if (property != null) {
            for (PropertyCondition condition : conditionsOf(property)) {
                walk(descriptor, byName, condition.property(), onPath, settled, stack);
            }
        }
        stack.removeLast();
        onPath.remove(name);
        settled.add(name);
    }

    private static List<PropertyCondition> conditionsOf(NodePropertyDescriptor property) {
        List<PropertyCondition> conditions = new ArrayList<>(2);
        if (property.visibleWhen() != null) {
            conditions.add(property.visibleWhen());
        }
        if (property.requiredWhen() != null) {
            conditions.add(property.requiredWhen());
        }
        return conditions;
    }

    private static IllegalArgumentException reject(NodeTypeDescriptor descriptor, String property, String detail) {
        return new IllegalArgumentException("Behavior '" + descriptor.behavior() + "' property '"
                + property + "' " + detail);
    }
}
