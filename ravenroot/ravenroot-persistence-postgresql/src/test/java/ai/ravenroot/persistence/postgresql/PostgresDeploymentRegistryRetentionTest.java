package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PostgresDeploymentRegistry#purgeExpiredCommandRecords}, the bounded-retention lever the
 * {@code deployment_command} ledger needs so it does not grow forever.
 *
 * <p>Not covered by the conformance suite, which proves replay works and never that a ledger row
 * eventually goes away — the reference adapter it is also run against has no retention policy to
 * exercise. Without a lever the ledger is a table that only grows, and the failure is not dramatic but
 * terminal: an operator eventually cannot back it up, index it or migrate it in a window.</p>
 */
class PostgresDeploymentRegistryRetentionTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void aReplayWorksBeforeExpiryThenTheRowIsPurgedAndTheKeyBehavesAsAFirstDelivery() {
        var clock = new MutableClock(START);
        Duration retention = Duration.ofHours(1);
        try (var registry = registry(clock, retention, tenant -> DeploymentId.of("dep-1"))) {
            var content = new GraphVersion.Content(1, "graph".getBytes(StandardCharsets.UTF_8), "alice",
                    clock.instant());
            var create = new DeploymentRegistry.CreateCommand("acme", "create-key", "a".repeat(64));
            DeploymentRegistry.Record made = registry.create(content, create).toCompletableFuture().join();

            assertEquals(made, registry.create(content, create).toCompletableFuture().join(),
                    "a replay inside the retention window returns the recorded outcome");

            clock.advance(retention.plusSeconds(1));
            assertEquals(1, registry.purgeExpiredCommandRecords("acme").toCompletableFuture().join(),
                    "exactly the one expired create-ledger row is removed");

            // The deployment was already minted and durably created; the ledger forgetting the command
            // means a retry with the same key is no longer recognised as a replay and is evaluated as a
            // fresh request. Minting is deterministic here, so the fresh create collides with the
            // still-existing aggregate rather than reusing it, and that collision is Conflict -- which
            // is exactly "not a replay" behaving as a first delivery would against an id already taken.
            assertInstanceOf(DeploymentRegistry.FailureReason.Conflict.class,
                    reasonOf(() -> registry.create(content, create).toCompletableFuture().join()));
            assertTrue(registry.get("acme", made.deploymentId()).toCompletableFuture().join().isPresent(),
                    "purging the ledger must not touch the deployment the command produced");
        }
    }

    @Test
    void purgeIsScopedToOneTenantAndLeavesAnotherTenantsLedgerRowsAlone() {
        var clock = new MutableClock(START);
        Duration retention = Duration.ofMinutes(30);
        try (var registry = registry(clock, retention, tenant -> DeploymentId.of("dep-" + tenant))) {
            DeploymentRegistry.Record foreign = create(registry, clock, "tenant-b", "create", 'b');
            create(registry, clock, "tenant-a", "create", 'a');

            clock.advance(retention.plusSeconds(1));
            assertEquals(1, registry.purgeExpiredCommandRecords("tenant-a").toCompletableFuture().join(),
                    "only the named tenant's expired rows are removed");

            var sameCommand = new DeploymentRegistry.CreateCommand("tenant-b", "create", "b".repeat(64));
            var content = new GraphVersion.Content(1, "graph-b".getBytes(StandardCharsets.UTF_8),
                    "alice", START);
            assertEquals(foreign, registry.create(content, sameCommand).toCompletableFuture().join(),
                    "the other tenant's ledger row still replays, so the purge did not reach it");
        }
    }

    private static PostgresDeploymentRegistry registry(MutableClock clock, Duration retention,
                                                       ai.ravenroot.api.deployment.registry
                                                               .DeploymentIdSource ids) {
        return new PostgresDeploymentRegistry(
                PostgresTestDatabase.dataSourceFor("deployment-retention-" + UUID.randomUUID()), clock,
                ids, retention);
    }

    private static DeploymentRegistry.Record create(DeploymentRegistry registry, MutableClock clock,
                                                    String tenant, String key, char digest) {
        var content = new GraphVersion.Content(1, ("graph-" + tenant).getBytes(StandardCharsets.UTF_8),
                "alice", clock.instant());
        return registry.create(content, new DeploymentRegistry.CreateCommand(tenant, key,
                String.valueOf(digest).repeat(64))).toCompletableFuture().join();
    }

    private static DeploymentRegistry.FailureReason reasonOf(Runnable call) {
        CompletionException thrown = assertThrows(CompletionException.class, call::run);
        return assertInstanceOf(DeploymentRegistry.RegistryException.class, thrown.getCause()).reason();
    }
}
