package ai.ravenroot.core.recovery;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declaration channel as the production catalog actually loads it (PERS-04, ADR 0022 §2).
 *
 * <p>{@code RecoveryRepeatabilityPropertyTest} proves the rules; this proves they are <em>wired</em>.
 * A validator nobody calls on the path that matters is a control that cannot fail, and the two
 * failures look identical from a green build.</p>
 */
class RecoveryRepeatabilityCatalogTest {

    @Test
    void httpRequestDeclaresTheContractAndRequiresItExactlyWhereTheAnswerHasConsequences() {
        // Through the production catalog, not the factory class: the descriptor a running system
        // serves is the one that matters, and it is the one an operator and the editor both read.
        NodeTypeDescriptor descriptor = BehaviorRegistry.standard().descriptor("http-request").orElseThrow();
        assertTrue(RecoveryRepeatabilityProperty.declaredBy(descriptor));

        NodePropertyDescriptor declared = descriptor.properties().stream()
                .filter(property -> RecoveryRepeatabilityProperty.NAME.equals(property.name()))
                .findFirst().orElseThrow();
        assertEquals(RecoveryRepeatabilityProperty.ALLOWED_VALUES, declared.allowedValues());
        assertEquals("", declared.defaultValue(),
                "a platform type must not default this; a default of 'repeatable' would make the "
                        + "fail-closed contract fail open for every http.request instance at once");
        assertEquals(List.of("POST", "PUT", "PATCH", "DELETE"), declared.requiredWhen().values(),
                "a GET is idempotent and a POST is not, and the side-effect capability tag cannot "
                        + "express the difference — which is why tags were rejected as a contract");
        assertEquals("method", declared.requiredWhen().property());
    }

    @Test
    void aBehaviorDeclaringTheWellKnownNameWithAWrongShapeIsRefusedAtRegistration() {
        var widened = new NodePropertyDescriptor(RecoveryRepeatabilityProperty.NAME, "Repeatable",
                NodePropertyType.STRING, false, "", "", List.of("repeatable", "not-repeatable", "maybe"),
                false, null, null);

        var registry = new BehaviorRegistry();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> registry.registerFactory(factoryDeclaring(widened)));
        assertTrue(failure.getMessage().contains(RecoveryRepeatabilityProperty.NAME),
                "the rejection must name the property, or an operator reads it as an unrelated "
                        + "catalog defect: " + failure.getMessage());
    }

    @Test
    void aBehaviorDeclaringItCanonicallyRegistersNormally() {
        var registry = new BehaviorRegistry();
        registry.registerFactory(factoryDeclaring(RecoveryRepeatabilityProperty.declaration(null)));
        assertTrue(registry.descriptor("rogue").isPresent());
    }

    private static NodeBehaviorFactory factoryDeclaring(NodePropertyDescriptor property) {
        return new NodeBehaviorFactory() {
            @Override
            public NodeTypeDescriptor descriptor() {
                return new NodeTypeDescriptor("rogue", "Rogue", "General", "", "actor", false,
                        List.of(property), Set.of());
            }

            @Override
            public ai.ravenroot.core.runtime.NodeHandler create(ai.ravenroot.core.graph.GraphNode node) {
                throw new UnsupportedOperationException("not created in this test");
            }
        };
    }
}
