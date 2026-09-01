package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptorValidator;
import ai.ravenroot.api.catalog.PropertyConditionOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a skill is declared, and every declaration this bundle refuses to build a node from. */
class AgentSkillTest {

    @Test
    @DisplayName("the slots are declared in the descriptor, so the Inspector renders them unaided")
    void theSlotsAreDeclaredProperties() {
        List<NodePropertyDescriptor> declared = AgentSkill.propertyDescriptors();

        assertEquals(AgentSkill.MAX_SKILLS * 3, declared.size());
        // The one property the model's own answer has to reproduce is a single-line string; the body
        // is TEXT, which is what makes the Inspector render it as a textarea rather than an input --
        // the measured reason this shape was chosen over a JSON document. See AgentSkill's javadoc.
        assertEquals(NodePropertyType.STRING, byName(declared, "skills.1.name").type());
        assertEquals(NodePropertyType.STRING, byName(declared, "skills.1.description").type());
        assertEquals(NodePropertyType.TEXT, byName(declared, "skills.1.instructions").type());
        // None of them is required: a node with no skills remains an ordinary agent.
        assertTrue(declared.stream().noneMatch(NodePropertyDescriptor::required));
    }

    @Test
    @DisplayName("slot one is always visible and every later slot appears when the previous is named")
    void laterSlotsDiscloseProgressively() {
        List<NodePropertyDescriptor> declared = AgentSkill.propertyDescriptors();

        assertNull(byName(declared, "skills.1.name").visibleWhen());
        var second = byName(declared, "skills.2.instructions").visibleWhen();
        assertNotNull(second);
        assertEquals("skills.1.name", second.property());
        assertEquals(PropertyConditionOperator.PRESENT, second.operator());
        assertEquals("skills." + (AgentSkill.MAX_SKILLS - 1) + ".name",
                byName(declared, "skills." + AgentSkill.MAX_SKILLS + ".name").visibleWhen().property());
    }

    @Test
    @DisplayName("the descriptor carrying every slot still passes catalog validation")
    void theDescriptorWithEverySlotIsAdmissible() {
        // The chain of conditions is what this proves is acyclic, self-reference-free and pointed at
        // siblings that exist: NodeTypeDescriptorValidator refuses each of those, and a descriptor
        // that failed here would not merely be untidy, it would fail to register at all.
        NodeTypeDescriptorValidator.validate(new AgentNodeBehavior().descriptor());
    }

    @Test
    @DisplayName("three declared skills are read in order")
    void threeSkillsAreRead() {
        List<AgentSkill> skills = AgentSkill.declaredOn(
                AiTestSupport.agentConfiguration(withSkills(3)));

        assertEquals(List.of("skill-1", "skill-2", "skill-3"), skills.stream().map(AgentSkill::name).toList());
        assertEquals("body 2", skills.get(1).instructions());
    }

    @Test
    @DisplayName("a node with no skill properties reads as a node with no skills, not as a defect")
    void noSkillsIsNotADefect() {
        assertEquals(List.of(), AgentSkill.declaredOn(AiTestSupport.agentConfiguration(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi"))));
    }

    @Test
    @DisplayName("a body over the ceiling is refused with the skill's name in front of the author")
    void anOversizeBodyIsRefusedByName() {
        Map<String, Object> properties = withSkills(2);
        properties.put("skills.2.instructions", "x".repeat(AgentSkill.MAX_INSTRUCTIONS_CHARS + 1));

        AgentSkillException refusal = assertThrows(AgentSkillException.class,
                () -> AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties)));

        assertEquals(AgentSkillException.Code.TOO_LARGE, refusal.code());
        // The name and not the slot number: the author knows their skill by what they called it.
        assertEquals("skill-2", refusal.skill());
        // The LENGTH, never the text: a body is graph content and must not travel in a message.
        assertFalse(refusal.getMessage().contains("xxxx"));
    }

    @Test
    @DisplayName("an over-long name is refused by its slot, because the name is the broken field")
    void anOversizeNameIsRefusedBySlot() {
        Map<String, Object> properties = withSkills(1);
        properties.put("skills.1.name", "n".repeat(AgentSkill.MAX_NAME_CHARS + 1));

        AgentSkillException refusal = assertThrows(AgentSkillException.class,
                () -> AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties)));

