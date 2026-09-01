package ai.ravenroot.api.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declaration channel for {@code recovery.repeatable} (PERS-04, ADR 0022 §2).
 *
 * <p>The property is ordinary and unreserved, so any node package may declare it. Two things must
 * therefore hold and are asserted here rather than assumed: a descriptor declaring the well-known
 * name with a non-canonical shape fails catalog load, and a node whose descriptor never declared the
 * property cannot be read as declaring anything however its graph reads.</p>
 */
class RecoveryRepeatabilityPropertyTest {

    @Test
    void theWellKnownNameIsOrdinaryAndTheReservedNamespaceStaysClosed() {
        assertEquals("recovery.repeatable", RecoveryRepeatabilityProperty.NAME);
        assertFalse(RecoveryRepeatabilityProperty.NAME.startsWith("ravenroot."),
                "the reserved namespace protects platform-asserted facts from author forgery; this "
                        + "value is an author assertion of domain knowledge and does not belong in it");
        assertEquals(List.of("repeatable", "not-repeatable"), RecoveryRepeatabilityProperty.ALLOWED_VALUES);
    }

    @Test
    void theCanonicalDeclarationPassesValidationAndCarriesNoDefault() {
        NodeTypeDescriptor descriptor = typeDeclaring(RecoveryRepeatabilityProperty.declaration(null));
        NodeTypeDescriptorValidator.validate(descriptor);
        assertTrue(RecoveryRepeatabilityProperty.declaredBy(descriptor));
        assertEquals("", descriptor.properties().getLast().defaultValue());
    }

    @Test
    void aDescriptorDeclaringTheWellKnownNameWithAWrongShapeFailsCatalogLoad() {
        // Widened values: an instance could then declare something the recovery contract never defined.
        var widened = new NodePropertyDescriptor(RecoveryRepeatabilityProperty.NAME, "Repeatable",
                NodePropertyType.STRING, false, "", "", List.of("repeatable", "not-repeatable", "maybe"),
                false, null, null);
        var widenedFailure = assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(typeDeclaring(widened)));
        assertTrue(widenedFailure.getMessage().contains(RecoveryRepeatabilityProperty.NAME));

        // No allowed values at all: every string becomes a candidate declaration.
        var unconstrained = new NodePropertyDescriptor(RecoveryRepeatabilityProperty.NAME, "Repeatable",
                NodePropertyType.STRING, false, "", "", List.of(), false, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(typeDeclaring(unconstrained)));

        // Wrong type.
        var wrongType = new NodePropertyDescriptor(RecoveryRepeatabilityProperty.NAME, "Repeatable",
                NodePropertyType.BOOLEAN, false, "", "", RecoveryRepeatabilityProperty.ALLOWED_VALUES,
                false, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(typeDeclaring(wrongType)));
    }

    @Test
    void aDescriptorThatDefaultsToRepeatableFailsCatalogLoad() {
        // The single most dangerous shape: it turns the fail-closed contract fail-open for every
        // instance of the type, and produces exactly the state an author who chose it would produce,
        // so nothing downstream could ever tell the two apart.
        var failOpen = new NodePropertyDescriptor(RecoveryRepeatabilityProperty.NAME, "Repeatable",
                NodePropertyType.STRING, false, "", "repeatable",
                RecoveryRepeatabilityProperty.ALLOWED_VALUES, false, null, null);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> NodeTypeDescriptorValidator.validate(typeDeclaring(failOpen)));
        assertTrue(failure.getMessage().contains("fail open"));

        // Defaulting the other way only adds safety, so it is legitimate.
        var failClosed = new NodePropertyDescriptor(RecoveryRepeatabilityProperty.NAME, "Repeatable",
                NodePropertyType.STRING, false, "", "not-repeatable",
                RecoveryRepeatabilityProperty.ALLOWED_VALUES, false, null, null);
        NodeTypeDescriptorValidator.validate(typeDeclaring(failClosed));
    }

    /**
     * The structural guarantee: the descriptor is a parameter, so a value the catalog never
     * sanctioned cannot become a declaration.
     */
    @Test
    void aNodeWhoseDescriptorNeverDeclaredThePropertyIsUndeclaredHoweverItsGraphReads() {
        NodeTypeDescriptor undeclaring = new NodeTypeDescriptor("plain", "Plain", "General", "",
                "actor", false, List.of(NodePropertyDescriptor.optional("url", "URL",
                        NodePropertyType.STRING, "", "")), Set.of());

        assertFalse(RecoveryRepeatabilityProperty.declaredBy(undeclaring));
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.read(undeclaring,
                        Map.of(RecoveryRepeatabilityProperty.NAME, "repeatable")),
                "a stray key on a node whose type never declared the contract is inert; a raw string "
                        + "lookup would read it as authoritative and the failure would be invisible");
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.read(null,
                Map.of(RecoveryRepeatabilityProperty.NAME, "repeatable")));
    }

    @Test
    void everyUnusableValueIsUndeclaredAndOnlyTheExactTokenAuthorisesRepeating() {
        NodeTypeDescriptor declaring = typeDeclaring(RecoveryRepeatabilityProperty.declaration(null));

        assertEquals(AttemptRepeatability.UNDECLARED,
                RecoveryRepeatabilityProperty.read(declaring, Map.of()), "the instance omitted it");
        assertEquals(AttemptRepeatability.UNDECLARED,
                RecoveryRepeatabilityProperty.read(declaring, null));
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.parse(null));
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.parse(""));
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.parse("   "));
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.parse("yes"));
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.parse("true"));
        assertEquals(AttemptRepeatability.UNDECLARED, RecoveryRepeatabilityProperty.parse("REPEATABLE"),
                "an approximate match is the one place where being helpful repeats an effect");

        assertEquals(AttemptRepeatability.NOT_REPEATABLE,
                RecoveryRepeatabilityProperty.parse("not-repeatable"));
        assertEquals(AttemptRepeatability.REPEATABLE, RecoveryRepeatabilityProperty.read(declaring,
                Map.of(RecoveryRepeatabilityProperty.NAME, " repeatable ")));

        assertTrue(AttemptRepeatability.REPEATABLE.authorisesReDispatch());
        assertFalse(AttemptRepeatability.NOT_REPEATABLE.authorisesReDispatch());
        assertFalse(AttemptRepeatability.UNDECLARED.authorisesReDispatch());
    }

    @Test
    void theConditionalFormForcesTheDeclarationExactlyWhereItMatters() {
        NodePropertyDescriptor conditional = RecoveryRepeatabilityProperty.declaration(null,
                PropertyCondition.oneOf("method", "POST", "PUT", "PATCH", "DELETE"));
        var httpLike = new NodeTypeDescriptor("http.request", "HTTP", "Integration", "", "actor", false,
                List.of(new NodePropertyDescriptor("method", "Method", NodePropertyType.STRING, false,
                        "", "GET", List.of("GET", "POST", "PUT", "PATCH", "DELETE")), conditional),
                Set.of("side-effect"));

        NodeTypeDescriptorValidator.validate(httpLike);
        assertEquals(List.of("POST", "PUT", "PATCH", "DELETE"), conditional.requiredWhen().values());
    }

    private static NodeTypeDescriptor typeDeclaring(NodePropertyDescriptor property) {
        return new NodeTypeDescriptor("mail.send", "Mail", "Integration", "", "actor", false,
                List.of(property), Set.of("side-effect"));
    }
}
