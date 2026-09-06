package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.testkit.persistence.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqliteDeploymentRegistry#purgeExpiredCommandRecords}, the bounded-retention lever the
 * {@code deployment_command} ledger needs so it does not grow forever (ADR 0038 D11's retention
 * clause). Not covered by {@link SqliteDeploymentRegistryContractTest}: the inherited conformance
 * suite proves replay works, never that a ledger row eventually goes away, because the in-memory
 * reference adapter it is also run against has no retention policy to exercise.
 */
class SqliteDeploymentRegistryRetentionTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void aReplayWorksBeforeExpiryThenTheRowIsPurgedAndTheKeyBehavesAsAFirstDelivery(@TempDir Path directory) {
        MutableClock clock = new MutableClock(START);
        Duration retention = Duration.ofHours(1);
        try (var registry = new SqliteDeploymentRegistry(directory.resolve("retention.db"), clock,
                tenant -> DeploymentId.of("dep-1"), retention)) {
            var content = new GraphVersion.Content(1, "graph".getBytes(StandardCharsets.UTF_8), "alice",
                    clock.instant());
            var create = new DeploymentRegistry.CreateCommand("acme", "create-key", "a".repeat(64));
            DeploymentRegistry.Record made = registry.create(content, create).toCompletableFuture().join();

            // Before expiry, the same key still replays to the exact original outcome.
            DeploymentRegistry.Record replayed = registry.create(content, create).toCompletableFuture().join();
            assertEquals(made, replayed, "a replay inside the retention window returns the recorded outcome");

            // Past the retention window, purge removes the ledger row.
            clock.advance(retention.plusSeconds(1));
            long purged = registry.purgeExpiredCommandRecords("acme").toCompletableFuture().join();
            assertEquals(1, purged, "exactly the one expired create-ledger row is removed");

            // The deployment id was already minted and durably created; the *ledger* forgetting the
            // command means a retry with the same key is no longer recognised as a replay and is
            // evaluated as a fresh request. Minting is deterministic here (tenant -> "dep-1"), so the
            // fresh create collides with the still-existing aggregate rather than reusing it, and that
            // collision is reported as Conflict -- which is exactly "not a replay" behaving as a first
            // delivery would against an id that already exists.
            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> registry.create(content, create).toCompletableFuture().join());
            DeploymentRegistry.RegistryException failure = assertInstanceOf(
                    DeploymentRegistry.RegistryException.class, thrown.getCause());
            assertInstanceOf(DeploymentRegistry.FailureReason.Conflict.class, failure.reason(),
                    "a purged key is evaluated as a new request, not replayed from a forgotten ledger row");
        }
    }

    @Test
    void purgeIsScopedToOneTenantAndLeavesOtherTenantsLedgerRowsAlone(@TempDir Path directory) {
        MutableClock clock = new MutableClock(START);
        Duration retention = Duration.ofMinutes(30);
        try (var registry = new SqliteDeploymentRegistry(directory.resolve("retention-tenant.db"), clock,
                tenant -> DeploymentId.of("dep-" + tenant), retention)) {
            var contentA = new GraphVersion.Content(1, "graph-a".getBytes(StandardCharsets.UTF_8), "alice",
                    clock.instant());
            var createA = new DeploymentRegistry.CreateCommand("tenant-a", "create", "a".repeat(64));
            DeploymentRegistry.Record madeA = registry.create(contentA, createA).toCompletableFuture().join();

            var contentB = new GraphVersion.Content(1, "graph-b".getBytes(StandardCharsets.UTF_8), "alice",
                    clock.instant());
            var createB = new DeploymentRegistry.CreateCommand("tenant-b", "create", "b".repeat(64));
            DeploymentRegistry.Record madeB = registry.create(contentB, createB).toCompletableFuture().join();

            clock.advance(retention.plusSeconds(1));
            long purged = registry.purgeExpiredCommandRecords("tenant-a").toCompletableFuture().join();
            assertEquals(1, purged, "only tenant-a's expired row is removed");

            // Tenant B's ledger row is untouched: its create key still replays to the original record.
            DeploymentRegistry.Record replayedB = registry.create(contentB, createB).toCompletableFuture().join();
            assertEquals(madeB, replayedB, "purging tenant-a must not disturb tenant-b's ledger");

            // Tenant A's own aggregate (as opposed to its ledger) is untouched by the purge: it is
            // still readable at exactly the coordinates it was created with.
            assertTrue(registry.get("tenant-a", madeA.deploymentId()).toCompletableFuture().join().isPresent());
        }
    }

    /** Guards the retention constructor argument itself, since a non-positive window can never expire anything. */
    @Test
    void aNonPositiveRetentionIsRejectedAtConstruction(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> new SqliteDeploymentRegistry(
                directory.resolve("invalid-retention.db"), new MutableClock(START),
                tenant -> DeploymentId.of("dep"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SqliteDeploymentRegistry(
                directory.resolve("invalid-retention-2.db"), new MutableClock(START),
                tenant -> DeploymentId.of("dep"), Duration.ofSeconds(-1)));
    }

    /**
     * A non-create ledger row (an {@code append}) is purged exactly like a create row, proving purge is
     * not special-cased to the one action the digest-driven examples above happen to use.
     */
    @Test
    void aNonCreateLedgerRowIsAlsoPurgedAfterItsRetentionWindow(@TempDir Path directory) {
        MutableClock clock = new MutableClock(START);
        Duration retention = Duration.ofMinutes(10);
        try (var registry = new SqliteDeploymentRegistry(directory.resolve("retention-append.db"), clock,
                tenant -> DeploymentId.of("dep-1"), retention)) {
            var content = new GraphVersion.Content(1, "v1".getBytes(StandardCharsets.UTF_8), "alice",
                    clock.instant());
            var create = new DeploymentRegistry.CreateCommand("acme", "create", "a".repeat(64));
            DeploymentRegistry.Record made = registry.create(content, create).toCompletableFuture().join();

            var appendContent = new GraphVersion.Content(1, "v2".getBytes(StandardCharsets.UTF_8), "alice",
                    clock.instant());
            var appendCommand = new DeploymentRegistry.Command(made.tenantId(), made.deploymentId(), "append",
                    "b".repeat(64), RevisionExpectation.exactly(made.revision()));
            registry.append(2, appendContent, appendCommand).toCompletableFuture().join();

            clock.advance(retention.plusSeconds(1));
            long purged = registry.purgeExpiredCommandRecords("acme").toCompletableFuture().join();
            assertEquals(2, purged, "both the create row and the append row have expired");
        }
    }
}
