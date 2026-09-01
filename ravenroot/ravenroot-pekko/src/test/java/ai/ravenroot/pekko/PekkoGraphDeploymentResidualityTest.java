package ai.ravenroot.pekko;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.execution.EngineCapability;
import ai.ravenroot.api.execution.EngineState;
import ai.ravenroot.api.execution.ExecutionDomain;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.execution.NodeStatus;
import ai.ravenroot.api.execution.RavenNode;
import ai.ravenroot.api.execution.Scheduler;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.Terminated;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two requirements the conformance suite structurally cannot police: "zero residual
 * resources" and "restart without duplicated subscriptions". Both pass trivially against the
 * engine-neutral {@code TrackedExecutionDomain} fallback -- see {@code GraphDeploymentContract}'s own
 * Javadoc and the S4 finding it is built around -- so neither lives there. Both belong here because
 * both need a fact only the real Pekko runtime can produce: whether the guardian actor a domain opened
 * has genuinely terminated, which {@link ExecutionDomain}'s contract has no vocabulary to express (it
 * promises a {@code CompletionStage<Void>} settles, never that any particular actor died).
 *
 * <h2>Why DeathWatch, not the resolved {@code CompletionStage}</h2>
 * <p>{@code domain.close()} resolving is not proof of anything by itself -- a bookkeeping domain's
 * close resolves while real actors stay alive precisely because it has none to begin with, and even a
 * native domain's close could in principle resolve slightly ahead of the guardian's own termination
 * signal reaching the actor system. Watching the guardian directly and waiting for Pekko's own
 * {@link Terminated} signal is the one thing that cannot lie about whether the subtree is actually
 * gone: Pekko does not deliver a parent's {@code Terminated} to a watcher until every child beneath it
 * has already stopped, so one watch on the guardian is proof for the whole subtree, not only its root.
 */
class PekkoGraphDeploymentResidualityTest {
    private static final SecurityContext IDENTITY = new SecurityContext("residuality-request",
            "residuality-tenant", "residuality-subject", PrincipalType.WORKLOAD, "urn:ravenroot:residuality");

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

    private PekkoExecutionEngine pekko;

    @AfterEach
    void close() {
        if (pekko != null) {
            pekko.close();
        }
    }

    /**
     * The blind requirement. A bookkeeping domain can truthfully report zero residual nodes on its own
     * ledger while the actors it never had anything to do with keep running -- there are none to leak,
     * which is exactly the gap. Here there is a real subtree, and this proves it is really gone.
     */
    @Test
    void stoppingADeploymentReallyTerminatesTheGuardianActor() throws Exception {
        pekko = new PekkoExecutionEngine("zero-residual-" + UUID.randomUUID());
        var recording = new DomainRecordingEngine(pekko);
        var deployment = deployment(recording, "zero-residual");

        deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        var guardian = ((PekkoExecutionEngine.SubtreeDomain) recording.opened().get(0)).guardianRef();
        var terminated = watch(guardian);

        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

        // Bounded: a subtree that is not really gone must fail this test rather than hang it forever.
        terminated.get(10, TimeUnit.SECONDS);
    }

    /**
     * "Restart without duplicated subscriptions", made concrete: every earlier generation's guardian
     * must be genuinely dead, not merely superseded in a Java field. A restart that ever raced -- a new
     * domain opened before the old one's guardian had actually died -- would leave two generations'
     * worth of live actors after this loop, which is exactly what this counts against. Driven through
     * {@link DefaultGraphDeployment#restart} itself, the real production sequencing, not a hand-rolled
     * imitation of it.
     */
    @Test
    void restartLeavesNoPriorGenerationOfActorsAlive() throws Exception {
        pekko = new PekkoExecutionEngine("no-duplication-" + UUID.randomUUID());
        var recording = new DomainRecordingEngine(pekko);
        var deployment = deployment(recording, "recurring");
        int restarts = 4;

        deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        for (int i = 0; i < restarts; i++) {
            deployment.restart(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

        var opened = recording.opened();
        // One generation per start plus one per restart: nothing here forces that count on its own
        // (that is GraphDeploymentContract's job) -- it is asserted only so the loop below is known to
        // be checking every generation this run actually produced, not a subset.
        assertEquals(1 + restarts, opened.size());

        var watches = new ArrayList<CompletableFuture<Void>>();
        for (ExecutionDomain domain : opened) {
            watches.add(watch(((PekkoExecutionEngine.SubtreeDomain) domain).guardianRef()));
        }
        for (var watch : watches) {
            watch.get(10, TimeUnit.SECONDS);
        }
    }

    private DefaultGraphDeployment deployment(ExecutionEngine engine, String id) {
        return new DefaultGraphDeployment(DeploymentId.of(id), engine,
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
    }

    /** Watches {@code target} and completes the returned future the moment Pekko reports it gone. */
    private CompletableFuture<Void> watch(ActorRef<?> target) {
        var terminated = new CompletableFuture<Void>();
        pekko.actorSystem().systemActorOf(watcher(target, terminated), "watch-" + UUID.randomUUID(), Props.empty());
        return terminated;
    }

    private static Behavior<Void> watcher(ActorRef<?> target, CompletableFuture<Void> terminated) {
        return Behaviors.setup(context -> {
            context.watch(target);
            return Behaviors.receiveSignal((ctx, signal) -> {
                if (signal instanceof Terminated) {
                    terminated.complete(null);
                    return Behaviors.stopped();
                }
                return Behaviors.same();
            });
        });
    }

    /**
     * Delegates every {@link ExecutionEngine} call to a real {@link PekkoExecutionEngine} and records
     * every {@link ExecutionDomain} {@code openDomain} hands back, in call order. A plain decorator --
     * the recording is the only thing it adds -- so {@link DefaultGraphDeployment} exercises exactly
     * the production adapter while this test keeps the handle it needs to watch each generation.
     */
    private static final class DomainRecordingEngine implements ExecutionEngine {
        private final PekkoExecutionEngine delegate;
        private final List<ExecutionDomain> opened = Collections.synchronizedList(new ArrayList<>());

        private DomainRecordingEngine(PekkoExecutionEngine delegate) {
            this.delegate = delegate;
        }

        List<ExecutionDomain> opened() {
            return List.copyOf(opened);
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public Set<EngineCapability> capabilities() {
            return delegate.capabilities();
        }

        @Override
        public Scheduler scheduler() {
            return delegate.scheduler();
        }

        @Override
        public EngineState state() {
            return delegate.state();
        }

        @Override
        public NodeRef spawn(String logicalName, RavenNode node) {
            return delegate.spawn(logicalName, node);
        }

        @Override
        public CompletionStage<NodeResult> send(NodeRef target, NodeMessage message) {
            return delegate.send(target, message);
        }

        @Override
        public Optional<NodeStatus> status(NodeRef target) {
            return delegate.status(target);
        }

        @Override
        public ExecutionDomain openDomain(String domainName) {
            ExecutionDomain domain = delegate.openDomain(domainName);
            opened.add(domain);
            return domain;
        }

        @Override
        public CompletionStage<Void> stop(NodeRef target) {
            return delegate.stop(target);
        }

        @Override
        public CompletionStage<Void> cancel(NodeRef target) {
            return delegate.cancel(target);
        }

        @Override
        public CompletionStage<Void> drain() {
            return delegate.drain();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
