package ai.ravenroot.extensions.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pins the rendering this module copied, so a divergence from the core's is a failure here. */
class PromptTemplateTest {

    @Test
    @DisplayName("the bare token, a nested path and a list index all resolve")
    void tokensResolve() {
        assertEquals("Summarise a report",
                PromptTemplate.render("Summarise {{payload}}", "a report", Map.of(), Map.of()));
        assertEquals("Summarise Milo",
                PromptTemplate.render("Summarise {{payload.pet.name}}",
                        Map.of("pet", Map.of("name", "Milo")), Map.of(), Map.of()));
        assertEquals("Summarise second",
                PromptTemplate.render("Summarise {{payload.items.1}}",
                        Map.of("items", List.of("first", "second")), Map.of(), Map.of()));
    }

    @Test
    @DisplayName("an Object[] is indexed like a List, because a CEL list literal arrives as one")
    void anArrayIsIndexedLikeAList() {
        assertEquals("second", PromptTemplate.render("{{payload.items.1}}",
                Map.of("items", new Object[] { "first", "second" }), Map.of(), Map.of()));
    }

    @Test
    @DisplayName("attributes and properties are substituted after the payload tokens")
    void attributesAndPropertiesAreSubstituted() {
        assertEquals("x=1 y=2", PromptTemplate.render("x={{attributes.x}} y={{properties.y}}",
                null, Map.of("x", 1), Map.of("y", 2)));
    }

    @Test
    @DisplayName("an unresolvable token fails rather than rendering empty or leaving itself in place")
    void anUnresolvableTokenFails() {
        for (String template : new String[] {
                "{{payload}}", "{{payload.missing}}", "{{payload.items.9}}", "{{payload.a..b}}",
                "{{payload.items.notAnIndex}}" }) {
            assertThrows(LlmPromptException.class, () -> PromptTemplate.render(template,
                    template.equals("{{payload}}") ? null : Map.of("items", List.of("only")),
                    Map.of(), Map.of()), template);
        }
    }

    @Test
    @DisplayName("a null entry is a failure, not the string \"null\"")
    void aNullEntryFails() {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("name", null);

        assertThrows(LlmPromptException.class,
                () -> PromptTemplate.render("{{payload.name}}", payload, Map.of(), Map.of()));
    }

    @Test
    @DisplayName("a replacement carrying a dollar or a backslash is quoted, not interpreted")
    void aReplacementIsQuoted() {
        assertEquals("cost $1\\2", PromptTemplate.render("cost {{payload}}", "$1\\2", Map.of(), Map.of()));
    }
}
