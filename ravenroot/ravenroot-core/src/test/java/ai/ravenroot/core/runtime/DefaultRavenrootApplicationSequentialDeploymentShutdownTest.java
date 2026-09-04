package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeContext;
import ai.ravenroot.api.execution.NodeLifecycleState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.ScheduledTask;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pod-level counterpart to {@code ExecutionEngineContract.closesDomainsConcurrentlyRatherThanInSeries()}.
 *
 * <p>That test proves an engine's own {@code close()} does not delay one domain closing behind another
 * -- an engine-level guarantee. This test proves, or disproves, that a pod's actual shutdown path
 * exercises that guarantee across the deployments running on it. It does not exist to duplicate that
 * proof; it exists because the two can diverge silently, and once already did:
 * {@code DeploymentCapConfiguration}'s shutdown-budget formula previously assumed pod-level
 * concurrency the pod's own shutdown code never provides, and nothing caught it until an independent
 * review read {@code DefaultRavenrootApplication.close()} directly.
 *
 * <h2>What this asserts, and why it is a deliberate pin rather than an assumption</h2>
 * <p>Today, {@code DefaultRavenrootApplication.close()} iterates {@code deployments.values()}
 * sequentially and blocks on each deployment's own shutdown stage
 * ({@code ending.toCompletableFuture().get(30, SECONDS)}) before starting the next
 * (`ravenroot-core/src/main/java/ai/ravenroot/core/runtime/DefaultRavenrootApplication.java:2382-2392`),
 * so at most one deployment's domain is ever closing at a time. This test asserts exactly that --
 * {@code maxConcurrentDomainCloses() == 1} for three deployments closed together -- as the load-bearing
 * fact behind {@code DeploymentCapConfiguration}'s {@code M}-dependent shutdown-budget formula (see its
 * own Javadoc). It is a deliberate characterization, not an assumption smuggled in as an assertion: if a
 * future change makes deployment shutdown concurrent -- a legitimate improvement, since it would shrink
 * the pod's shutdown budget -- this assertion must be updated in the SAME change as
 * {@code DeploymentCapConfiguration}'s formula. If this test starts failing on its own, that is the
 * engine-level property and the pod-level claim diverging again, and the formula needs to be revisited
 * before the failure is waived away.</p>
 *
 * <h2>Deterministic, not a timing comparison</h2>
 * <p>Detected via a shared counter of domain closes currently in flight, not by comparing elapsed time
 * between runs. Each fake domain's {@code close()} holds itself open for a short fixed window purely so
 * that a genuine overlap -- if the shutdown path were ever changed to be concurrent -- would be observed
 * rather than finish before the counter is read; the window is a fixed constant applied identically to
 * every close, never a duration compared against another duration.</p>
 */
class DefaultRavenrootApplicationSequentialDeploymentShutdownTest {

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="wiring" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @Test
    void closesDeploymentsSequentiallyNotConcurrently() throws Exception {
        int deployments = 3;
        var engine = new DomainCloseOrderingProbeEngine();
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                new InMemoryArtifactRegistry(), new DisabledProgramRuntime(),
                ExecutionIdentitySource.randomUuids(), null, deployments);
        try {
            for (int i = 0; i < deployments; i++) {
                application.activateDeployment(TestIdentities.TENANT_A, DeploymentId.of("dep-" + i), graphStream())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
            }

            application.close();

            assertEquals(1, engine.maxConcurrentDomainCloses(),
                    "DefaultRavenrootApplication.close() closed more than one deployment's domain "
                            + "concurrently -- the pod-level assumption DeploymentCapConfiguration's "
                            + "shutdown-budget formula is built on no longer holds as measured here. If "
                            + "this is a deliberate improvement, update that formula's M-dependent term "
                            + "(and this assertion) in the same change; do not waive this failure alone.");
        } finally {
            engine.close();
        }
    }

    private static ByteArrayInputStream graphStream() {
        return new ByteArrayInputStream(GRAPH.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A minimal {@link ExecutionEngine} whose {@link #openDomain(String)} returns a domain that
     * measures how many of its own kind are closing at once, engine-wide -- the pod-level analogue of
     * {@code ExecutionEngineContract}'s {@code blockingOnStop}, but at the domain-close boundary
     * directly rather than through a node's {@code onStop}, because this engine (unlike the real
     * adapters) never invokes node lifecycle hooks at all -- it exists only to observe ordering.
     */
    private static final class DomainCloseOrderingProbeEngine implements ExecutionEngine {
        private final Map<NodeRef, RavenNode> nodes = new ConcurrentHashMap<>();
        private final AtomicInteger domainsCurrentlyClosing = new AtomicInteger();
        private final AtomicInteger maxConcurrentDomainCloses = new AtomicInteger();
        private volatile EngineState state = EngineState.RUNNING;

        int maxConcurrentDomainCloses() {
            return maxConcurrentDomainCloses.get();
        }

        @Override
        public String id() {
            return "domain-close-ordering-probe";
        }

        @Override
        public Set<EngineCapability> capabilities() {
            return Set.of();
        }

        @Override
        public Scheduler scheduler() {
            return (delay, task) -> (ScheduledTask) () -> false;
        }

        @Override
        public EngineState state() {
            return state;
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            var ref = new NodeRef(logicalName + "-" + UUID.randomUUID());
            nodes.put(ref, node);
            return ref;
        }

        @Override
        public ExecutionDomain openDomain(String domainName) {
            if (!state().accepting()) {
                throw new IllegalStateException("Execution engine is " + state());
            }
            return new ExecutionDomain() {
                private final Set<NodeRef> members = ConcurrentHashMap.newKeySet();

                @Override
                public String name() {
                    return domainName;
                }

                @Override
                public NodeRef spawn(String logicalName, RavenNode node) {
                    NodeRef ref = DomainCloseOrderingProbeEngine.this.spawn(logicalName, node);
                    members.add(ref);
                    return ref;
                }

                @Override
                public Set<NodeRef> nodes() {
                    return Set.copyOf(members);
                }

                @Override
                public CompletionStage<Void> close() {
                    int current = domainsCurrentlyClosing.incrementAndGet();
                    maxConcurrentDomainCloses.updateAndGet(max -> Math.max(max, current));
                    try {
                        // Fixed window, identical for every close: widens the chance a genuine
                        // overlap is observed by the counter above rather than finishing before it is
                        // read. Not a duration compared against another duration.
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    members.forEach(nodes::remove);
                    domainsCurrentlyClosing.decrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            RavenNode node = nodes.get(target);
            if (node == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown node " + target.value()));
            }
            return node.onMessage(message, context(target));
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return Optional.ofNullable(nodes.get(target))
                    .map(ignored -> new NodeStatus(target, NodeLifecycleState.RUNNING, null, 0));
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            nodes.remove(target);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return stop(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            state = EngineState.DRAINING;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            state = EngineState.CLOSED;
            nodes.clear();
        }

        private NodeContext context(NodeRef ref) {
            return new NodeContext() {
                @Override
                public NodeRef self() {
                    return ref;
                }

                @Override
                public Scheduler scheduler() {
                    return DomainCloseOrderingProbeEngine.this.scheduler();
                }

                @Override
                public ai.ravenroot.api.execution.Mailbox mailbox() {
                    return () -> 0;
                }

                @Override
                public ai.ravenroot.api.execution.CancellationSignal cancellation() {
                    return StubEngineLifecycle.NEVER_CANCELLED;
                }
            };
        }
    }
}
