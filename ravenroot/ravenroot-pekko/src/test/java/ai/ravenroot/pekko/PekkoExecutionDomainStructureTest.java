package ai.ravenroot.pekko;

import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.TrackedExecutionDomain;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That this adapter's domains are <em>native</em>, not the neutral fallback.
 *
 * <h2>Why the conformance suite is not enough on its own</h2>
 * <p>{@code ExecutionEngineContract} asserts the mandatory guarantee — closing a domain terminates
 * exactly its nodes — and {@link TrackedExecutionDomain} satisfies that by bookkeeping alone. So the
 * suite passing says nothing about whether this adapter delivers the isolation ADR 0021 D1 promises.
 * Without the assertions here, "one ActorSystem with graphs distinguishable inside it" would silently
 * become "one ActorSystem with graphs distinguishable in the logs", and every test would still be
 * green.
 */
class PekkoExecutionDomainStructureTest {
    private PekkoExecutionEngine engine;

    @AfterEach
    void close() {
        if (engine != null) {
            engine.close();
        }
    }

    /** The adapter must override the neutral default, or it has no subtree at all. */
    @Test
    void providesANativeDomainRatherThanTheNeutralFallback() {
        engine = new PekkoExecutionEngine("domain-structure-" + UUID.randomUUID());

        ExecutionDomain domain = engine.openDomain("orders");

        assertFalse(domain instanceof TrackedExecutionDomain,
                "this adapter fell back to the engine-neutral tracking domain, so its nodes are flat "
                        + "children of the system guardian and the segregation ADR 0021 D1 promises -- "
                        + "failure isolation at the domain root, thread isolation per domain -- does "
                        + "not exist. The conformance suite cannot see this, which is why it is here");
    }

    /** Each domain owns its own guardian, so two domains are genuinely separate subtrees. */
    @Test
    void givesEachDomainItsOwnGuardian() {
        engine = new PekkoExecutionEngine("domain-guardian-" + UUID.randomUUID());

        var first = (PekkoExecutionEngine.SubtreeDomain) engine.openDomain("orders");
        var second = (PekkoExecutionEngine.SubtreeDomain) engine.openDomain("orders");

        assertNotEquals(first.guardianName(), second.guardianName(),
                "two domains sharing one guardian would share a supervision boundary, so a failure "
                        + "escalating out of one would reach the other's nodes");
        assertTrue(first.guardianName().startsWith("domain-"),
                "the guardian's actor-path segment identifies it as a domain root: " + first.guardianName());
    }

    /**
     * The isolation that matters operationally: one domain closing leaves another's nodes serving.
     * Asserted here against the real adapter as well as in the suite, because this is the adapter
     * whose subtree is doing the work.
     */
    @Test
    void closingOneDomainLeavesAnotherServing() throws Exception {
        engine = new PekkoExecutionEngine("domain-isolation-" + UUID.randomUUID());
        ExecutionDomain first = engine.openDomain("first");
        ExecutionDomain second = engine.openDomain("second");
        NodeRef doomed = first.spawn("doomed", echo());
        NodeRef survivor = second.spawn("survivor", echo());

        first.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

        assertTrue(engine.status(doomed).orElseThrow().state().terminal());
        assertTrue(engine.status(survivor).orElseThrow().state() == NodeLifecycleState.RUNNING);
        assertTrue(engine.send(survivor, message()).toCompletableFuture()
                .get(10, TimeUnit.SECONDS).payload().equals("ping"));
    }

    private static RavenNode echo() {
        return new RavenNode() {
            @Override
            public CompletionStage<NodeResult> onMessage(NodeMessage message, NodeContext context) {
                return CompletableFuture.completedFuture(new NodeResult("continue", message.payload(), Map.of()));
            }
        };
    }

    private static NodeMessage message() {
        var identity = new SecurityContext("request", "tenant", "subject", PrincipalType.WORKLOAD, "issuer");
        return new NodeMessage(identity, UUID.randomUUID(), UUID.randomUUID(), "node", "ping", Map.of());
    }
}
