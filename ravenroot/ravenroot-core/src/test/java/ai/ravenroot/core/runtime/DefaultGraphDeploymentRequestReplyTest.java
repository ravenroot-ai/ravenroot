package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.RequestReplyAdmission;
import ai.ravenroot.api.deployment.RequestReplyTerminalState;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Production deployment wiring for the engine-neutral request/reply coordinator. */
class DefaultGraphDeploymentRequestReplyTest {
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="request-reply" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="capture">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">capture-request-reply</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-capture" source="start" target="capture">
                  <data key="edge-outcome">continue</data>
                </edge>
                <edge id="capture-end" source="capture" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    private static final String FAILURE_ROUTE_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="node-behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="join-quorum" for="node" attr.name="joinQuorum" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <key id="failure-route" for="edge" attr.name="failure.route" attr.type="string"/>
              <graph id="request-reply-failure" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="boom">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">request-reply-boom</data>
                </node>
                <node id="ordinary">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">request-reply-ordinary</data>
                </node>
                <node id="handler">
                  <data key="node-kind">BEHAVIOR</data>
                  <data key="node-behavior">request-reply-handler</data>
                </node>
                <node id="rejoin">
                  <data key="node-kind">PASSTHROUGH</data>
                  <data key="join-quorum">1</data>
                </node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-boom" source="start" target="boom">
                  <data key="edge-outcome">continue</data>
                </edge>
                <edge id="normal" source="boom" target="ordinary">
                  <data key="edge-outcome">continue</data>
                </edge>
                <edge id="failure" source="boom" target="handler">
                  <data key="failure-route">true</data>
                </edge>
                <edge id="ordinary-rejoin" source="ordinary" target="rejoin">
                  <data key="edge-outcome">continue</data>
                </edge>
                <edge id="handler-rejoin" source="handler" target="rejoin">
                  <data key="edge-outcome">continue</data>
                </edge>
                <edge id="rejoin-end" source="rejoin" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @Test
    void establishedDeploymentIdentityAndIssuedIdsReachEveryTraversalStructurally() throws Exception {
        var observed = new AtomicReference<NodeMessage>();
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("capture-request-reply", message -> {
                    observed.set(message);
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
        var identity = new SecurityContext("request-reply-start", "tenant-a", "trusted-source",
                PrincipalType.WORKLOAD, "urn:ravenroot:test:request-reply");

        try (var engine = new SameThreadExecutionEngine()) {
            var deployment = new DefaultGraphDeployment(DeploymentId.of("request-reply"), engine, registry,
                    new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    GRAPH.getBytes(StandardCharsets.UTF_8), 4);
            deployment.start(identity).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var accepted = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                    deployment.requestReply().request(IngressTarget.start(), PayloadValue.of("payload"),
                            Instant.now().plusSeconds(5)));
            var exchange = accepted.exchange();
            var outcome = exchange.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            NodeMessage delivered = observed.get();
            assertSame(identity, delivered.security(),
                    "request/reply must use the identity established by deployment start, never caller input");
            assertEquals("tenant-a", delivered.tenantId());
            assertEquals(exchange.processInstanceId(), delivered.processInstanceId());
            assertEquals(exchange.traversalId(), delivered.traversalId());
            assertEquals(RequestReplyTerminalState.COMPLETED, outcome.state(), outcome::toString);
            assertEquals("payload", outcome.payload());
            assertEquals(exchange.processInstanceId(), outcome.processInstanceId());
            assertEquals(exchange.traversalId(), outcome.traversalId());

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void handledFailureRouteProducesOneBoundedSuccessfulReplyWithFailureEvidence() throws Exception {
        var registry = BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("request-reply-boom", message -> CompletableFuture.failedFuture(
                        new IllegalStateException("secret raw failure detail")))
                .register("request-reply-ordinary", message -> CompletableFuture.failedFuture(
                        new AssertionError("the ordinary route must not run")))
                .register("request-reply-handler", message -> CompletableFuture.completedFuture(
                        NodeResult.continueWith("handled")));
        var identity = new SecurityContext("request-reply-failure", "tenant-a", "trusted-source",
                PrincipalType.WORKLOAD, "urn:ravenroot:test:request-reply");

        try (var engine = new JoinTestEngine()) {
            var deployment = new DefaultGraphDeployment(DeploymentId.of("request-reply-failure"), engine,
                    registry, new ExecutionMonitor(), ExecutionIdentitySource.randomUuids(),
                    FAILURE_ROUTE_GRAPH.getBytes(StandardCharsets.UTF_8), 2);
            deployment.start(identity).toCompletableFuture().get(10, TimeUnit.SECONDS);

            var exchange = assertInstanceOf(RequestReplyAdmission.Accepted.class,
                    deployment.requestReply().request(IngressTarget.start(), PayloadValue.of("payload"),
                            Instant.now().plusSeconds(5))).exchange();
            var outcome = exchange.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(RequestReplyTerminalState.COMPLETED, outcome.state(), outcome::toString);
            assertEquals("handled", outcome.payload());
            assertEquals(Set.of("boom"), outcome.executionOutcome().orElseThrow().handledFailureNodes());

            deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
