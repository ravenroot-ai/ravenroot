package ai.ravenroot.extensions.telegram;

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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the recovery loop is told about the Telegram nodes (PERS-04, ADR 0022).
 *
 * <p>Resolved through {@link RepeatabilityDeclarations#fromGraph}, which is the function
 * {@code ExecutionRecoveryService} consults and the only route from the catalog to a sweep. The three
 * action nodes and the send node carry the <em>same</em> authored value, so the difference in what
 * the engine receives is attributable to the catalog and not to the document. The full engine
 * decision — re-dispatch versus park — is driven end to end in {@code MailRecoveryRepeatabilityTest}
 * rather than repeated in each connector.</p>
 */
class TelegramRecoveryRepeatabilityTest {

    private final BehaviorRegistry catalog =
            NodePackages.register(new BehaviorRegistry(), new TelegramNodePackage());

    @Test
    void anEditIsRepeatableAndASendIsInertOnTheIdenticalAuthoredValue() {
        RepeatabilityDeclarations declarations = graphDeclaringRepeatable(
                Map.of("edit-it", "telegram.edit.message", "send-it", "telegram.send"));

        assertEquals(AttemptRepeatability.REPEATABLE, declarations.declaredFor("edit-it"));
        assertTrue(declarations.declaredFor("edit-it").authorisesReDispatch(),
                "a replayed edit converges: Telegram answers 'message is not modified'");
        assertEquals(AttemptRepeatability.UNDECLARED, declarations.declaredFor("send-it"),
                "telegram.send declares nothing, so the identical value is inert rather than "
                        + "authoritative: a repeat would be a second message in the chat");
        assertFalse(declarations.declaredFor("send-it").authorisesReDispatch());
    }

    @Test
    void everyConvergingActionOffersTheContractAndOnlyTheSendWithholdsIt() {
        assertEquals(Set.of("telegram.answer.callback", "telegram.edit.message", "telegram.delete.message"),
                behaviorNames().stream().filter(name ->
                                RecoveryRepeatabilityProperty.declaredBy(catalog.descriptor(name).orElseThrow()))
                        .collect(Collectors.toSet()),
                "a Telegram node was added or its repeatability changed; decide it rather than "
                        + "inheriting whichever neighbour it was copied from");
        assertEquals(Set.of("telegram.send", "telegram.answer.callback", "telegram.edit.message",
                "telegram.delete.message"), behaviorNames());
    }

    @Test
    void noActionDefaultsTheDecision() {
        for (String name : List.of("telegram.answer.callback", "telegram.edit.message",
                "telegram.delete.message")) {
            var declared = catalog.descriptor(name).orElseThrow().properties().stream()
                    .filter(property -> RecoveryRepeatabilityProperty.NAME.equals(property.name()))
                    .findFirst().orElseThrow();
            assertEquals("", declared.defaultValue(), name + " must leave the decision to the author");
            assertEquals(RecoveryRepeatabilityProperty.ALLOWED_VALUES, declared.allowedValues());
        }
    }

    private Set<String> behaviorNames() {
        return new TelegramNodePackage().behaviors().stream()
                .map(behavior -> behavior.descriptor().behavior()).collect(Collectors.toSet());
    }

    private RepeatabilityDeclarations graphDeclaringRepeatable(Map<String, String> nodeIdToBehavior) {
        Map<String, Object> authored =
                Map.of(RecoveryRepeatabilityProperty.NAME, RecoveryRepeatabilityProperty.REPEATABLE);
        return RepeatabilityDeclarations.fromGraph(nodeIdToBehavior.entrySet().stream()
                .map(entry -> new GraphNode(entry.getKey(), NodeKind.BEHAVIOR, entry.getValue(), authored))
                .toList(), catalog::descriptor);
    }
}
