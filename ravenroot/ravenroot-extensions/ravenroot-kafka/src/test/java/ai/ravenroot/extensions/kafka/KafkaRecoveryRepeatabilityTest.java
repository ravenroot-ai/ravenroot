package ai.ravenroot.extensions.kafka;

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
 * What the recovery loop is told about the Kafka nodes (PERS-04, ADR 0022).
 *
 * <p>Resolved through {@link RepeatabilityDeclarations#fromGraph}, the function
 * {@code ExecutionRecoveryService} consults. Both nodes carry the same authored value, so the
 * difference is the catalog's and not the document's.</p>
 *
 * <p>The producer node's own description says it produces an "idempotent" record, meaning
 * {@code enable.idempotence}. That setting deduplicates the client's own in-session retries and has
 * no bearing on a recovery re-dispatch, which is a fresh send. The contract is therefore offered as
 * an author assertion about the consumer, never asserted by the node.</p>
 */
class KafkaRecoveryRepeatabilityTest {

    private final BehaviorRegistry catalog =
            NodePackages.register(new BehaviorRegistry(), new KafkaNodePackage());

    @Test
    void aProduceCanBeDeclaredRepeatableAndAnInboundSourceCannot() {
        Map<String, Object> authored =
                Map.of(RecoveryRepeatabilityProperty.NAME, RecoveryRepeatabilityProperty.REPEATABLE);
        RepeatabilityDeclarations declarations = RepeatabilityDeclarations.fromGraph(List.of(
                        new GraphNode("produce-it", NodeKind.BEHAVIOR, KafkaProduceNodeBehavior.BEHAVIOR, authored),
                        new GraphNode("consume-it", NodeKind.BEHAVIOR, KafkaConsumeNodeBehavior.BEHAVIOR, authored)),
                catalog::descriptor);

        assertEquals(AttemptRepeatability.REPEATABLE, declarations.declaredFor("produce-it"));
        assertTrue(declarations.declaredFor("produce-it").authorisesReDispatch());
        assertEquals(AttemptRepeatability.UNDECLARED, declarations.declaredFor("consume-it"),
                "an inbound source has no attempt of its own to repeat, so the identical value is "
                        + "inert: its unit is a record and its offset commit");
    }

    /**
     * The roster, pinned, for the reason given on {@code AmqpRecoveryRepeatabilityTest}: a property of
     * a type has as many unguarded readers as the type has declarers, and the next one is added by
     * somebody who read neither of the first two.
     */
    @Test
    void exactlyTheProduceOffersTheContractAcrossTheWholePackage() {
        var behaviors = new KafkaNodePackage().behaviors().stream()
                .map(behavior -> behavior.descriptor()).toList();
        assertEquals(java.util.Set.of(KafkaProduceNodeBehavior.BEHAVIOR, KafkaConsumeNodeBehavior.BEHAVIOR),
                behaviors.stream().map(descriptor -> descriptor.behavior())
                        .collect(java.util.stream.Collectors.toSet()),
                "a Kafka node was added or removed; decide its repeatability rather than leaving it out");
        assertEquals(java.util.Set.of(KafkaProduceNodeBehavior.BEHAVIOR),
                behaviors.stream().filter(RecoveryRepeatabilityProperty::declaredBy)
                        .map(descriptor -> descriptor.behavior())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void theProduceNodeOffersTheContractWithoutDefaultingIt() {
        var declared = catalog.descriptor(KafkaProduceNodeBehavior.BEHAVIOR).orElseThrow().properties().stream()
                .filter(property -> RecoveryRepeatabilityProperty.NAME.equals(property.name()))
                .findFirst().orElseThrow();
        assertEquals("", declared.defaultValue(),
                "producer idempotence is not a licence to default this to repeatable");
        assertEquals(RecoveryRepeatabilityProperty.ALLOWED_VALUES, declared.allowedValues());
        assertFalse(RecoveryRepeatabilityProperty.declaredBy(
                catalog.descriptor(KafkaConsumeNodeBehavior.BEHAVIOR).orElseThrow()));
    }
}
