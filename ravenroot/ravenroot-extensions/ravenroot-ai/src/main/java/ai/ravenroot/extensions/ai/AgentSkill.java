package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyGroupDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.node.NodeConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One skill declared by the author of a graph: a name, a short description, and a body of
 * instructions that is loaded only when the model asks for it.
 *
 * <h2>Why the body is not simply appended to the instructions</h2>
 * <p>Because that is the whole of what makes a skill a skill rather than more text in a prompt.
 * {@link LoadSkillTool} hands the body over on request, so the untrusted author turn carries only the name and
 * the description: an unused skill then costs its one-line entry and nothing else, and ten declared
 * skills do not multiply the conversation by ten on every turn. The bounded agent loop makes this
 * possible, and {@code load_skill} uses that loop for exactly this purpose.</p>
 *
 * <h2>Three numbered properties per slot, and why not one JSON document</h2>
 * <p>A skill is a triple and {@link NodePropertyType} has no record or list type, so the two shapes
 * available were {@code n} numbered triples and a single JSON document in one {@code TEXT} property.
 * <b>This is measured, not preferred.</b> The Inspector derives its controls from the descriptor —
 * {@code catalogPropertyFieldsHtml} maps over {@code descriptor.properties} — and it renders
 * {@code TEXT} as a {@code <textarea>} and {@code STRING} as a single-line input. So under numbered
 * triples a skill body is written as what it is, prose with blank lines in a real multi-line control;
 * under the JSON document the same body has to be typed as an <em>escaped JSON string</em> inside
 * that same textarea, with every paragraph break hand-written as {@code \n}. The compactness the JSON
 * form buys is paid by the person least able to afford it, and one misplaced quote invalidates every
 * skill on the node at once instead of one field.</p>
 *
 * <p>The descriptor publishes the triple as a trusted dynamic additional-property group rather than
 * enumerating a finite number of slots. An editor can therefore add and remove complete items
 * without a baked-in count, while the runtime still type-checks every materialized field.</p>
 *
 * <h2>Slots are filled from one upward, with no gaps</h2>
 * <p>A hand-written graph could declare slot 3 while leaving slot 1 blank. That is refused rather
 * than compacted. Compacting would make stored identity depend on which items happen to be absent
 * and could execute a declaration under an index different from the one an author reviewed.
 * Refusing costs a hand-writer one renumber;
 * compacting costs every author the possibility of an active skill they cannot see.</p>
 *
 * @param name what the model passes to {@code load_skill}; matched case-insensitively
 * @param description the one line the model reads when deciding whether the body is worth loading
 * @param instructions the body, handed over only on request
 */
record AgentSkill(String name, String description, String instructions) {

    /** The property an author writes a skill's name into. */
    static String nameProperty(int slot) {
        return "skills." + slot + ".name";
    }

    /** The property an author writes a skill's description into. */
    static String descriptionProperty(int slot) {
        return "skills." + slot + ".description";
    }

    /** The property an author writes a skill's body into. */
    static String instructionsProperty(int slot) {
        return "skills." + slot + ".instructions";
    }

    /** Trusted dynamic descriptor for complete numbered skill triples. */
    static NodePropertyGroupDescriptor propertyGroup() {
        return new NodePropertyGroupDescriptor("skills", "Skill",
                "An ordered collection of complete name, description, and instructions groups. "
                        + "The executing runtime applies its configured payload ceilings.",
                List.of(
                        NodePropertyDescriptor.required("name", "Name", NodePropertyType.STRING,
                                "Name passed to load_skill; matched ignoring case."),
                        NodePropertyDescriptor.required("description", "Description", NodePropertyType.STRING,
                                "One line telling the model when the skill is worth loading."),
                        NodePropertyDescriptor.required("instructions", "Instructions", NodePropertyType.TEXT,
                                "Body returned only when the model calls load_skill.")));
    }

    /**
     * Reads every declared skill off one node's configuration, refusing a defective declaration.
     *
     * <p><b>Every refusal here is a throw, and that is deliberate.</b> The rule this node otherwise
     * lives by — a refusal is a failed future, never a synchronous throw — governs
     * {@link ai.ravenroot.api.node.NodeAction}, which runs on every message. This runs in
     * {@code create}, whose contract says the opposite in as many words: "throwing here refuses the
     * whole graph. That is the right response to a node this behavior cannot ever serve". A skill
     * body over the ceiling is exactly that — nothing about the deployment can resolve it, only the
     * author can. A missing profile stays a failed future for the same reason read the other way: an
     * operator can declare one without touching the graph.</p>
     *
     * <p>The thrown type is an {@link IllegalArgumentException}, which is not a detail: it is what
     * the server's submission handler maps to a refused request. See {@link AgentSkillException} —
     * including for what the author does <em>not</em> get to read, which is the message.</p>
     *
     * @throws AgentSkillException naming the offending skill, or its slot when the name is the field
     *     at fault
     */
    static List<AgentSkill> declaredOn(NodeConfiguration configuration) {
        return declaredOn(configuration, AgentOperationalConfiguration.defaults());
    }

