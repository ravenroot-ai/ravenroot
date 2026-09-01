package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/** Reusable conformance suite for deployment-registry adapters. */
public abstract class DeploymentRegistryContract {
    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    protected abstract DeploymentRegistry createRegistry(Clock clock);

    private static GraphVersion.Content graph(MutableClock clock, String bytes) {
        return new GraphVersion.Content(1, bytes.getBytes(StandardCharsets.UTF_8), "alice", clock.instant());
    }
    private static DeploymentRegistry.CreateCommand createCommand(String tenant, String key, char digest) {
        return new DeploymentRegistry.CreateCommand(tenant, key, String.valueOf(digest).repeat(64));
    }
    private static DeploymentRegistry.Command command(DeploymentRegistry.Record record, String key, char digest) {
        return new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(), key,
                String.valueOf(digest).repeat(64), RevisionExpectation.exactly(record.revision()));
    }
    private static DeploymentRegistry.Command anyCommand(DeploymentRegistry.Record record, String key) {
        return new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(), key,
                "f".repeat(64), RevisionExpectation.any());
    }
    private static DeploymentRegistry.Desired running(long version) {
        return new DeploymentRegistry.Desired(DeploymentRegistry.DesiredKind.RUNNING, version,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST, 0);
    }
    private static DeploymentRegistry.Record create(DeploymentRegistry registry, MutableClock clock,
                                                    String tenant, String key, String bytes) {
        return registry.create(graph(clock, bytes), createCommand(tenant, key, 'a')).toCompletableFuture().join();
    }
    private static DeploymentRegistry.RegistryException rejected(Runnable call, Class<?> reason) {
        CompletionException exception = assertThrows(CompletionException.class, call::run);
        DeploymentRegistry.RegistryException registry = assertInstanceOf(
                DeploymentRegistry.RegistryException.class, exception.getCause());
        assertTrue(reason.isInstance(registry.reason()), () -> "unexpected reason " + registry.reason());
        return registry;
    }

    @Test
    final void serverMintsStableTenantBoundIdentityAndKeepsExactImmutableBytes() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            byte[] source = "one".getBytes(StandardCharsets.UTF_8);
            GraphVersion.Content content = new GraphVersion.Content(1, source, "alice", clock.instant());
            DeploymentRegistry.CreateCommand create = createCommand("tenant-a", "create", 'a');
            DeploymentRegistry.Record made = registry.create(content, create).toCompletableFuture().join();
            assertNotNull(made.deploymentId(), "the registry, not the caller, mints identity");
            assertEquals(made, registry.create(content, create).toCompletableFuture().join(),
                    "create replay returns the same minted aggregate");
            source[0] = 'x';
            GraphVersion stored = registry.version("tenant-a", made.deploymentId(), 1).toCompletableFuture().join().orElseThrow();
            assertEquals("one", new String(stored.canonicalSnapshot(), StandardCharsets.UTF_8));
            assertEquals("7692c3ad3540bb803c020b3aee66cd8887123234ea0c6e7143c0add73ff431ed", stored.canonicalDigest());
            byte[] returned = stored.canonicalSnapshot(); returned[0] = 'x';
            assertEquals("one", new String(registry.version("tenant-a", made.deploymentId(), 1)
                    .toCompletableFuture().join().orElseThrow().canonicalSnapshot(), StandardCharsets.UTF_8));
            assertTrue(registry.get("tenant-b", made.deploymentId()).toCompletableFuture().join().isEmpty());
            rejected(() -> registry.create(graph(clock, "other"), createCommand("tenant-a", "create", 'b'))
                    .toCompletableFuture().join(), DeploymentRegistry.FailureReason.Conflict.class);
        }
    }

    @Test
    final void versionsCannotCrossDeploymentOrReplaceExistingBytes() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record first = create(registry, clock, "tenant", "first", "v1");
            DeploymentRegistry.Record second = create(registry, clock, "tenant", "second", "other");
            DeploymentRegistry.Record appended = registry.append(2, graph(clock, "v2"), command(first, "shared", 'b'))
                    .toCompletableFuture().join();
            DeploymentRegistry.Record otherAppended = registry.append(2, graph(clock, "other-v2"),
                    command(second, "shared", 'b')).toCompletableFuture().join();
            assertEquals("v2", new String(registry.version("tenant", first.deploymentId(), 2)
                    .toCompletableFuture().join().orElseThrow().canonicalSnapshot(), StandardCharsets.UTF_8));
            assertEquals("other-v2", new String(registry.version("tenant", second.deploymentId(), 2)
                    .toCompletableFuture().join().orElseThrow().canonicalSnapshot(), StandardCharsets.UTF_8));
            assertNotEquals(appended.deploymentId(), otherAppended.deploymentId(),
                    "the same action/key cannot replay across deployment aggregates");
            rejected(() -> registry.append(2, graph(clock, "different"), command(appended, "replace", 'c'))
                    .toCompletableFuture().join(), DeploymentRegistry.FailureReason.Conflict.class);
            assertEquals("v1", new String(registry.version("tenant", first.deploymentId(), 1)
                    .toCompletableFuture().join().orElseThrow().canonicalSnapshot(), StandardCharsets.UTF_8));
        }
    }

    @Test
    final void paginationIsOpaqueBoundedStableAndTenantBound() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            Set<DeploymentId> expected = new HashSet<>();
            for (int i = 0; i < 5; i++) expected.add(create(registry, clock, "tenant-a", "c" + i, "v" + i).deploymentId());
            create(registry, clock, "tenant-b", "foreign", "x");
            DeploymentRegistry.Page first = registry.list("tenant-a", null, 2).toCompletableFuture().join();
            assertEquals(2, first.items().size());
            assertNotNull(first.nextCursor());
            assertFalse(first.nextCursor().contains("tenant-a"));
            Set<DeploymentId> seen = new HashSet<>();
            String cursor = null;
            do {
                DeploymentRegistry.Page page = registry.list("tenant-a", cursor, 2).toCompletableFuture().join();
                page.items().forEach(item -> assertTrue(seen.add(item.deploymentId()), "pagination must not duplicate"));
                cursor = page.nextCursor();
            } while (cursor != null);
            assertEquals(expected, seen);
            rejected(() -> registry.list("tenant-b", first.nextCursor(), 2).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.InvalidRequest.class);
            rejected(() -> registry.list("tenant-a", "%%%not-base64", 2).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.InvalidRequest.class);
        }
    }

    @Test
    final void casAndLedgerAreScopedByDeploymentActionAndKey() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record desired = registry.command(running(1), command(made, "shared", 'b'))
                    .toCompletableFuture().join();
            assertEquals(desired, registry.command(running(1), command(made, "shared", 'b'))
                    .toCompletableFuture().join(), "same action replay returns original coordinates");
            DeploymentRegistry.Record appended = registry.append(2, graph(clock, "v2"), command(desired, "shared", 'b'))
                    .toCompletableFuture().join();
            assertEquals(2, appended.latestVersion(), "same key in another action is not a replay collision");
            rejected(() -> registry.command(running(1), command(made, "stale", 'c')).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.Conflict.class);
            rejected(() -> registry.command(running(1), new DeploymentRegistry.Command("tenant", made.deploymentId(),
                    "shared", "d".repeat(64), RevisionExpectation.exactly(made.revision()))).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.Conflict.class);
        }
    }

    @Test
    final void anyRevisionCannotReachAnyExistingAggregateMutation() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record acquired = registry.acquire("owner", Duration.ofSeconds(30), command(made, "lease", 'b'))
                    .toCompletableFuture().join();
            DeploymentRegistry.Lease lease = acquired.lease();
            DeploymentRegistry.Observation ready = new DeploymentRegistry.Observation(
                    DeploymentRegistry.ObservedKind.READY, 1L, 0, clock.instant());
            DeploymentRegistry.Failure failure = new DeploymentRegistry.Failure("START_FAILED", "failed", clock.instant());
            DeploymentRegistry.Tombstone tombstone = new DeploymentRegistry.Tombstone("delete", clock.instant());

            List<Runnable> everyExistingAggregateMutation = List.of(
                    () -> registry.append(2, graph(clock, "v2"), anyCommand(acquired, "append")).toCompletableFuture().join(),
                    () -> registry.command(running(1), anyCommand(acquired, "command")).toCompletableFuture().join(),
                    () -> registry.observe(ready, lease, anyCommand(acquired, "observe")).toCompletableFuture().join(),
                    () -> registry.fail(failure, lease, anyCommand(acquired, "fail")).toCompletableFuture().join(),
                    () -> registry.tombstone(tombstone, anyCommand(acquired, "tombstone")).toCompletableFuture().join(),
                    () -> registry.acquire("other", Duration.ofSeconds(10), anyCommand(acquired, "acquire")).toCompletableFuture().join(),
                    () -> registry.renew(lease, Duration.ofSeconds(10), anyCommand(acquired, "renew")).toCompletableFuture().join(),
                    () -> registry.release(lease, anyCommand(acquired, "release")).toCompletableFuture().join());

            for (Runnable attempt : everyExistingAggregateMutation) {
                assertThrows(IllegalArgumentException.class, attempt::run,
                        "RevisionExpectation.Any must be refused at the shared registry boundary");
                assertEquals(acquired, registry.get("tenant", acquired.deploymentId()).toCompletableFuture().join().orElseThrow(),
                        "a rejected non-CAS mutation must be atomic");
                assertTrue(registry.version("tenant", acquired.deploymentId(), 2).toCompletableFuture().join().isEmpty());
            }
        }
    }

    @Test
    final void observedStateRequiresACoherentKnownActiveVersionAndRejectsAtomically() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record acquired = registry.acquire("owner", Duration.ofSeconds(30), command(made, "lease", 'b'))
                    .toCompletableFuture().join();

            assertThrows(IllegalArgumentException.class, () -> new DeploymentRegistry.Observation(
                    DeploymentRegistry.ObservedKind.READY, null, 0, clock.instant()));
            assertEquals(acquired, registry.get("tenant", acquired.deploymentId()).toCompletableFuture().join().orElseThrow());

            DeploymentRegistry.Observation unknown = new DeploymentRegistry.Observation(
                    DeploymentRegistry.ObservedKind.READY, 99L, 0, clock.instant());
            rejected(() -> registry.observe(unknown, acquired.lease(), command(acquired, "unknown", 'c'))
                    .toCompletableFuture().join(), DeploymentRegistry.FailureReason.InvalidRequest.class);
            assertEquals(acquired, registry.get("tenant", acquired.deploymentId()).toCompletableFuture().join().orElseThrow(),
                    "unknown version evidence must not advance observation, revision, or ledger outcome");

            DeploymentRegistry.Observation known = new DeploymentRegistry.Observation(
                    DeploymentRegistry.ObservedKind.READY, 1L, 0, clock.instant());
            DeploymentRegistry.Record observed = registry.observe(known, acquired.lease(), command(acquired, "known", 'd'))
                    .toCompletableFuture().join();
            assertEquals(DeploymentRegistry.ObservedKind.READY, observed.observed().state());
            assertEquals(1L, observed.observed().activeVersion());
        }
    }

    @Test
    final void everyObservedStateDefinesWhetherAnActiveVersionIsPresent() {
        for (DeploymentRegistry.ObservedKind state : List.of(DeploymentRegistry.ObservedKind.READY,
                DeploymentRegistry.ObservedKind.DEGRADED, DeploymentRegistry.ObservedKind.DRAINING,
                DeploymentRegistry.ObservedKind.STOPPING)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DeploymentRegistry.Observation(state, null, 0, START), state.toString());
        }
        for (DeploymentRegistry.ObservedKind state : List.of(DeploymentRegistry.ObservedKind.COLD,
                DeploymentRegistry.ObservedKind.STARTING, DeploymentRegistry.ObservedKind.STOPPED,
                DeploymentRegistry.ObservedKind.FAILED)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DeploymentRegistry.Observation(state, 1L, 0, START), state.toString());
        }
    }

    @Test
    final void expiredOwnerCannotObserveOrReplayAndTakeoverFencesOldOwner() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record acquired = registry.acquire("owner-one", Duration.ofSeconds(10), command(made, "acquire", 'b'))
                    .toCompletableFuture().join();
            DeploymentRegistry.Lease old = acquired.lease();
            DeploymentRegistry.Observation observation = new DeploymentRegistry.Observation(
                    DeploymentRegistry.ObservedKind.READY, 1L, 0, clock.instant());
            DeploymentRegistry.Command observe = command(acquired, "observe", 'c');
            DeploymentRegistry.Record observed = registry.observe(observation, old, observe).toCompletableFuture().join();
            clock.advance(Duration.ofSeconds(11));
            rejected(() -> registry.observe(observation, old, observe).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.LeaseLost.class);
            DeploymentRegistry.Record takeover = registry.acquire("owner-two", Duration.ofSeconds(10), command(observed, "takeover", 'd'))
                    .toCompletableFuture().join();
            assertTrue(takeover.lease().fence() > old.fence());
            rejected(() -> registry.renew(old, Duration.ofSeconds(10), command(takeover, "renew-old", 'e'))
                    .toCompletableFuture().join(), DeploymentRegistry.FailureReason.Fenced.class);
            rejected(() -> registry.release(old, command(takeover, "release-old", 'f')).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.Fenced.class);
        }
    }

    @Test
    final void leaseMutationIsCasProtectedIdempotentAndAtomic() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Command acquire = command(made, "acquire", 'b');
            DeploymentRegistry.Record acquired = registry.acquire("owner", Duration.ofSeconds(30), acquire).toCompletableFuture().join();
            assertEquals(acquired, registry.acquire("owner", Duration.ofSeconds(30), acquire).toCompletableFuture().join());
            DeploymentRegistry.Command renew = command(acquired, "renew", 'c');
            DeploymentRegistry.Record renewed = registry.renew(acquired.lease(), Duration.ofSeconds(40), renew).toCompletableFuture().join();
            assertEquals(renewed, registry.renew(acquired.lease(), Duration.ofSeconds(40), renew).toCompletableFuture().join());
            DeploymentRegistry.Command release = command(renewed, "release", 'd');
            DeploymentRegistry.Record released = registry.release(renewed.lease(), release).toCompletableFuture().join();
            assertNull(released.lease());
            assertEquals(released, registry.release(renewed.lease(), release).toCompletableFuture().join());
        }
    }

    @Test
    final void tombstoneIsTerminalImmutableAndRetainsVersionHistory() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record appended = registry.append(2, graph(clock, "v2"), command(made, "append", 'b'))
                    .toCompletableFuture().join();
            DeploymentRegistry.Tombstone tombstone = new DeploymentRegistry.Tombstone("operator request", clock.instant());
            DeploymentRegistry.Record deleted = registry.tombstone(tombstone, command(appended, "delete", 'c'))
                    .toCompletableFuture().join();
            assertEquals(deleted, registry.get("tenant", made.deploymentId()).toCompletableFuture().join().orElseThrow());
            assertTrue(registry.version("tenant", made.deploymentId(), 1).toCompletableFuture().join().isPresent());
            assertTrue(registry.version("tenant", made.deploymentId(), 2).toCompletableFuture().join().isPresent());
            rejected(() -> registry.command(running(2), command(deleted, "mutate", 'd')).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.Conflict.class);
            rejected(() -> registry.acquire("owner", Duration.ofSeconds(10), command(deleted, "lease", 'e'))
                    .toCompletableFuture().join(), DeploymentRegistry.FailureReason.Conflict.class);
        }
    }

    @Test
    final void sanitizedFailureAndExplicitRecoveryKeepCoherentTimestamps() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record acquired = registry.acquire("owner", Duration.ofSeconds(30), command(made, "acquire", 'b'))
                    .toCompletableFuture().join();
            clock.advance(Duration.ofSeconds(1));
            DeploymentRegistry.Failure failure = new DeploymentRegistry.Failure("START_FAILED", "readiness refused", clock.instant());
            DeploymentRegistry.Record failed = registry.fail(failure, acquired.lease(), command(acquired, "fail", 'c'))
                    .toCompletableFuture().join();
            assertEquals(failure, failed.failure());
            assertFalse(failed.updatedAt().isBefore(failed.createdAt()));
            DeploymentRegistry.Record recovered = registry.command(running(1), command(failed, "recover", 'd'))
                    .toCompletableFuture().join();
            assertNull(recovered.failure(), "a new desired generation is explicit recovery, not rewritten history");
            assertEquals(1, recovered.generation());
            assertThrows(IllegalArgumentException.class,
                    () -> new DeploymentRegistry.Failure("LEAK", "secret\nsecond line", clock.instant()));
        }
    }

    /** Mutable deterministic time seam required for lease expiry/takeover conformance. */
    protected static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
