package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyBinding;
import ai.ravenroot.api.deployment.RequestReplyContext;
import ai.ravenroot.api.deployment.RequestReplyExchange;
import ai.ravenroot.api.deployment.RequestReplyRefusal;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production wiring of the pre-dispatch payload projection seam.
 *
 * <p>What the coordinator's own tests cannot show is which <em>binding</em> each view supplies: the
 * deployment-wide view belongs to no node, a source view belongs to exactly the node whose context
 * handed it out, and a source view from a retired generation must not reach a projection at all.
 * Those are properties of {@link DefaultGraphDeployment}'s composition, so they are pinned here.</p>
 */
class DefaultGraphDeploymentProjectedRequestReplyTest {

    private static final SecurityContext IDENTITY = new SecurityContext("projection-request",
            "tenant-projected", "trusted-source", PrincipalType.WORKLOAD, "urn:ravenroot:test:projection");

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <graph id="projected-request-reply" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="listener">
                  <data key="kind">behavior</data>
                  <data key="behavior">test.projection.listener</data>
                </node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="listener"/>
                <edge source="listener" target="end"/>
              </graph>
            </graphml>
            """;

    @Test
    void aSourceViewProjectsUnderItsOwnNodeAndGenerationBindingAndTheProjectedPayloadIsTraversed()
            throws Exception {
        var listener = new ListenerBehavior();
        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = deployment(engine, listener);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var seen = new AtomicReference<RequestReplyContext>();
            Instant deadline = Instant.now().plusSeconds(5);
            RequestReplyExchange exchange = accepted(listener.contexts.get(0).requestReply()
                    .requestProjected(IngressTarget.start(), context -> {
                        seen.set(context);
                        return PayloadValue.of(context.traversalId().toString());
                    }, deadline));
            var outcome = exchange.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(RequestReplyTerminalState.COMPLETED, outcome.state(), outcome::toString);
            assertEquals(exchange.traversalId().toString(), outcome.payload(),
                    "the traversal must carry the payload the projection built from issued identity");
            assertEquals(new RequestReplyBinding("tenant-projected", "projected", 1L, Optional.of("listener")),
                    seen.get().binding());
            assertEquals(exchange.correlationId(), seen.get().correlationId());
            assertEquals(exchange.processInstanceId(), seen.get().processInstanceId());
            assertEquals(exchange.traversalId(), seen.get().traversalId());
            assertEquals(deadline, seen.get().deadline());
            assertEquals(exchange.deadline(), seen.get().deadline());

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void theDeploymentWideViewBindsToNoNode() throws Exception {
        var listener = new ListenerBehavior();
        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = deployment(engine, listener);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var seen = new AtomicReference<RequestReplyContext>();
            RequestReplyExchange exchange = accepted(deployment.requestReply()
                    .requestProjected(IngressTarget.start(), context -> {
                        seen.set(context);
                        return PayloadValue.of("composition-root");
                    }, Instant.now().plusSeconds(5)));
            exchange.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(new RequestReplyBinding("tenant-projected", "projected", 1L, Optional.empty()),
                    seen.get().binding(), "the composition-root view is owned by the deployment, not a node");

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Generation isolation reaches the projected path too. A source that kept its old context across a
     * restart is refused before anything runs — the projection is not merely ignored, it is never
     * invoked, so it cannot observe the replacement generation's identity either.
     */
    @Test
    void aSourceViewFromARetiredGenerationIsRefusedAndItsProjectionNeverRuns() throws Exception {
        var listener = new ListenerBehavior();
        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = deployment(engine, listener);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            InboundSourceContext retired = listener.contexts.get(0);

            deployment.restart(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(2, listener.contexts.size(), "restart must hand the source a fresh context");

            var ran = new AtomicInteger();
            var refusal = assertInstanceOf(RequestReplyAdmission.Refused.class,
                    retired.requestReply().requestProjected(IngressTarget.start(), context -> {
                        ran.incrementAndGet();
                        return PayloadValue.of("stale");
                    }, Instant.now().plusSeconds(5)));

            assertEquals(RequestReplyRefusal.ADMISSION_CLOSED, refusal.reason());
            assertEquals(0, ran.get(), "a retired generation must not run caller code");

            var current = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                    listener.contexts.get(1).requestReply().requestProjected(IngressTarget.start(),
                            context -> PayloadValue.of(String.valueOf(context.binding().generation())),
                            Instant.now().plusSeconds(5)));
            assertEquals("2", current.exchange().completion().toCompletableFuture()
                    .get(10, TimeUnit.SECONDS).payload());

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aFailingProjectionAdmitsNoTraversalAndLeavesTheDeploymentAbleToAdmitTheNextOne()
            throws Exception {
        var listener = new ListenerBehavior();
        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = deployment(engine, listener);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var refusal = assertInstanceOf(RequestReplyAdmission.Refused.class,
                    deployment.requestReply().requestProjected(IngressTarget.start(), context -> {
                        throw PayloadException.tooLarge(64, 16);
                    }, Instant.now().plusSeconds(5)));
            assertEquals(RequestReplyRefusal.PAYLOAD_REJECTED, refusal.reason());
            assertEquals(0, listener.invocations.get(), "a refused projection starts no traversal");

            RequestReplyExchange next = accepted(deployment.requestReply().request(IngressTarget.start(),
                    PayloadValue.of("after-refusal"), Instant.now().plusSeconds(5)));
            assertEquals("after-refusal",
                    next.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).payload());
            assertEquals(1, listener.invocations.get());

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void aStoppedDeploymentRefusesTheProjectedPathWithoutRunningTheProjection() throws Exception {
        var listener = new ListenerBehavior();
        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = deployment(engine, listener);
            deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);

            var ran = new AtomicInteger();
            var refusal = assertInstanceOf(RequestReplyAdmission.Refused.class,
                    deployment.requestReply().requestProjected(IngressTarget.start(), context -> {
                        ran.incrementAndGet();
                        return PayloadValue.of("late");
                    }, Instant.now().plusSeconds(5)));

            assertEquals(RequestReplyRefusal.ADMISSION_CLOSED, refusal.reason());
            assertEquals(0, ran.get());
            assertFalse(listener.contexts.isEmpty());
            assertTrue(listener.invocations.get() == 0);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static RequestReplyExchange accepted(RequestReplyAdmission admission) {
        return assertInstanceOf(RequestReplyAdmission.Accepted.class, admission).exchange();
    }

    private static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine,
                                                     ListenerBehavior listener) {
        NodePackage nodePackage = new NodePackage() {
            @Override
            public String id() {
                return "test.projection.package";
            }

            @Override
            public String version() {
                return "1.0.0";
            }

            @Override
            public String sdkContract() {
                return NodeSdk.CONTRACT;
            }

            @Override
            public List<NodeBehavior> behaviors() {
                return List.of(listener);
            }
        };
        return new DefaultGraphDeployment(DeploymentId.of("projected"), engine,
                NodePackages.register(new BehaviorRegistry(), nodePackage), new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY);
    }

    /** Source-capable so the deployment builds a generation-fenced {@link InboundSourceContext}. */
    private static final class ListenerBehavior implements NodeBehavior, InboundSourceCapable {
        private final CopyOnWriteArrayList<InboundSourceContext> contexts = new CopyOnWriteArrayList<>();
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.projection.listener", "Projection listener", "Test",
                    "A source-capable behavior that echoes whatever payload reaches it.", "actor", false,
                    List.of(), Set.of(), null, Set.of());
        }

        @Override
        public NodeAction create(NodeConfiguration configuration) {
            return message -> {
                invocations.incrementAndGet();
                return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
            };
        }

        @Override
        public InboundSource createSource(NodeConfiguration configuration, InboundSourceContext context) {
            contexts.add(context);
            return new InboundSource() {
                @Override
                public CompletionStage<Void> start(InboundSourceContext started) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<Void> stop() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }
    }
}
