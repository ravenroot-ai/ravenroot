package ai.ravenroot.testkit.persistence;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentCommandOutcome;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.deployment.registry.GraphVersion;
import ai.ravenroot.api.persistence.RevisionExpectation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable conformance suite for deployment-registry adapters.
 *
 * <h2>Two kinds of row, in one suite, on purpose</h2>
 * <p>Most of what follows drives a live adapter. The lifecycle-command matrix at the end does not: it
 * asserts the shape of the accepted contract itself — which command is a barrier, which level each
 * one converges to, how they rank against each other, and what the idempotency digest is taken over.
 * Those facts are adapter-independent, and they live here rather than in a separate value test
 * because they are the rows a coordinator suite has to drive in wave 2. Keeping the matrix in one
 * place is what stops the coordinator's eight scenarios and the contract's eight commands from
 * drifting into two lists that agree only by accident, which is exactly how a command quietly ends up
 * with no scenario at all. {@link #lifecycleCommandMatrix()} is public for that reuse.</p>
 *
 * <h2>What this suite does not cover, and where those rows actually live</h2>
 * <p>A suite that must run against any adapter can only assert what every adapter has. Three rows the
 * acceptance criteria name are therefore <em>not</em> here, and two of them are now proved elsewhere
 * rather than left open:</p>
 * <ul>
 *   <li><b>Partition.</b> Two live authorities disagreeing needs a durable adapter with real
 *       cross-process exclusion; the reference adapter is one map in one JVM, so a "partition" test
 *       against it would assert only that the test set two fields. It is driven against the durable
 *       adapter by that module's own killed-holder cell.</li>
 *   <li><b>Slow shutdown.</b> A drain that outlives its bound needs a runtime target to be slow, and
 *       {@link LifecycleCommand.Drain#bound()} is the parameter it drives. The coordinator suite owns
 *       that row, because a registry has no runtime to be slow.</li>
 *   <li><b>Restart under load.</b> The half-open barrier is asserted here only as generation
 *       arithmetic. That work admitted at {@code G} completes while work admitted afterwards enters at
 *       {@code G + 1} needs something actually executing, and no implementor of the lifecycle port
 *       admits work yet — so this one is still genuinely open rather than relocated.</li>
 * </ul>
 */
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
        List<DeploymentRegistry.ObservedKind> bound = List.of(DeploymentRegistry.ObservedKind.READY,
                DeploymentRegistry.ObservedKind.DEGRADED, DeploymentRegistry.ObservedKind.DRAINING,
                DeploymentRegistry.ObservedKind.STOPPING, DeploymentRegistry.ObservedKind.PAUSED,
                DeploymentRegistry.ObservedKind.DRAINED);
        List<DeploymentRegistry.ObservedKind> unbound = List.of(DeploymentRegistry.ObservedKind.COLD,
                DeploymentRegistry.ObservedKind.STARTING, DeploymentRegistry.ObservedKind.STOPPED,
                DeploymentRegistry.ObservedKind.FAILED);
        for (DeploymentRegistry.ObservedKind state : bound) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DeploymentRegistry.Observation(state, null, 0, START), state.toString());
        }
        for (DeploymentRegistry.ObservedKind state : unbound) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DeploymentRegistry.Observation(state, 1L, 0, START), state.toString());
        }
        // Asserted over the whole enum rather than over two hand-written lists, so an observation
        // added later cannot join without someone deciding which side of the boundary it sits on.
        Set<DeploymentRegistry.ObservedKind> covered = new LinkedHashSet<>(bound);
        covered.addAll(unbound);
        assertEquals(EnumSet.allOf(DeploymentRegistry.ObservedKind.class), EnumSet.copyOf(covered));
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

    private static DeploymentRegistry.Command lifecycleCommand(DeploymentRegistry.Record record, String key,
                                                               char digest, long expectedGeneration) {
        return new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(), key,
                String.valueOf(digest).repeat(64), RevisionExpectation.exactly(record.revision()),
                GenerationExpectation.exactly(expectedGeneration));
    }
    private static DeploymentRegistry.Desired level(DeploymentRegistry.DesiredKind kind) {
        return new DeploymentRegistry.Desired(kind, null, null, 0);
    }

    // ================================================ Three monotone axes (ADR 0038 D1)

    /**
     * The whole point of naming three axes is that each one answers a question the other two cannot,
     * and that property is destroyed the moment any operation moves two of them. A takeover that
     * bumped the generation would tell every consumer watching the lifecycle that an operator changed
     * their mind, when all that happened is that a reconciler died and another picked the deployment
     * up. A lifecycle command that bumped the fence would revoke the current owner's authority as a
     * side effect of an operator pressing pause.
     */
    @Test
    final void ownershipAndLifecycleAdvanceOnSeparateAxesThatNeverMoveTogether() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            assertEquals(0, made.generation(), "a deployment begins before any lifecycle decision");

            DeploymentRegistry.Record leased = registry.acquire("owner-one", Duration.ofSeconds(10),
                    command(made, "acquire", 'b')).toCompletableFuture().join();
            long firstFence = leased.lease().fence();
            assertEquals(made.generation(), leased.generation(),
                    "acquiring ownership is not a lifecycle decision and must not advance the generation");

            DeploymentRegistry.Record started = registry.command(running(1), command(leased, "start", 'c'))
                    .toCompletableFuture().join();
            assertEquals(leased.generation() + 1, started.generation());
            assertEquals(firstFence, started.lease().fence(),
                    "a lifecycle command must not revoke the current owner by moving the fence");

            clock.advance(Duration.ofSeconds(11));
            DeploymentRegistry.Record takenOver = registry.acquire("owner-two", Duration.ofSeconds(10),
                    command(started, "takeover", 'd')).toCompletableFuture().join();
            assertTrue(takenOver.lease().fence() > firstFence, "a takeover advances the fence");
            assertEquals(started.generation(), takenOver.generation(),
                    "a takeover changes who is authoritative, not what was decided");

            // The revision, by contrast, moved on every one of the three writes: it is the CAS token
            // and says only "something was written", which is precisely why it cannot stand in for
            // either of the other two.
            assertTrue(takenOver.revision() > started.revision() && started.revision() > leased.revision());
        }
    }

    /**
     * Criterion 1 asks a lifecycle mutation to pin both expectations, and this is why neither is
     * sufficient alone. The generation here is stale while the revision is exactly right, so an
     * adapter that only compared revisions would accept a decision made against a lifecycle state
     * that no longer exists -- silently applying an operator's older intent over their newer one.
     */
    @Test
    final void aLifecycleMutationPinsTheGenerationItWasDecidedAgainstAndNotOnlyTheRevision() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record started = registry.command(running(1), lifecycleCommand(made, "start", 'b', 0))
                    .toCompletableFuture().join();
            assertEquals(1, started.generation());

            // A current revision paired with the generation the caller last saw before "start" landed.
            rejected(() -> registry.command(level(DeploymentRegistry.DesiredKind.PAUSED),
                            lifecycleCommand(started, "pause", 'c', 0)).toCompletableFuture().join(),
                    DeploymentRegistry.FailureReason.Conflict.class);
            assertEquals(started, registry.get("tenant", made.deploymentId()).toCompletableFuture().join().orElseThrow(),
                    "a refused generation expectation must leave the aggregate untouched");

            DeploymentRegistry.Record paused = registry.command(level(DeploymentRegistry.DesiredKind.PAUSED),
                    lifecycleCommand(started, "pause", 'd', 1)).toCompletableFuture().join();
            assertEquals(DeploymentRegistry.DesiredKind.PAUSED, paused.desired().kind());
            assertEquals(2, paused.generation());

            // An unstated expectation stays unstated: every non-lifecycle mutation in this suite uses
            // the pre-generation constructors and must keep behaving exactly as it did.
            assertEquals(GenerationExpectation.any(), command(paused, "any", 'e').expectedGeneration());
        }
    }

    /**
     * A barrier is the one lifecycle move that advances the generation while leaving the level alone
     * (ADR 0038 D5, D6). Writing the current desired state back is how that is expressed against this
     * port, and the fact worth pinning is that the level really is unchanged afterwards -- otherwise
     * a restart would be indistinguishable from a stop followed by a start, which is the shape ADR
     * 0038 rejects because a crash between the two halves leaves a deployment durably stopped that an
     * operator asked to keep running.
     */
    @Test
    final void aBarrierAdvancesTheGenerationWhileLeavingTheDesiredLevelExactlyWhereItWas() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record started = registry.command(running(1), lifecycleCommand(made, "start", 'b', 0))
                    .toCompletableFuture().join();

            DeploymentRegistry.Record afterBarrier = registry.command(started.desired(),
                    lifecycleCommand(started, "restart", 'c', 1)).toCompletableFuture().join();

            assertEquals(started.desired().kind(), afterBarrier.desired().kind());
            assertEquals(started.desired().desiredVersion(), afterBarrier.desired().desiredVersion());
            assertEquals(started.desired().updateStrategy(), afterBarrier.desired().updateStrategy());
            assertEquals(started.generation() + 1, afterBarrier.generation(),
                    "half-open: admission closes at G and reopens at G+1, so the barrier must advance it");
            assertEquals(afterBarrier.generation(), afterBarrier.desired().generation(),
                    "the desired state is re-stamped with the generation it now belongs to");
        }
    }

    // ================================================ The lifecycle levels themselves (ADR 0038 D5)

    /**
     * Only {@code RUNNING} selects a version, and the rule is stated over the whole enum rather than
     * over the two members that happened to exist first. A level added later without an opinion here
     * would otherwise inherit "anything goes" by omission.
     */
    @Test
    final void everyDesiredLevelDefinesWhetherItSelectsAVersionAndOnlyRunningDoes() {
        for (DeploymentRegistry.DesiredKind kind : DeploymentRegistry.DesiredKind.values()) {
            assertEquals(kind == DeploymentRegistry.DesiredKind.RUNNING, kind.carriesVersion(), kind.toString());
            if (kind.carriesVersion()) {
                assertThrows(IllegalArgumentException.class,
                        () -> new DeploymentRegistry.Desired(kind, null, null, 0), kind.toString());
                assertThrows(IllegalArgumentException.class, () -> new DeploymentRegistry.Desired(
                        kind, 1L, null, 0), kind + " needs a strategy as well as a version");
            } else {
                assertDoesNotThrow(() -> new DeploymentRegistry.Desired(kind, null, null, 0), kind.toString());
                assertThrows(IllegalArgumentException.class, () -> new DeploymentRegistry.Desired(
                        kind, 1L, DeploymentRegistry.UpdateStrategy.STOP_FIRST, 0), kind.toString());
            }
        }
    }

    /**
     * The restriction lattice is what decides precedence, so it has to be a genuine total order with
     * no ties: two levels sharing a rank would make "which of these two commands governs" undecidable
     * exactly when two operators act at once, which is the only time the question is ever asked.
     */
    @Test
    final void theRestrictionLatticeIsATotalOrderRunningThroughRemoved() {
        List<DeploymentRegistry.DesiredKind> ascending = List.of(
                DeploymentRegistry.DesiredKind.RUNNING, DeploymentRegistry.DesiredKind.PAUSED,
                DeploymentRegistry.DesiredKind.DRAINED, DeploymentRegistry.DesiredKind.STOPPED,
                DeploymentRegistry.DesiredKind.REMOVED);
        assertEquals(EnumSet.allOf(DeploymentRegistry.DesiredKind.class), EnumSet.copyOf(ascending),
                "a level added without a place in the order would rank against everything by accident");
        Set<Integer> ranks = new LinkedHashSet<>();
        for (DeploymentRegistry.DesiredKind kind : ascending) assertTrue(ranks.add(kind.restriction()), kind.toString());
        for (int i = 1; i < ascending.size(); i++) {
            assertTrue(ascending.get(i).atLeastAsRestrictiveAs(ascending.get(i - 1)));
            assertFalse(ascending.get(i - 1).atLeastAsRestrictiveAs(ascending.get(i)));
        }
        assertTrue(DeploymentRegistry.DesiredKind.STOPPED.restriction() > LifecycleCommand.CANCEL_PRECEDENCE
                        && LifecycleCommand.CANCEL_PRECEDENCE > DeploymentRegistry.DesiredKind.DRAINED.restriction(),
                "Stop outranks Cancel outranks Drain, and the scale must leave room to say so");
    }

    /**
     * A paused deployment has not released its activation, and neither has a drained one, so both must
     * name the version they are holding. Reporting either without one would lose which graph an
     * operator is about to resume, which is the single fact a resume depends on.
     */
    @Test
    final void aHeldDeploymentReportsTheVersionItIsStillBoundTo() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            DeploymentRegistry.Record made = create(registry, clock, "tenant", "create", "v1");
            DeploymentRegistry.Record leased = registry.acquire("owner", Duration.ofSeconds(30),
                    command(made, "acquire", 'b')).toCompletableFuture().join();

            for (DeploymentRegistry.ObservedKind held : List.of(DeploymentRegistry.ObservedKind.PAUSED,
                    DeploymentRegistry.ObservedKind.DRAINED)) {
                assertThrows(IllegalArgumentException.class,
                        () -> new DeploymentRegistry.Observation(held, null, 0, clock.instant()), held.toString());
            }

            DeploymentRegistry.Record observed = registry.observe(new DeploymentRegistry.Observation(
                            DeploymentRegistry.ObservedKind.PAUSED, 1L, 0, clock.instant()),
                    leased.lease(), command(leased, "observe", 'c')).toCompletableFuture().join();
            assertEquals(DeploymentRegistry.ObservedKind.PAUSED, observed.observed().state());
            assertEquals(1L, observed.observed().activeVersion());
        }
    }

    /**
     * A caller that must stop working before it can be fenced needs to know how far its own clock may
     * be trusted against the authority's, and the allowance has to be strictly inside the lease so
     * that renewing at {@code expiresAt - maxClockSkew} is still a renewal rather than an expiry.
     */
    @Test
    final void theAdapterPublishesASkewAllowanceStrictlyInsideItsOwnLeaseBound() {
        MutableClock clock = new MutableClock(START);
        try (DeploymentRegistry registry = createRegistry(clock)) {
            Duration skew = registry.limits().maxClockSkew();
            assertNotNull(skew);
            assertFalse(skew.isNegative(), "a negative allowance would mean the caller's clock leads by fiat");
            assertTrue(skew.compareTo(registry.limits().maximumLeaseTtl()) < 0,
                    "an allowance at or beyond the maximum lease makes every lease unrenewable in principle");
        }
    }

    // ================================================ The eight-command matrix (ADR 0038 D5, D7, D11)

    /**
     * One row per command, shared by every assertion below and reusable by the coordinator suite.
     *
     * @param command the command value under test.
     * @param kind the closed-vocabulary kind it must report.
     * @param level the level it converges to, or {@code null} when it is a barrier.
     * @param precedence its rank on the restriction scale.
     * @return the eight rows, in precedence-independent declaration order.
     */
    public record LifecycleCommandCell(LifecycleCommand command, LifecycleCommand.Kind kind,
                                       DeploymentRegistry.DesiredKind level, int precedence) {}

    /**
     * The eight commands, once.
     * @return one cell per member of {@link LifecycleCommand.Kind}.
     */
    public static List<LifecycleCommandCell> lifecycleCommandMatrix() {
        return List.of(
                new LifecycleCommandCell(new LifecycleCommand.Start("k-start", 1,
                        DeploymentRegistry.UpdateStrategy.STOP_FIRST), LifecycleCommand.Kind.START,
                        DeploymentRegistry.DesiredKind.RUNNING, DeploymentRegistry.DesiredKind.RUNNING.restriction()),
                new LifecycleCommandCell(new LifecycleCommand.Pause("k-pause", "budget freeze"),
                        LifecycleCommand.Kind.PAUSE, DeploymentRegistry.DesiredKind.PAUSED,
                        DeploymentRegistry.DesiredKind.PAUSED.restriction()),
                new LifecycleCommandCell(new LifecycleCommand.Resume("k-resume"), LifecycleCommand.Kind.RESUME,
                        DeploymentRegistry.DesiredKind.RUNNING, DeploymentRegistry.DesiredKind.RUNNING.restriction()),
                new LifecycleCommandCell(new LifecycleCommand.Drain("k-drain", Duration.ofMinutes(2)),
                        LifecycleCommand.Kind.DRAIN, DeploymentRegistry.DesiredKind.DRAINED,
                        DeploymentRegistry.DesiredKind.DRAINED.restriction()),
                new LifecycleCommandCell(new LifecycleCommand.Stop("k-stop", "maintenance window"),
                        LifecycleCommand.Kind.STOP, DeploymentRegistry.DesiredKind.STOPPED,
                        DeploymentRegistry.DesiredKind.STOPPED.restriction()),
                new LifecycleCommandCell(new LifecycleCommand.Undeploy("k-undeploy",
                        LifecycleCommand.Undeploy.Disposition.DRAIN_FIRST, "decommissioned"),
                        LifecycleCommand.Kind.UNDEPLOY, DeploymentRegistry.DesiredKind.REMOVED,
                        DeploymentRegistry.DesiredKind.REMOVED.restriction()),
                new LifecycleCommandCell(new LifecycleCommand.Cancel("k-cancel", "wrong input batch"),
                        LifecycleCommand.Kind.CANCEL, null, LifecycleCommand.CANCEL_PRECEDENCE),
                new LifecycleCommandCell(new LifecycleCommand.Restart("k-restart"),
                        LifecycleCommand.Kind.RESTART, null, LifecycleCommand.CANCEL_PRECEDENCE));
    }

    /**
     * Every command has exactly one nature and reports it consistently. The matrix is asserted to be
     * complete against the enum, because a command added to the hierarchy and forgotten here is
     * precisely the kind of gap a scenario matrix exists to prevent.
     */
    @Test
    final void everyLifecycleCommandDeclaresOneNatureOneLevelAndOneRank() {
        List<LifecycleCommandCell> matrix = lifecycleCommandMatrix();
        assertEquals(EnumSet.allOf(LifecycleCommand.Kind.class),
                EnumSet.copyOf(matrix.stream().map(LifecycleCommandCell::kind).toList()),
                "every command kind needs a row; a missing one is a command with no scenario");
        assertEquals(matrix.size(), LifecycleCommand.Kind.values().length, "one row per kind, no duplicates");

        for (LifecycleCommandCell cell : matrix) {
            LifecycleCommand command = cell.command();
            assertEquals(cell.kind(), command.kind());
            assertEquals(cell.level() == null, command.barrier(), cell.kind().toString());
            assertEquals(java.util.Optional.ofNullable(cell.level()), command.targetLevel(), cell.kind().toString());
            assertEquals(cell.precedence(), command.precedence(), cell.kind().toString());
            assertFalse(command.idempotencyKey().isBlank());
            assertNotNull(command.canonicalBody());
        }
    }

    /**
     * {@code Shutdown > Stop > Cancel > Drain > Pause}, read off the one published scale. Service
     * shutdown is deliberately absent from the assertion because it is deliberately absent from the
     * hierarchy: it is service-scoped with its own epoch and is not a deployment generation.
     */
    @Test
    final void theCommandPrecedenceIsStopThenCancelThenDrainThenPause() {
        int stop = rank(LifecycleCommand.Kind.STOP);
        int cancel = rank(LifecycleCommand.Kind.CANCEL);
        int drain = rank(LifecycleCommand.Kind.DRAIN);
        int pause = rank(LifecycleCommand.Kind.PAUSE);
        assertTrue(stop > cancel && cancel > drain && drain > pause,
                "precedence read " + stop + " > " + cancel + " > " + drain + " > " + pause);
        assertTrue(rank(LifecycleCommand.Kind.UNDEPLOY) > stop, "removal is absorbing and outranks a stop");
        assertTrue(rank(LifecycleCommand.Kind.RESTART) > rank(LifecycleCommand.Kind.START),
                "a barrier over a running deployment outranks the command that merely keeps it running");
    }

    private static int rank(LifecycleCommand.Kind kind) {
        return lifecycleCommandMatrix().stream().filter(cell -> cell.kind() == kind)
                .findFirst().orElseThrow().command().precedence();
    }

    /**
     * The digest an idempotency key protects covers the generation the command was decided against, so
     * the same words retried after the lifecycle moved are a different decision and must not replay
     * the earlier one's recorded outcome. Asserted for all eight rather than for a sampled one,
     * because a command whose canonical body ignored a component would be invisible in a sample.
     */
    @Test
    final void aCommandsCanonicalFormSeparatesDecisionsTakenAtDifferentGenerations() {
        Set<String> distinct = new LinkedHashSet<>();
        for (LifecycleCommandCell cell : lifecycleCommandMatrix()) {
            String atFour = LifecycleCommand.canonicalForm(cell.command(), GenerationExpectation.exactly(4));
            String atFive = LifecycleCommand.canonicalForm(cell.command(), GenerationExpectation.exactly(5));
            String unpinned = LifecycleCommand.canonicalForm(cell.command(), GenerationExpectation.any());
            assertNotEquals(atFour, atFive, cell.kind().toString());
            assertNotEquals(atFour, unpinned, cell.kind().toString());
            assertEquals(atFour, LifecycleCommand.canonicalForm(cell.command(), GenerationExpectation.exactly(4)),
                    "the canonical form must be deterministic, or every retry is a conflict");
            assertTrue(distinct.add(atFour), "two different commands must not digest to the same input: " + cell.kind());
        }
        assertEquals(LifecycleCommand.Kind.values().length, distinct.size());

        // The key names the ledger slot and is deliberately outside the digest: two clients using
        // different keys for the same decision must still produce the same body to compare against.
        assertEquals(LifecycleCommand.canonicalForm(new LifecycleCommand.Restart("one"), GenerationExpectation.exactly(1)),
                LifecycleCommand.canonicalForm(new LifecycleCommand.Restart("two"), GenerationExpectation.exactly(1)));
    }

    /**
     * The outcome hierarchy is the coordinator's return type in wave 2, and the invariants asserted
     * here are the ones a coordinator could otherwise violate while still compiling: a generation
     * advance that is not one step, a "stale" answer that is not stale, and a replay chain that grows
     * without bound across retries.
     */
    @Test
    final void anOutcomeCannotBeConstructedIntoAShapeThatMisreportsWhatHappened() {
        DeploymentCommandOutcome accepted = new DeploymentCommandOutcome.Accepted("cmd-1", 4, 5);
        assertEquals("cmd-1", accepted.commandId());
        assertThrows(IllegalArgumentException.class, () -> new DeploymentCommandOutcome.Accepted("cmd-1", 4, 6),
                "a two-step advance would mean a decision was applied that nothing recorded");
        assertThrows(IllegalArgumentException.class, () -> new DeploymentCommandOutcome.StaleGeneration(4, 4),
                "a matching generation is not stale");

        DeploymentCommandOutcome replayed = new DeploymentCommandOutcome.Replayed(accepted);
        assertEquals("cmd-1", replayed.commandId(), "a replay reports the original decision, not a new one");
        assertThrows(IllegalArgumentException.class, () -> new DeploymentCommandOutcome.Replayed(replayed));

        // A classifier, never a message: this value reaches operator surfaces and logs.
        assertDoesNotThrow(() -> new DeploymentCommandOutcome.Failed("java.util.concurrent.TimeoutException"));
        assertThrows(IllegalArgumentException.class,
                () -> new DeploymentCommandOutcome.Failed("failed to start: secret=hunter2"));

        // Removal succeeding and a command arriving after removal are different answers on purpose.
        assertNotEquals(new DeploymentCommandOutcome.Terminal("cmd-2", 7),
                new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.Tombstoned));
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
