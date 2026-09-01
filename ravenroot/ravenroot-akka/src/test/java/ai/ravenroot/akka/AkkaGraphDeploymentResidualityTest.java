package ai.ravenroot.akka;

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
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Props;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.Behaviors;
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
 * Mechanical mirror of {@code PekkoGraphDeploymentResidualityTest}. Unverifiable in this
 * environment: {@code ravenroot-akka} has never been compiled here, its BSL artifact has never
 * resolved, as declared in {@link AkkaExecutionEngine}'s own Javadoc. Verified on Pekko; mirrored
 * here rather than skipped so the suite is complete the moment that artifact does resolve somewhere.
 * See the Pekko original for the full reasoning this file deliberately does not repeat.
 */
class AkkaGraphDeploymentResidualityTest {
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

    private AkkaExecutionEngine akka;

    @AfterEach
    void close() {
        if (akka != null) {
            akka.close();
        }
    }

    @Test
    void stoppingADeploymentReallyTerminatesTheGuardianActor() throws Exception {
        akka = new AkkaExecutionEngine("zero-residual-" + UUID.randomUUID());
        var recording = new DomainRecordingEngine(akka);
        var deployment = deployment(recording, "zero-residual");

        deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        var guardian = ((AkkaExecutionEngine.SubtreeDomain) recording.opened().get(0)).guardianRef();
        var terminated = watch(guardian);

        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

        terminated.get(10, TimeUnit.SECONDS);
    }

    @Test
    void restartLeavesNoPriorGenerationOfActorsAlive() throws Exception {
        akka = new AkkaExecutionEngine("no-duplication-" + UUID.randomUUID());
        var recording = new DomainRecordingEngine(akka);
        var deployment = deployment(recording, "recurring");
        int restarts = 4;

        deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        for (int i = 0; i < restarts; i++) {
            deployment.restart(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

        var opened = recording.opened();
        assertEquals(1 + restarts, opened.size());

        var watches = new ArrayList<CompletableFuture<Void>>();
        for (ExecutionDomain domain : opened) {
            watches.add(watch(((AkkaExecutionEngine.SubtreeDomain) domain).guardianRef()));
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

    private CompletableFuture<Void> watch(ActorRef<?> target) {
        var terminated = new CompletableFuture<Void>();
        akka.actorSystem().systemActorOf(watcher(target, terminated), "watch-" + UUID.randomUUID(), Props.empty());
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

    /** Mechanical mirror of the Pekko test's own {@code DomainRecordingEngine}. */
    private static final class DomainRecordingEngine implements ExecutionEngine {
        private final AkkaExecutionEngine delegate;
        private final List<ExecutionDomain> opened = Collections.synchronizedList(new ArrayList<>());

        private DomainRecordingEngine(AkkaExecutionEngine delegate) {
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
