package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.GraphAdmissionPurpose;
import ai.ravenroot.api.application.GraphAdmissionReason;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CoreScheduledSourceDeploymentTest {
    @TempDir Path directory;
    private static final SecurityContext IDENTITY = new SecurityContext("request", "tenant", "timer",
            PrincipalType.WORKLOAD, "test");
    private static final DeploymentId DEPLOYMENT = DeploymentId.of("scheduled-deployment");
    private static final String GRAPH = """
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="zone" for="node" attr.name="zoneId" attr.type="string"/>
              <key id="times" for="node" attr.name="times" attr.type="string"/>
              <key id="mode" for="node" attr.name="mode" attr.type="string"/>
              <graph id="scheduled" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="timer"><data key="kind">behavior</data><data key="behavior">timer</data>
                  <data key="zone">UTC</data><data key="times">23:59:59</data><data key="mode">RECURRING</data>
                </node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="timer"/><edge source="timer" target="end"/>
              </graph>
            </graphml>
            """;

    @Test void catalogPublishesCoreSourcesAndSharedStoreAllowsOneOwnerPerDeploymentSchedule() throws Exception {
        var registry = BehaviorRegistry.standard();
        assertEquals(NodeRuntimeNature.SOURCE, registry.descriptor("timer").orElseThrow().effectiveDefaultNature());
        assertEquals(NodeRuntimeNature.SOURCE, registry.descriptor("crontab").orElseThrow().effectiveDefaultNature());
        assertTrue(registry.inboundSourceFactory("timer").isPresent());

        Path database = directory.resolve("schedules.db");
        try (var firstStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var secondStore = new SqliteExecutionStore(database, Clock.systemUTC());
             var firstEngine = new SameThreadExecutionEngine();
             var secondEngine = new SameThreadExecutionEngine()) {
            var first = deployment(firstEngine, firstStore);
            var second = deployment(secondEngine, secondStore);
            first.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThrows(Exception.class, () -> second.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS),
                    "the stable schedule namespace must have one active owner");
            first.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            second.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS);
            second.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test void invalidCalendarIsRefusedBeforeDeploymentWithPropertyAddress() {
        var validator = new GraphAdmissionValidator(BehaviorRegistry.standard(), GraphExecutionLimits.DEFAULTS);
        var finding = validator.inspect(GRAPH.replace("23:59:59", "25:00")
                .getBytes(StandardCharsets.UTF_8), GraphAdmissionPurpose.SOURCE_SESSION)
                .findings().getFirst();
        assertEquals(GraphAdmissionReason.INVALID_PROPERTY, finding.reason());
        assertEquals("timer", finding.nodeId());
        assertEquals("times", finding.propertyName());
    }

    private static DefaultGraphDeployment deployment(SameThreadExecutionEngine engine, SqliteExecutionStore store) {
        return new DefaultGraphDeployment(DEPLOYMENT, engine, BehaviorRegistry.standard(), new ExecutionMonitor(),
                ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION);
    }
}
