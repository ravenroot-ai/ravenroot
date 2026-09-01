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
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RavenrootServerMainLifecycleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void pluginRefusalClosesAuditAndCheckpointsStoreBeforeExitStrategyRuns() throws Exception {
        var location = SqliteStoreLocation.underDirectory(temporaryDirectory.resolve("store"));
        var configuration = new ExecutionStoreConfiguration(true, location);
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
