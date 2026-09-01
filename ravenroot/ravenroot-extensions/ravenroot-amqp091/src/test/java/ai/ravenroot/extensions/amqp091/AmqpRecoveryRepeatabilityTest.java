package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.recovery.RepeatabilityDeclarations;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the recovery loop is told about the AMQP nodes (PERS-04, ADR 0022).
 *
 * <p>Resolved through {@link RepeatabilityDeclarations#fromGraph}, the function
 * {@code ExecutionRecoveryService} consults. Both nodes carry the same authored value, so the
 * difference is the catalog's and not the document's.</p>
 */
class AmqpRecoveryRepeatabilityTest {

    private final BehaviorRegistry catalog =
            NodePackages.register(new BehaviorRegistry(), new AmqpNodePackage());

    @Test
    void aPublishCanBeDeclaredRepeatableAndAnInboundSourceCannot() {
        Map<String, Object> authored =
                Map.of(RecoveryRepeatabilityProperty.NAME, RecoveryRepeatabilityProperty.REPEATABLE);
        RepeatabilityDeclarations declarations = RepeatabilityDeclarations.fromGraph(List.of(
                        new GraphNode("publish-it", NodeKind.BEHAVIOR, AmqpPublishNodeBehavior.BEHAVIOR, authored),
                        new GraphNode("consume-it", NodeKind.BEHAVIOR, AmqpConsumeNodeBehavior.BEHAVIOR, authored)),
                catalog::descriptor);

        assertEquals(AttemptRepeatability.REPEATABLE, declarations.declaredFor("publish-it"));
        assertTrue(declarations.declaredFor("publish-it").authorisesReDispatch(),
                "the broker does not deduplicate, but a consumer keyed on the message id can, and "
                        + "that is the author's knowledge to assert");
        assertEquals(AttemptRepeatability.UNDECLARED, declarations.declaredFor("consume-it"),
                "an inbound source has no attempt of its own to repeat, so the identical value is "
                        + "inert: its unit is a delivery and its acknowledgement");
    }

    /**
     * The roster, pinned. Without this an AMQP node added tomorrow inherits whichever neighbour it
     * was copied from instead of deciding, which is exactly the unguarded-reader shape this contract
     * has one of per declaring type.
     */
    @Test
    void exactlyThePublishOffersTheContractAcrossTheWholePackage() {
        var behaviors = new AmqpNodePackage().behaviors().stream()
                .map(behavior -> behavior.descriptor()).toList();
        assertEquals(java.util.Set.of(AmqpPublishNodeBehavior.BEHAVIOR, AmqpConsumeNodeBehavior.BEHAVIOR),
                behaviors.stream().map(descriptor -> descriptor.behavior())
                        .collect(java.util.stream.Collectors.toSet()),
                "an AMQP node was added or removed; decide its repeatability rather than leaving it out");
        assertEquals(java.util.Set.of(AmqpPublishNodeBehavior.BEHAVIOR),
                behaviors.stream().filter(RecoveryRepeatabilityProperty::declaredBy)
                        .map(descriptor -> descriptor.behavior())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void thePublishNodeOffersTheContractWithoutDefaultingIt() {
        var declared = catalog.descriptor(AmqpPublishNodeBehavior.BEHAVIOR).orElseThrow().properties().stream()
                .filter(property -> RecoveryRepeatabilityProperty.NAME.equals(property.name()))
                .findFirst().orElseThrow();
        assertEquals("", declared.defaultValue());
        assertEquals(RecoveryRepeatabilityProperty.ALLOWED_VALUES, declared.allowedValues());
        assertFalse(RecoveryRepeatabilityProperty.declaredBy(
                        catalog.descriptor(AmqpConsumeNodeBehavior.BEHAVIOR).orElseThrow()),
                "offering it on a source would ask an author to decide about an event that never "
                        + "happens on that node");
    }
}
