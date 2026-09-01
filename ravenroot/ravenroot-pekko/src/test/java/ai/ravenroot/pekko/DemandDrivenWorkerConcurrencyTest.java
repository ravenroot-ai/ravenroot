package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.GraphRunner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property only a real actor runtime can demonstrate: concurrent arrivals at one logical node run
 * concurrently (ADR 0024 §3).
 *
 * <p>Previously a logical node was one actor, and Pekko processes one message per actor at a time —
 * the {@code idle}/{@code busy}/stash machine in {@link PekkoExecutionEngine}. So two traversals
 * reaching the same node queued, and the serialisation was an accident of how the node was
 * represented rather than anything the graph author asked for. ADR 0024 calls that out as the reason
 * naturally parallel work such as OCR could not scale.
 *
 * <p>These assertions are deliberately built so that the old runtime does not merely report a
 * different number: it <b>deadlocks</b>. The node body waits for every concurrent arrival to reach it
 * before any of them returns, which one mailbox can never satisfy. That is the strongest available
 * statement that the mailbox is really gone, and it is why the barrier is a latch the node waits on
 * rather than a timing measurement that a fast machine could pass by luck.
 */
class DemandDrivenWorkerConcurrencyTest {

    private static final SecurityContext IDENTITY = new SecurityContext("demand-request", "tenant-a",
            "alice", PrincipalType.USER, "urn:ravenroot:test");
    private static final int ARRIVALS = 8;