    static List<AgentSkill> declaredOn(NodeConfiguration configuration,
                                       AgentOperationalConfiguration policy) {
        String nodeId = configuration.nodeId();
        Map<Integer, Map<String, String>> items = new TreeMap<>();
        for (var property : configuration.properties().entrySet()) {
            AgentSkill.propertyGroup().match(property.getKey()).ifPresent(match ->
                    items.computeIfAbsent(match.index(), ignored -> new java.util.LinkedHashMap<>())
                            .put(match.field().name(), property.getValue() == null
                                    ? "" : property.getValue().toString().strip()));
        }
        var skills = new java.util.ArrayList<AgentSkill>(items.size());
        var seen = new LinkedHashSet<String>();
        long payloadBytes = 0;
        int expected = 1;
        for (var item : items.entrySet()) {
            int slot = item.getKey();
            if (slot != expected) {
                throw invalid(nodeId, "skills." + slot, "is declared while slot " + expected
                        + " is absent; indices must be contiguous from 1");
            }
            String name = item.getValue().getOrDefault("name", "");
            String description = item.getValue().getOrDefault("description", "");
            String instructions = item.getValue().getOrDefault("instructions", "");
            AgentSkill skill = validated(nodeId, slot, name, description, instructions, seen, policy);
            payloadBytes += utf8Bytes(name) + utf8Bytes(description) + utf8Bytes(instructions);
            if (payloadBytes > policy.maxSkillPayloadBytes()) {
                throw tooLarge(nodeId, "skills", "combined UTF-8 payload", payloadBytes,
                        policy.maxSkillPayloadBytes());
            }
            skills.add(skill);
            expected++;
        }
        return List.copyOf(skills);
    }

    private static AgentSkill validated(String nodeId, int slot, String name, String description,
                                        String instructions, Set<String> seen,
                                        AgentOperationalConfiguration policy) {
        String at = "skills." + slot;
        if (name.isEmpty()) {
            // Unreachable by name, so it would sit in the graph doing nothing while looking declared.
            throw invalid(nodeId, at + ".name", "declares a description or a body but no name");
        }
        if (name.length() > policy.maxSkillNameChars()) {
            throw tooLarge(nodeId, at + ".name", "name", name.length(), policy.maxSkillNameChars());
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            // A name with a line break in it would break the one-entry-per-line listing the model
            // reads, and would be untypable back as a tool argument.
            throw invalid(nodeId, at + ".name", "has a line break or a control character in its name");
        }
        if (!seen.add(name.toLowerCase(Locale.ROOT))) {
            // Case-insensitively, because that is how load_skill matches: two names differing only by
            // case would be one name to the model and two to the author.
            throw invalid(nodeId, name, "repeats a name already declared on this node; names are "
                    + "matched ignoring case, so two that differ only by case are one name");
        }
        if (description.isEmpty()) {
            // The description is the entire basis on which the model decides to load the body. A
            // skill without one is a name the model has no reason to ever call.
            throw invalid(nodeId, name, "has no description, which is the only basis the model has "
                    + "for deciding whether to load it");
        }
        if (description.length() > policy.maxSkillDescriptionChars()) {
            throw tooLarge(nodeId, name, "description", description.length(),
                    policy.maxSkillDescriptionChars());
        }
        if (instructions.isEmpty()) {
            // AgentTool#invoke may never return an empty string: an empty tool message reads to a
            // model as a call that succeeded and returned nothing. So an empty body cannot be served,
            // and the honest place to say so is here rather than at the first call.
            throw invalid(nodeId, name, "has an empty body, and an empty tool result reads to a "
                    + "model as a call that succeeded and returned nothing");
        }
        if (instructions.length() > policy.maxSkillInstructionsChars()) {
            throw tooLarge(nodeId, name, "body", instructions.length(),
                    policy.maxSkillInstructionsChars());
        }
        return new AgentSkill(name, description, instructions);
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static AgentSkillException invalid(String nodeId, String skill, String detail) {
        return new AgentSkillException(AgentSkillException.Code.DECLARATION_INVALID, nodeId, skill, detail);
    }

    private static AgentSkillException tooLarge(String nodeId, String skill, String field, long length,
                                                int ceiling) {
        // The LENGTH and the ceiling, never the text itself: the numbers are what an author acts on,
        // and a body is graph content that must not travel in an exception message.
        return new AgentSkillException(AgentSkillException.Code.TOO_LARGE, nodeId, skill,
                "has a " + field + " of " + length + " characters, over the ceiling of " + ceiling);
    }
}
