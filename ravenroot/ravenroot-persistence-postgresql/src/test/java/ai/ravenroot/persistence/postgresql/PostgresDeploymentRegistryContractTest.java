package ai.ravenroot.persistence.postgresql;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistryPolicy;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import ai.ravenroot.testkit.persistence.DeploymentRegistryContract;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deployment-registry conformance suite, run against the PostgreSQL adapter.
 *
 * <p>The hook is {@code createRegistry(Clock)} alone: this suite has no {@code storeId} and no reopen,
 * so a registry is never asked to come back to state a previous one left. Each call therefore gets its
 * own schema on the shared container. That is not tidiness — the suite reuses tenant ids such as
 * {@code "tenant"} across independent test methods and this adapter's tables carry no store column,
 * so two registries sharing a schema would answer each other's tenant-scoped assertions from the
 * wrong rows and would pass or fail depending on execution order.</p>
 *
 * <h2>What guards this binding, since no assertion here can be skipped</h2>
 * <p>The other conformance bindings in this module guard a declared {@code StoreCapability}, because
 * the suites they run gate assertions on one and a dropped declaration turns those assertions into
 * skips that look identical to passes. {@link DeploymentRegistryContract} has no such gate, so every
 * inherited assertion runs unconditionally and there is nothing of that shape to protect.</p>
 *
 * <p>What it does have is one assertion that a wrong answer still satisfies.
 * {@code theAdapterPublishesASkewAllowanceStrictlyInsideItsOwnLeaseBound} accepts any non-negative
 * allowance below the maximum lease, and {@link Duration#ZERO} qualifies — correctly, because a
 * single-process reference adapter whose clock <em>is</em> the caller's clock has no skew to allow.
 * For a multi-host adapter zero is a false statement that the inherited assertion cannot see, so
 * {@link #theSkewAllowanceIsNotZeroBecauseTheCallerAndTheAuthorityAreNotOneClock} states it here.</p>
 */
class PostgresDeploymentRegistryContractTest extends DeploymentRegistryContract {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC);

    @Override
    protected DeploymentRegistry createRegistry(Clock clock) {
        return registryOn("deployment-registry-" + UUID.randomUUID(), clock);
    }

    private static DeploymentRegistry registryOn(String storeId, Clock clock) {
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(storeId);
        return new PostgresDeploymentRegistry(dataSource, clock,
                tenant -> DeploymentId.of(UUID.randomUUID().toString()));
    }

    @Test
    void explicitTypedPolicyControlsPublishedAndEnforcedLimits() {
        var policy = new DeploymentRegistryPolicy(Duration.ofDays(2),
                new DeploymentRegistry.Limits(2, Duration.ofSeconds(20), Duration.ofSeconds(2)));
        DataSource dataSource = PostgresTestDatabase.dataSourceFor(
                "deployment-registry-policy-" + UUID.randomUUID());
        try (DeploymentRegistry registry = new PostgresDeploymentRegistry(dataSource, FIXED,
                tenant -> DeploymentId.of(UUID.randomUUID().toString()), policy,
                PostgresStoreConfig.defaults())) {
            assertEquals(policy.limits(), registry.limits());
            assertThrows(CompletionException.class,
                    () -> registry.list("acme", null, 3).toCompletableFuture().join());
        }
    }

    /**
     * The published skew allowance is a claim about two clocks, and here they are provably not one.
     *
     * <p>A holder that must stop working before it can be fenced renews at
     * {@code expiresAt - maxClockSkew}, so the allowance is the only thing standing between a holder
     * whose clock runs fast and a takeover it did not expect. Publishing zero would tell every caller
     * it may trust its own clock to the instant against an authority in another process on another
     * machine.</p>
     */
    @Test
    void theSkewAllowanceIsNotZeroBecauseTheCallerAndTheAuthorityAreNotOneClock() {
        try (DeploymentRegistry registry = createRegistry(FIXED)) {
            Duration skew = registry.limits().maxClockSkew();
            assertTrue(skew.compareTo(Duration.ZERO) > 0,
                    "zero is true only where the caller and the store share one Clock instance, which "
                            + "is exactly what a durable multi-host registry is not");
            assertTrue(skew.compareTo(registry.limits().maximumLeaseTtl()) < 0,
                    "an allowance at or beyond the maximum lease makes every lease unrenewable");
        }
    }

    /**
     * A deployment survives the registry instance that created it, read back from the database rather
     * than from a map this process is still holding.
     *
     * <p>Not reachable through the inherited suite, whose hook cannot ask for a second registry over
     * the same stored state. Two instances over the same schema is the closest this module can come to
     * two hosts without forking a JVM, and it is what makes "durable" mean here what it says.</p>
     */
    @Test
    void aDeploymentOutlivesTheRegistryInstanceThatCreatedIt() {
        String storeId = "deployment-registry-durability-" + UUID.randomUUID();
        DeploymentId created;
        try (DeploymentRegistry registry = registryOn(storeId, FIXED)) {
            created = registry.create(content("graph"), create("acme", "create", 'a'))
                    .toCompletableFuture().join().deploymentId();
        }
        try (DeploymentRegistry reopened = registryOn(storeId, FIXED)) {
            DeploymentRegistry.Record found = reopened.get("acme", created).toCompletableFuture()
                    .join().orElseThrow();
            assertEquals(1, found.latestVersion());
            assertEquals(DeploymentRegistry.DesiredKind.STOPPED, found.desired().kind());
            assertEquals("graph", new String(reopened.version("acme", created, 1).toCompletableFuture()
                    .join().orElseThrow().canonicalSnapshot(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Tenant isolation as the persistence layer must express it: a deployment id from one tenant is
     * invisible <em>and</em> unmutatable from another, and an attempted mutation answers
     * {@code NotFound}, never {@code Conflict}. A {@code Conflict} would tell tenant B that some
     * expectation of its own was violated, which leaks that a row with that id exists at all.
     */
    @Test
    void aDeploymentIdFromOneTenantIsInvisibleAndUnmutatableFromAnother() {
        try (DeploymentRegistry registry = createRegistry(FIXED)) {
            DeploymentRegistry.Record made = registry.create(content("graph-a"),
                    create("tenant-a", "create", 'a')).toCompletableFuture().join();

            assertTrue(registry.get("tenant-b", made.deploymentId()).toCompletableFuture().join()
                    .isEmpty(), "tenant B must not be able to read tenant A's deployment");
            assertTrue(registry.version("tenant-b", made.deploymentId(), 1).toCompletableFuture().join()
                    .isEmpty(), "tenant B must not be able to read tenant A's version either");

            var foreign = new DeploymentRegistry.Command("tenant-b", made.deploymentId(), "mutate",
                    "b".repeat(64), RevisionExpectation.exactly(made.revision()));
            var desired = new DeploymentRegistry.Desired(DeploymentRegistry.DesiredKind.RUNNING, 1L,
                    DeploymentRegistry.UpdateStrategy.STOP_FIRST, 0);
            assertInstanceOf(DeploymentRegistry.FailureReason.NotFound.class,
                    reasonOf(() -> registry.command(desired, foreign).toCompletableFuture().join()),
                    "a Conflict here would leak that a deployment with this id exists for someone");

            assertEquals(made, registry.get("tenant-a", made.deploymentId()).toCompletableFuture()
                    .join().orElseThrow(), "the owning tenant is unaffected by the attempt");
        }
    }

    /**
     * The cursor is the portable {@code "rr1\0tenant\0lastId"} wire form, base64url and unpadded, so a
     * cursor issued by one adapter selects the same next page against another backing the same tenant.
     * Decoded here rather than only round-tripped, because every implementation can round-trip its own
     * cursor whether or not the bytes are the shared ones.
     */
    @Test
    void theCursorWireFormIsTheSharedRr1SchemeByteForByte() {
        try (DeploymentRegistry registry = createRegistry(FIXED)) {
            for (int index = 0; index < 3; index++) {
                registry.create(content("graph-" + index),
                                create("tenant-a", "create-" + index, (char) ('a' + index)))
                        .toCompletableFuture().join();
            }
            DeploymentRegistry.Page first = registry.list("tenant-a", null, 1).toCompletableFuture().join();
            String cursor = first.nextCursor();
            assertNotNull(cursor, "three rows over a page size of one must yield a continuation cursor");

            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split(java.util.regex.Pattern.quote(String.valueOf((char) 0)), -1);
            assertEquals(3, parts.length, "the wire form is exactly three NUL-separated fields");
            assertEquals("rr1", parts[0], "the version marker is the one every adapter must share");
            assertEquals("tenant-a", parts[1]);
            assertEquals(first.items().getFirst().deploymentId().value(), parts[2],
                    "the third field is the last id on the page just returned");
        }
    }

    private static GraphVersion.Content content(String bytes) {
        return new GraphVersion.Content(1, bytes.getBytes(StandardCharsets.UTF_8), "alice",
                FIXED.instant());
    }

    private static DeploymentRegistry.CreateCommand create(String tenant, String key, char digest) {
        return new DeploymentRegistry.CreateCommand(tenant, key, String.valueOf(digest).repeat(64));
    }

    private static DeploymentRegistry.FailureReason reasonOf(Runnable call) {
        CompletionException thrown = assertThrows(CompletionException.class, call::run);
        return assertInstanceOf(DeploymentRegistry.RegistryException.class, thrown.getCause()).reason();
    }
}