        assertEquals(AgentSkillException.Code.TOO_LARGE, refusal.code());
        assertEquals("skills.1.name", refusal.skill());
    }

    @Test
    @DisplayName("two names differing only by case are one name to the model, so they are refused")
    void duplicateNamesAreRefused() {
        Map<String, Object> properties = withSkills(2);
        properties.put("skills.2.name", "SKILL-1");

        AgentSkillException refusal = assertThrows(AgentSkillException.class,
                () -> AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties)));

        assertEquals(AgentSkillException.Code.DECLARATION_INVALID, refusal.code());
    }

    @Test
    @DisplayName("a slot filled past a blank one is refused, not silently compacted")
    void aGapIsRefused() {
        Map<String, Object> properties = withSkills(1);
        properties.put("skills.3.name", "orphan");
        properties.put("skills.3.description", "unreachable from the editor");
        properties.put("skills.3.instructions", "body 3");

        // Compacting would run a skill whose controls the Inspector keeps hidden while slot 2 is
        // blank: an active declaration the author cannot see, which is the failure under test.
        AgentSkillException refusal = assertThrows(AgentSkillException.class,
                () -> AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties)));

        assertEquals(AgentSkillException.Code.DECLARATION_INVALID, refusal.code());
        assertEquals("skills.3", refusal.skill());
    }

    @Test
    @DisplayName("a skill without a description is refused: the model would have no reason to load it")
    void aDescriptionlessSkillIsRefused() {
        Map<String, Object> properties = withSkills(1);
        properties.put("skills.1.description", "   ");

        AgentSkillException refusal = assertThrows(AgentSkillException.class,
                () -> AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties)));

        assertEquals(AgentSkillException.Code.DECLARATION_INVALID, refusal.code());
    }

    @Test
    @DisplayName("a named skill with an empty body is refused, because an empty tool result cannot be sent")
    void anEmptyBodyIsRefused() {
        Map<String, Object> properties = withSkills(1);
        properties.put("skills.1.instructions", "");

        AgentSkillException refusal = assertThrows(AgentSkillException.class,
                () -> AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties)));

        assertEquals(AgentSkillException.Code.DECLARATION_INVALID, refusal.code());
    }


    @Test
    @DisplayName("the refusal is an IllegalArgumentException, which is what makes it answerable")
    void theRefusalIsTheTypeTheServerMaps() {
        Map<String, Object> properties = withSkills(1);
        properties.put("skills.1.instructions", "x".repeat(AgentSkill.MAX_INSTRUCTIONS_CHARS + 1));

        RuntimeException refusal = assertThrows(AgentSkillException.class,
                () -> AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties)));

        // Load-bearing, not cosmetic. The server's submission handler maps IllegalArgumentException
        // to INVALID_REQUEST and IllegalStateException to CONFLICT; a RuntimeException of any other
        // type matches no clause and the submission is never answered through the error contract at
        // all. This is CORE-03's JoinConfigurationException precedent, for the same reason.
        assertInstanceOf(IllegalArgumentException.class, refusal);
    }

    @Test
    @DisplayName("every ceiling is stated in the property's own description")
    void theCeilingsAreStatedWhereTheAuthorReadsThem() {
        // Prevention is the only mitigation available inside this bundle: API-01 leaves the server no
        // error signature that carries text, so a refused submission cannot tell an author WHICH
        // skill was too long. The description is where the Inspector can say the
        // limit BEFORE it is exceeded, and asserting it here keeps it from drifting from the constant.
        List<NodePropertyDescriptor> declared = AgentSkill.propertyDescriptors();

        assertTrue(byName(declared, "skills.1.name").description()
                .contains(String.valueOf(AgentSkill.MAX_NAME_CHARS)));
        assertTrue(byName(declared, "skills.1.description").description()
                .contains(String.valueOf(AgentSkill.MAX_DESCRIPTION_CHARS)));
        assertTrue(byName(declared, "skills.1.instructions").description()
                .contains(String.valueOf(AgentSkill.MAX_INSTRUCTIONS_CHARS)));
    }

    @Test
    @DisplayName("a name of exactly the ceiling is accepted; the refusal is over it, not at it")
    void aNameAtTheCeilingIsAccepted() {
        Map<String, Object> properties = withSkills(1);
        String atTheLimit = "n".repeat(AgentSkill.MAX_NAME_CHARS);
        properties.put("skills.1.name", atTheLimit);

        List<AgentSkill> skills = AgentSkill.declaredOn(AiTestSupport.agentConfiguration(properties));

        assertEquals(atTheLimit, skills.get(0).name());
    }

    /** A node configuration declaring {@code count} well-formed skills. */
    static Map<String, Object> withSkills(int count) {
        var properties = new HashMap<String, Object>(Map.of(
                "provider", "local", "instructions", "be terse", "objective", "say hi"));
        for (int slot = 1; slot <= count; slot++) {
            properties.put("skills." + slot + ".name", "skill-" + slot);
            properties.put("skills." + slot + ".description", "what skill " + slot + " is for");
            properties.put("skills." + slot + ".instructions", "body " + slot);
        }
        return properties;
    }

    private static NodePropertyDescriptor byName(List<NodePropertyDescriptor> declared, String name) {
        return declared.stream().filter(property -> property.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no property named " + name));
    }
}