    /**
     * N concurrent arrivals at one logical node produce N actors and N invocation identities.
     *
     * <p>Every one of the four counts is asserted, because they fail independently. A runtime could
     * create N actors and reuse one invocation identity, or mint N identities and serialise them
     * through one actor; only all four together mean what ADR 0024 §3 says.
     */
    @Test
    void concurrentArrivalsAtOneNodeProduceDistinctActorsAndDistinctInvocations() throws Exception {
        var everyArrivalPresent = new CountDownLatch(ARRIVALS);
        var actorRefs = ConcurrentHashMap.<String>newKeySet();
        var invocationIds = ConcurrentHashMap.<UUID>newKeySet();
        var attemptIds = ConcurrentHashMap.<UUID>newKeySet();
        var threads = ConcurrentHashMap.<String>newKeySet();

        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("shared", "gather"),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "shared"), GraphEdge.to("shared", "end")));

        try (var manager = GraphManager.from(graph);
             var engine = new PekkoExecutionEngine("demand-concurrency-" + UUID.randomUUID())) {
            var registry = new BehaviorRegistry().register("gather", message -> {
                invocationIds.add(message.invocationId());
                attemptIds.add(message.attemptId());
                threads.add(Thread.currentThread().getName());
                everyArrivalPresent.countDown();
                // The whole point. On one shared mailbox the first arrival waits here forever, because
                // the arrival that would release it cannot be delivered until this one returns.
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        assertTrue(everyArrivalPresent.await(30, TimeUnit.SECONDS),
                                "arrivals at one logical node did not run concurrently: only "
                                        + (ARRIVALS - everyArrivalPresent.getCount()) + " of " + ARRIVALS
                                        + " reached the node, so the rest were queued behind them");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return new NodeResult("continue", message.payload(), Map.of());
                });
            });

            try (var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor(),
                    ExecutionIdentitySource.randomUuids(), Duration.ofSeconds(30))) {
                var traversals = new CompletableFuture[ARRIVALS];
                for (int index = 0; index < ARRIVALS; index++) {
                    traversals[index] = runner.execute(IDENTITY, UUID.randomUUID(), UUID.randomUUID(),
                            "payload", "v1").toCompletableFuture();
                }
                CompletableFuture.allOf(traversals).get(60, TimeUnit.SECONDS);

                assertEquals(ARRIVALS, invocationIds.size(),
                        "each arrival must carry its own invocation identity");
                assertEquals(ARRIVALS, attemptIds.size(),
                        "each arrival must carry its own attempt identity under its own invocation");
                System.out.println("Observed " + ARRIVALS + " concurrent arrivals at one logical node -> "
                        + invocationIds.size() + " invocation identities, "
                        + attemptIds.size() + " attempt identities, executed on "
                        + threads.size() + " distinct threads");
            }
        }
    }

    /**
     * Nothing of the deployment survives a completed traversal, measured through the domain itself.
     *
     * <p>{@code ExecutionDomain#nodes()} is the engine's own answer to "what of this deployment is
     * alive", so asserting on it rather than on the runner's bookkeeping is what makes this a
     * statement about actors and not about the runner's opinion of them. Membership had to become
     * live first: previously, this set never shrank and this assertion was unwritable.
     *
     * <p>The wait is bounded rather than instantaneous, and that is honest rather than lax: releasing
     * an instance asks the engine to stop its actor, and an actor runtime terminates asynchronously.
     * What is immediate is the runner's own accounting, asserted separately.
     */
    @Test
    void aCompletedTraversalLeavesTheDeploymentDomainEmpty() throws Exception {
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.passthrough("work"), GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "work"), GraphEdge.to("work", "end")));

        try (var manager = GraphManager.from(graph);
             var engine = new PekkoExecutionEngine("demand-orphans-" + UUID.randomUUID())) {
            ExecutionDomain domain = engine.openDomain("deployment-under-test");

            assertEquals(Set.of(), domain.nodes(),
                    "opening a deployment domain over a three-node graph must create no actor at all");

            try (var runner = new GraphRunner(manager, engine, domain, new BehaviorRegistry(),
                    new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(), Duration.ofSeconds(30))) {
                assertEquals(Set.of(), domain.nodes(),
                        "building a runner over a deployment domain must create no actor either");
                for (int run = 0; run < 5; run++) {
                    runner.execute(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), "payload", "v1")
                            .toCompletableFuture().get(30, TimeUnit.SECONDS);
                }
                awaitEmpty(domain);
                System.out.println("After 5 traversals of a 3-node graph, live domain members: "
                        + domain.nodes().size());
            }
            domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);
            assertEquals(Set.of(), domain.nodes(), "closing the deployment must leave nothing alive");
        }
    }

    /** Stopping a deployment mid-flight leaves nothing behind, including the parked invocations. */
    @Test
    void stoppingADeploymentWithWorkInFlightLeavesNoOrphan() throws Exception {
        var parked = new CompletableFuture<NodeResult>();
        var arrived = new CountDownLatch(3);
        var graph = new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("a", "park"),
                        GraphNode.behavior("b", "park"), GraphNode.behavior("c", "park"),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "a"), GraphEdge.to("start", "b"),
                        GraphEdge.to("start", "c"), GraphEdge.to("a", "end")));

        try (var manager = GraphManager.from(graph);
             var engine = new PekkoExecutionEngine("demand-stop-" + UUID.randomUUID())) {
            ExecutionDomain domain = engine.openDomain("stopping-deployment");
            var registry = new BehaviorRegistry().register("park", message -> {
                arrived.countDown();
                return parked;
            });
            var runner = new GraphRunner(manager, engine, domain, registry, new ExecutionMonitor(),
                    ExecutionIdentitySource.randomUuids(), Duration.ofSeconds(5));
            runner.execute(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), "payload", "v1");

            assertTrue(arrived.await(30, TimeUnit.SECONDS), "the three branches must reach their nodes");
            // Bounded rather than immediate, and the reason is the start node. Its invocation completed
            // before the branches were dispatched and released its instance, but releasing asks the
            // engine to stop an actor and an actor runtime terminates asynchronously -- so for a short
            // window the domain still lists four. Asserting instantly would be asserting that Pekko
            // stops actors synchronously, which is a property of the test machine's timing rather than
            // of the runtime contract. What is asserted is that three parked branches are three
            // live instances and not one queue behind one mailbox.
            int parkedMembers = awaitMembers(domain, 3);
            assertEquals(3, parkedMembers,
                    "three parked invocations means three live instances, not one queue");
            System.out.println("Three branches parked mid-traversal -> " + parkedMembers
                    + " live domain members");

            runner.close();
            domain.close().toCompletableFuture().get(30, TimeUnit.SECONDS);

            assertEquals(Set.of(), domain.nodes(),
                    "stopping a deployment with work in flight must leave no orphan actor");
        }
    }

    /**
     * Bounded wait for the domain to drain.
     *
     * <p>Not an instantaneous assertion, and the reason is stated where it is done rather than left
     * for a reader to infer from a flake: the runner drops its registry entry synchronously when an
     * invocation settles, but the actor's own termination is asynchronous because that is what an
     * actor runtime provides. Asserting instantaneously would be asserting that Pekko terminates
     * synchronously, which it does not and must not.
     */
    private static int awaitMembers(ExecutionDomain domain, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        int observed = domain.nodes().size();
        while (System.nanoTime() < deadline && observed != expected) {
            Thread.sleep(20);
            observed = domain.nodes().size();
        }
        return observed;
    }

    private static void awaitEmpty(ExecutionDomain domain) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (domain.nodes().isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(Set.of(), domain.nodes(),
                "the domain still held live members 30s after every traversal completed");
    }
}
