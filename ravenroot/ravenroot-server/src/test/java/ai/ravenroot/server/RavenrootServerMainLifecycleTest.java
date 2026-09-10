package ai.ravenroot.server;

import ai.ravenroot.persistence.sqlite.SqliteStoreLocation;
import ai.ravenroot.server.persistence.ExecutionStoreBootstrap;
import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RavenrootServerMainLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void oneResolvedExecutionRuntimeReachesEveryServerExecutionConsumer() throws Exception {
        var environment = Map.of(
                ai.ravenroot.core.runtime.ExecutionRuntimeConfiguration
                        .MAX_STASHED_COMMANDS_PER_NODE_VARIABLE, "7",
                ai.ravenroot.core.runtime.ExecutionRuntimeConfiguration
                        .LIFECYCLE_STEP_SECONDS_VARIABLE, "2",
                ai.ravenroot.core.runtime.ExecutionRuntimeConfiguration
                        .TERMINAL_HISTORY_CAPACITY_VARIABLE, "9",
                ai.ravenroot.core.runtime.ExecutionRuntimeConfiguration
                        .RUNNER_SHUTDOWN_STEP_SECONDS_VARIABLE, "1");
        var runtime = RavenrootServerMain.ResolvedExecutionRuntime.fromEnvironment(environment);
        var capturedPolicy = new AtomicReference<ai.ravenroot.api.execution.ExecutionEnginePolicy>();

        String created = runtime.createEngine("PeKkO", "server-policy-probe", (id, name, policy) -> {
            assertEquals("PeKkO", id);
            assertEquals("server-policy-probe", name);
            capturedPolicy.set(policy);
            return "created";
        });

        assertEquals("created", created);
        assertEquals(7, capturedPolicy.get().maxStashedCommandsPerNode());
        assertEquals(Duration.ofSeconds(2), capturedPolicy.get().lifecycleStepBound());
        assertEquals(9, capturedPolicy.get().terminalNodeHistoryCapacity());
        Duration applicationBound = runtime.applicationRunnerShutdownStepBound();
        assertEquals(Duration.ofSeconds(1), applicationBound);
        assertSame(applicationBound, runtime.toolApprovalRunnerShutdownStepBound());
        assertSame(applicationBound, runtime.humanTaskRunnerShutdownStepBound());

        String source = Files.readString(Path.of(
                "src/main/java/ai/ravenroot/server/RavenrootServerMain.java"));
        assertEquals(1, source.split(
                "ResolvedExecutionRuntime\\.fromEnvironment\\(System\\.getenv\\(\\)\\)", -1).length - 1,
                "the server must resolve the engine/runner tuple exactly once");
        assertTrue(source.indexOf("ResolvedExecutionRuntime.fromEnvironment(System.getenv())")
                        < source.indexOf("ExecutionStoreBootstrap.openOwned("),
                "runtime bounds must refuse invalid startup before a durable store opens");
        String compact = source.replaceAll("\\s+", " ");
        assertTrue(compact.contains("executionRuntime.createEngine(engineId, \"ravenroot-server\", "
                        + "ExecutionEngines::create)"),
                "the actual server engine site must use the resolved policy");
        assertTrue(compact.contains("executionStoreOwner.executionManifestStore(), "
                        + "executionRuntime.applicationRunnerShutdownStepBound(), "
                        + "executionOwnershipConfiguration.runtimeOwnership())"),
                "the application site must use its named projection, and must be handed this "
                        + "replica's own runtime identity rather than letting core mint one");
        assertTrue(source.indexOf("refuseUnsupportableReplicaTopology(System.getenv(), "
                        + "executionStoreConfiguration)")
                        < source.indexOf("ExecutionStoreBootstrap.openOwned("),
                "an unsupportable replica topology must be refused before a durable store opens");
        assertTrue(compact.contains("String recoveryWorker = executionOwnershipConfiguration"
                        + ".recoveryIdentity().value()"),
                "the recovery sweep must take the recovery role of this replica's identity; sharing "
                        + "the runtime's would let it claim work its own runtime is advancing and "
                        + "keep the fencing token under that runtime's recorder");
        assertTrue(compact.contains("executionManifests, "
                        + "executionRuntime.toolApprovalRunnerShutdownStepBound())"),
                "tool recovery must use its named projection");
        assertTrue(compact.contains("executionManifests, "
                        + "executionRuntime.humanTaskRunnerShutdownStepBound())"),
                "human-task recovery must use its named projection");
    }

    @Test
    void pluginRefusalClosesAuditAndCheckpointsStoreBeforeExitStrategyRuns() throws Exception {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("store"));
        var configuration = new ExecutionStoreConfiguration.SingleHost(location);
        var owner = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC());
        owner.store().forgottenBefore("tenant-a").toCompletableFuture().join();
        var order = new ArrayList<String>();

        RavenrootServerMain.launch(() -> {
            try (var startupGuard = owner.startupGuard();
                 var auditOwner = new RecordingOwner(order)) {
                throw new RavenrootServerMain.PluginStartupRefused();
            }
        }, status -> {
            order.add("exit:" + status);
            // Reopening inside the exit strategy proves both checkpoint/close and lock release
            // completed before the composition root asks the process to terminate.
            try (var reopened = ExecutionStoreBootstrap.openOwned(configuration, Clock.systemUTC())) {
                assertEquals(java.time.Instant.MIN,
                        reopened.store().forgottenBefore("tenant-a").toCompletableFuture().join());
            }
        });

        owner.close();
        assertEquals(java.util.List.of("audit", "exit:1"), order);
        assertTrue(!Files.exists(location.walFile()) || Files.size(location.walFile()) == 0);
    }

    /**
     * With no operator authority configured, the packaged process refuses
     * before the listener binds, and still says {@code EMBED_OPERATOR_AUTHORITY_UNAVAILABLE}.
     *
     * <p>The refusal is conditional on the embed being enabled, so it answers «the
     * embed is on and nothing says where its durable authority lives», and the detail names the
     * variable to set.</p>
     */
    @Test
    void packagedEmbedWithoutAConfiguredAuthorityRefusesBeforeBind() throws Exception {
        var bound = new AtomicBoolean();
        var exitStatus = new AtomicInteger(-1);
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var captured = new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(captured);
            RavenrootServerMain.launch(() -> {
                RavenrootServerMain.refuseUnsupportablePackagedEmbed(
                        Map.of("RAVENROOT_EMBED_ENABLED", "true"));
                bound.set(true);
            }, exitStatus::set);
        } finally {
            System.setErr(previous);
        }
        assertEquals(1, exitStatus.get());
        assertEquals(false, bound.get());
        assertEquals("{\"event\":\"startup_refused\","
                        + "\"code\":\"EMBED_OPERATOR_AUTHORITY_UNAVAILABLE\","
                        + "\"detail\":\"packaged embed requires a durable operator provision-revoke "
                        + "authority; set RAVENROOT_EMBED_REGISTRATION_DIR\"}"
                        + System.lineSeparator(),
                output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * A supportable configuration must reach the bind. Without this, every other assertion in this
     * class is satisfied by a process that refuses everything; reaching the bind proves the
     * supportable configuration is admitted.
     */
    @Test
    void packagedEmbedWithADurableAuthorityAndOneReplicaProceedsToBind() throws Exception {
        var bound = new AtomicBoolean();
        var exitStatus = new AtomicInteger(-1);
        RavenrootServerMain.launch(() -> {
            RavenrootServerMain.refuseUnsupportablePackagedEmbed(Map.of(
                    "RAVENROOT_EMBED_ENABLED", "true",
                    "RAVENROOT_EMBED_REGISTRATION_DIR",
                    temporaryDirectory.resolve("embed").toString(),
                    "RAVENROOT_EMBED_VIEWER_ORIGIN", "https://viewer.example",
                    "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", "true",
                    "RAVENROOT_REPLICAS", "1"));
            bound.set(true);
        }, exitStatus::set);
        assertTrue(bound.get());
        assertEquals(-1, exitStatus.get());
    }

    /** The multi-replica refusal, on the variable a deployment actually sets. */
    @Test
    void packagedEmbedRefusesMoreThanOneReplicaBeforeBind() throws Exception {
        var bound = new AtomicBoolean();
        var exitStatus = new AtomicInteger(-1);
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var captured = new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(captured);
            RavenrootServerMain.launch(() -> {
                RavenrootServerMain.refuseUnsupportablePackagedEmbed(Map.of(
                        "RAVENROOT_EMBED_ENABLED", "true",
                        "RAVENROOT_EMBED_REGISTRATION_DIR",
                        temporaryDirectory.resolve("embed").toString(),
                        "RAVENROOT_EMBED_VIEWER_ORIGIN", "https://viewer.example",
                        "RAVENROOT_EMBED_SINGLE_PROCESS_ACKNOWLEDGED", "true",
                        "RAVENROOT_REPLICAS", "3"));
                bound.set(true);
            }, exitStatus::set);
        } finally {
            System.setErr(previous);
        }
        assertEquals(1, exitStatus.get());
        assertEquals(false, bound.get());
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("EMBED_MULTI_REPLICA_UNSUPPORTED"),
                output.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void packagedEmbedDisabledLeavesStartupPathUnchanged() throws Exception {
        var started = new AtomicBoolean();
        var exitStatus = new AtomicInteger(-1);
        RavenrootServerMain.launch(() -> {
            RavenrootServerMain.refuseUnsupportablePackagedEmbed(Map.of());
            RavenrootServerMain.refuseUnsupportablePackagedEmbed(
                    Map.of("RAVENROOT_EMBED_ENABLED", "false"));
            started.set(true);
        }, exitStatus::set);
        assertTrue(started.get());
        assertEquals(-1, exitStatus.get());
    }

    private record RecordingOwner(ArrayList<String> order) implements AutoCloseable {
        @Override
        public void close() {
            order.add("audit");
        }
    }
}
