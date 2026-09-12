package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.PropertyCondition;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * <p>The usual objection to numbered slots — a form cluttered with empty fields — does not apply
 * here, because {@code visibleWhen} already solves it: slot {@code n} is declared visible only
 * when slot {@code n-1} carries a name, so an agent node shows one empty skill and reveals the next
 * as each is filled. The editor's own disclosure is then the same idea as the feature's.</p>
 *
 * <h2>Slots are filled from one upward, with no gaps</h2>
 * <p>A hand-written graph could declare slot 3 while leaving slot 1 blank. That is refused rather
 * than compacted. Compacting would run a skill the Inspector cannot show — slot 3's controls stay
 * {@code hidden} while slot 1 is blank — and an active declaration the author cannot review is the
 * failure mode to avoid. Refusing costs a hand-writer one renumber;
 * compacting costs every author the possibility of an active skill they cannot see.</p>
 *
 * @param name what the model passes to {@code load_skill}; matched case-insensitively
 * @param description the one line the model reads when deciding whether the body is worth loading
 * @param instructions the body, handed over only on request
 */
record AgentSkill(String name, String description, String instructions) {

    /**
     * Slots an author may declare.
     *
     * <p>Small on purpose, and the bound is load-bearing rather than cosmetic: together with
     * {@link LoadSkillTool}'s no-duplicate rule it caps the body text one run can pull into its
     * conversation at {@code MAX_SKILLS × MAX_INSTRUCTIONS_CHARS}, without depending on the endpoint
     * reporting token usage. A node that wants more skills than this is describing a library, and a
     * library requires a shared-skill surface that this bundle does not provide.</p>
     */
    static final int MAX_SKILLS = 8;

    /** A name the model has to reproduce exactly enough to be matched. Long names invite typos. */
    static final int MAX_NAME_CHARS = 64;

    /** A description is the one line that is paid for on every single turn. */
    static final int MAX_DESCRIPTION_CHARS = 512;

    /** A body is paid for once, from the turn it is loaded onward. */
    static final int MAX_INSTRUCTIONS_CHARS = 16_384;

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

    /**
     * The catalog properties for every slot, in order, for {@link AgentNodeBehavior#descriptor()}.
     *
     * <p>Built here rather than spelled out on the descriptor so the property names, the reader in
     * {@link #declaredOn} and the Inspector's controls cannot drift apart: there is one spelling of
     * {@code skills.<n>.name} in this bundle and every consumer goes through it.</p>
     */
    static List<NodePropertyDescriptor> propertyDescriptors() {
        var declared = new ArrayList<NodePropertyDescriptor>(MAX_SKILLS * 3);
        for (int slot = 1; slot <= MAX_SKILLS; slot++) {
            // Slot 1 is unconditional; every later slot appears once the previous one is named. The
            // chain is acyclic and never self-referential, which is what NodeTypeDescriptorValidator
            // checks -- and it is a chain rather than "visible when any earlier slot is named"
            // because a PropertyCondition names exactly one sibling, by design.
            PropertyCondition visibleWhen = slot == 1
                    ? null
                    : PropertyCondition.present(nameProperty(slot - 1));
            declared.add(optional(nameProperty(slot), "Skill " + slot + " name", NodePropertyType.STRING,
                    "What the model passes to load_skill to read this skill. Matched ignoring case. "
                            + "At most " + MAX_NAME_CHARS + " characters.",
                    visibleWhen));
            declared.add(optional(descriptionProperty(slot), "Skill " + slot + " description",
                    NodePropertyType.STRING,
                    "One line telling the model when this skill is worth loading. Shown on every "
                            + "turn, so keep it short. At most " + MAX_DESCRIPTION_CHARS
                            + " characters.",
                    visibleWhen));
            declared.add(optional(instructionsProperty(slot), "Skill " + slot + " instructions",
                    NodePropertyType.TEXT,
                    "The body. Sent only when the model calls load_skill for this name, and never "
                            + "before. It grants no tool and no authority. At most "
                            + MAX_INSTRUCTIONS_CHARS + " characters.",
                    visibleWhen));
        }
        return List.copyOf(declared);
    }

