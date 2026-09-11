package ai.ravenroot.persistence.sqlite;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistryPolicy;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.testkit.persistence.DeploymentRegistryContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR 0038 conformance suite, run against the durable SQLite adapter.
 *
 * <p>{@code databaseDirectory} is a fresh {@code @TempDir} per test method, so each of the 22 inherited
 * assertions gets its own file rather than sharing state through a directory JUnit reuses.</p>
 */
class SqliteDeploymentRegistryContractTest extends DeploymentRegistryContract {

    @TempDir
    Path databaseDirectory;

    @Override
    protected DeploymentRegistry createRegistry(Clock clock) {
        return new SqliteDeploymentRegistry(databaseDirectory.resolve("deployment-registry.db"), clock,
                tenant -> DeploymentId.of(UUID.randomUUID().toString()));
    }

    @Test
    void explicitTypedPolicyControlsPublishedAndEnforcedLimits() {
        var policy = new DeploymentRegistryPolicy(Duration.ofDays(2),
                new DeploymentRegistry.Limits(2, Duration.ofSeconds(20), Duration.ofSeconds(2)));
        try (DeploymentRegistry registry = new SqliteDeploymentRegistry(
                SqliteStoreLocation.ofFile(databaseDirectory.resolve("explicit-policy.db")),
                Clock.systemUTC(), tenant -> DeploymentId.of(UUID.randomUUID().toString()), policy)) {
            assertEquals(policy.limits(), registry.limits());
            assertThrows(CompletionException.class,
                    () -> registry.list("acme", null, 3).toCompletableFuture().join());
        }
    }

    /**
     * Proves tenant isolation the way the persistence layer must: a deployment id from one tenant is
     * invisible <em>and</em> unmutatable from another, and the failure for an attempted mutation is
     * {@code NotFound}, never {@code Conflict}. A {@code Conflict} would tell tenant B that some
     * expectation of its own was violated, which leaks the fact that a row with that id exists at all;
     * {@code NotFound} is the only answer that reveals nothing.
     *
     * <p>New relative to the inherited suite: every inherited test drives a single tenant's own
     * aggregate. This is the one assertion in this module that deliberately presents tenant A's
     * {@link DeploymentId} to a query and a mutation scoped to tenant B.</p>
     */
    @Test
    void aDeploymentIdFromOneTenantIsInvisibleAndUnmutatableFromAnother() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            byte[] bytes = "graph-a".getBytes(StandardCharsets.UTF_8);
            var content = new GraphVersion.Content(1, bytes, "alice", clock.instant());
            var create = new DeploymentRegistry.CreateCommand("tenant-a", "create", "a".repeat(64));
            DeploymentRegistry.Record made = registry.create(content, create).toCompletableFuture().join();

            assertTrue(registry.get("tenant-b", made.deploymentId()).toCompletableFuture().join().isEmpty(),
                    "tenant B must not be able to read tenant A's deployment");
            assertTrue(registry.version("tenant-b", made.deploymentId(), 1).toCompletableFuture().join().isEmpty(),
                    "tenant B must not be able to read tenant A's version either");

            var foreignCommand = new DeploymentRegistry.Command("tenant-b", made.deploymentId(), "mutate",
                    "b".repeat(64), RevisionExpectation.exactly(made.revision()));
            var desired = new DeploymentRegistry.Desired(DeploymentRegistry.DesiredKind.RUNNING, 1L,
                    DeploymentRegistry.UpdateStrategy.STOP_FIRST, 0);
            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> registry.command(desired, foreignCommand).toCompletableFuture().join());
            DeploymentRegistry.RegistryException failure = assertInstanceOf(
                    DeploymentRegistry.RegistryException.class, thrown.getCause());
            assertInstanceOf(DeploymentRegistry.FailureReason.NotFound.class, failure.reason(),
                    "a Conflict here would leak that a deployment with this id exists for someone");

            // The owning tenant is unaffected by the cross-tenant attempt.
            assertEquals(made, registry.get("tenant-a", made.deploymentId()).toCompletableFuture().join()
                    .orElseThrow());
        }
    }

    /**
     * {@code list}'s cursor is specified as the portable wire form {@code "rr1\0tenant\0lastId"},
     * base64url without padding (see {@link DeploymentRegistryContract}'s pagination assertion and
     * {@code InMemoryDeploymentRegistry}'s matching private codec, in {@code ravenroot-core}, which this
     * module cannot depend on to compare against directly). This test decodes a cursor this adapter
     * actually issued and checks the wire bytes themselves, rather than only checking that this
     * adapter can round-trip its own cursor -- which every implementation trivially can, portable or
     * not.
     */
    @Test
    void theCursorWireFormIsTheSharedRr1SchemeByteForByte() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            for (int i = 0; i < 3; i++) {
                byte[] bytes = ("graph-" + i).getBytes(StandardCharsets.UTF_8);
                var content = new GraphVersion.Content(1, bytes, "alice", clock.instant());
                var create = new DeploymentRegistry.CreateCommand("tenant-a", "create-" + i,
                        String.valueOf((char) ('a' + i)).repeat(64));
                registry.create(content, create).toCompletableFuture().join();
            }
            DeploymentRegistry.Page first = registry.list("tenant-a", null, 1).toCompletableFuture().join();
            String cursor = first.nextCursor();
            assertTrue(cursor != null, "three rows over a page size of one must yield a continuation cursor");

            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String raw = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = raw.split(java.util.regex.Pattern.quote(String.valueOf((char) 0)), -1);
            assertEquals(3, parts.length, "the wire form is exactly three NUL-separated fields");
            assertEquals("rr1", parts[0], "the version marker is the one every adapter must share");
            assertEquals("tenant-a", parts[1]);
            assertTrue(parts[2].equals(first.items().get(0).deploymentId().value()),
                    "the third field is the last id on the page just returned");
        }
    }
}