    private static NodePropertyDescriptor optional(String name, String displayName, NodePropertyType type,
                                                   String description, PropertyCondition visibleWhen) {
        // The full canonical constructor because the `optional` factory takes no condition. Never
        // `requiredWhen`: a skill is optional in every state, so the required-implies-visible rule
        // has nothing to check and the conditionally-required-with-a-default rule cannot be tripped.
        return new NodePropertyDescriptor(name, displayName, type, false, description, "", List.of(),
                false, visibleWhen, null);
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
        String nodeId = configuration.nodeId();
        var skills = new ArrayList<AgentSkill>();
        var seen = new LinkedHashSet<String>();
        for (int slot = 1; slot <= MAX_SKILLS; slot++) {
            String name = configuration.property(nameProperty(slot), "").strip();
            String description = configuration.property(descriptionProperty(slot), "").strip();
            String instructions = configuration.property(instructionsProperty(slot), "").strip();
            if (name.isEmpty() && description.isEmpty() && instructions.isEmpty()) {
                // An empty slot ends the list. A later non-empty one is the gap this class refuses.
                refuseGapAfter(configuration, nodeId, slot);
                break;
            }
            skills.add(validated(nodeId, slot, name, description, instructions, seen));
        }
        return List.copyOf(skills);
    }

    private static AgentSkill validated(String nodeId, int slot, String name, String description,
                                        String instructions, Set<String> seen) {
        String at = "skills." + slot;
        if (name.isEmpty()) {
            // Unreachable by name, so it would sit in the graph doing nothing while looking declared.
            throw invalid(nodeId, at + ".name", "declares a description or a body but no name");
        }
        if (name.length() > MAX_NAME_CHARS) {
            throw tooLarge(nodeId, at + ".name", "name", name.length(), MAX_NAME_CHARS);
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
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            throw tooLarge(nodeId, name, "description", description.length(), MAX_DESCRIPTION_CHARS);
        }
        if (instructions.isEmpty()) {
            // AgentTool#invoke may never return an empty string: an empty tool message reads to a
            // model as a call that succeeded and returned nothing. So an empty body cannot be served,
            // and the honest place to say so is here rather than at the first call.
            throw invalid(nodeId, name, "has an empty body, and an empty tool result reads to a "
                    + "model as a call that succeeded and returned nothing");
        }
        if (instructions.length() > MAX_INSTRUCTIONS_CHARS) {
            throw tooLarge(nodeId, name, "body", instructions.length(), MAX_INSTRUCTIONS_CHARS);
        }
        return new AgentSkill(name, description, instructions);
    }

    private static void refuseGapAfter(NodeConfiguration configuration, String nodeId, int emptySlot) {
        for (int later = emptySlot + 1; later <= MAX_SKILLS; later++) {
            if (!configuration.property(nameProperty(later), "").strip().isEmpty()
                    || !configuration.property(descriptionProperty(later), "").strip().isEmpty()
                    || !configuration.property(instructionsProperty(later), "").strip().isEmpty()) {
                throw invalid(nodeId, "skills." + later, "is declared while slot " + emptySlot
                        + " is blank; slots are filled from 1 upward, because the editor keeps a "
                        + "slot hidden until the one before it is named");
            }
        }
    }

    private static AgentSkillException invalid(String nodeId, String skill, String detail) {
        return new AgentSkillException(AgentSkillException.Code.DECLARATION_INVALID, nodeId, skill, detail);
    }

    private static AgentSkillException tooLarge(String nodeId, String skill, String field, int length,
                                                int ceiling) {
        // The LENGTH and the ceiling, never the text itself: the numbers are what an author acts on,
        // and a body is graph content that must not travel in an exception message.
        return new AgentSkillException(AgentSkillException.Code.TOO_LARGE, nodeId, skill,
                "has a " + field + " of " + length + " characters, over the ceiling of " + ceiling);
    }
}
