package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciliation of commands a crash or a takeover left half-applied.
 *
 * <h2>How a crash is staged, and why this shape is the honest one</h2>
 * <p>A command submitted for a deployment whose runtime this process does not host records durable
 * intent and performs no effect. That is not an approximation of issue 91 criterion 2's window — it
 * <em>is</em> that window, produced by the same code path a crash would leave behind, and it is
 * deterministic rather than timing-dependent. What it cannot show on its own is a process boundary,
 * so the cross-process half of the same criterion is proved separately against a durable adapter
 * with a real second JVM.</p>
 */
class DeploymentReconcilerTest {

    private static final String TENANT = "acme";

    /**
     * Issue 91 criterion 2: a crash between intent persistence and runtime action is resumed, and the
     * action is performed once.
     *
     * <p>The second sweep is the assertion that matters. Once evidence names the generation, the
     * intent stops being outstanding, so no later sweep — by this owner or the next one — considers
     * the deployment at all. That is what closes the window rather than merely narrowing it.</p>
     */
    @Test
    void anIntentRecordedWithoutItsEffectIsResumedOnceAndThenLeftAlone() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.unhostedDeployment(TENANT, "crashed");
        fixture.coordinator("owner-dead").submit(TENANT, id,
                new LifecycleCommand.Start("s", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));

        DeploymentRegistry.Record afterCrash = fixture.record(TENANT, id);
        assertEquals(1, afterCrash.generation(), "the decision is durable");
        assertEquals(0, afterCrash.observed().observedGeneration(), "and its effect is not");

        // The dead owner's lease outlives it: a kill releases nothing, so the deployment stays
        // excluded until the authority's own clock says the window has lapsed. That is not a delay to
        // be engineered away -- it is the only thing that makes "one current owner" decidable.
        var target = fixture.host(id);
        var reconciler = fixture.reconciler("owner-live", TENANT);
        assertInstanceOf(DeploymentReconcileOutcome.NotOwned.class, reconciler.sweepOnce().get(0),
                "before the crashed owner's lease lapses, nobody else may act");
        fixture.clock.advance(CoordinatorFixture.LEASE_TTL.plusSeconds(1));

        List<DeploymentReconcileOutcome> first = reconciler.sweepOnce();
        assertEquals(1, first.size(), "exactly one deployment was outstanding");
        var reconciled = assertInstanceOf(DeploymentReconcileOutcome.Reconciled.class, first.get(0));
        assertEquals(id, reconciled.deploymentId());
        assertEquals(1, reconciled.generation());
        assertEquals(List.of("start:1@1"), target.effects(), "the interrupted action was carried out");

        assertEquals(List.of(), reconciler.sweepOnce(),
                "evidence now names the generation, so the intent is no longer outstanding");
        assertEquals(List.of("start:1@1"), target.effects(), "and nothing was done a second time");
        assertEquals(1, fixture.record(TENANT, id).observed().observedGeneration());
    }

    /**
     * The other half of criterion 2: an effect that <em>had</em> already landed before the crash is
     * re-driven and still happens once, because the port is idempotent for a generation.
     *
     * <p>This is the case a reconciler cannot distinguish from the one above, and asking it to would
     * require a third durable write between the intent and the effect — which has the same crash
     * window one level down. The port closes it instead, and this test is where that guarantee is
     * exercised rather than assumed: the runtime is called again, and the call does nothing.</p>
     */
    @Test
    void anEffectThatAlreadyLandedBeforeTheCrashIsNotPerformedTwice() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.unhostedDeployment(TENANT, "half-applied");
        fixture.coordinator("owner-dead").submit(TENANT, id,
                new LifecycleCommand.Start("s", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));

        // The dead owner had got as far as the effect and died before publishing evidence.
        var target = fixture.host(id);
        target.start(1, 1).toCompletableFuture().join();
        assertEquals(List.of("start:1@1"), target.effects());
        fixture.clock.advance(CoordinatorFixture.LEASE_TTL.plusSeconds(1));

        var outcomes = fixture.reconciler("owner-live", TENANT).sweepOnce();

        assertInstanceOf(DeploymentReconcileOutcome.Reconciled.class, outcomes.get(0));
        assertEquals(List.of("start:1@1", "start:1@1"), target.calls(),
                "the reconciler did re-drive the interrupted command");
        assertEquals(List.of("start:1@1"), target.effects(),
                "and the runtime performed it once, which is what per-generation idempotence buys");
    }

    /**
     * Issue 91 criterion 2, the "one current owner" half: two reconcilers sweeping the same
     * outstanding intent produce exactly one actor.
     */
    @Test
    void twoReconcilersSweepingOneOutstandingIntentProduceExactlyOneActor() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.unhostedDeployment(TENANT, "contended");
        fixture.coordinator("owner-dead").submit(TENANT, id,
                new LifecycleCommand.Start("s", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));
        var target = fixture.host(id);
        fixture.clock.advance(CoordinatorFixture.LEASE_TTL.plusSeconds(1));

        var winner = fixture.reconciler("owner-b", TENANT).sweepOnce();
        var loser = fixture.reconciler("owner-c", TENANT).sweepOnce();

        assertInstanceOf(DeploymentReconcileOutcome.Reconciled.class, winner.get(0));
        assertEquals(List.of(), loser,
                "the first sweep published evidence, so the second finds nothing outstanding at all");
        assertEquals(List.of("start:1@1"), target.effects());

        // Now make an intent outstanding again while owner-b's lease is still live: a command whose
        // effect fails records the decision, publishes no evidence, and leaves ownership where it is.
        target.failNext(new IllegalStateException("the runtime refused"));
        fixture.coordinator("owner-b").submit(TENANT, id,
                new LifecycleCommand.Pause("p", "second decision"), GenerationExpectation.any());
        assertEquals(1, fixture.record(TENANT, id).observed().observedGeneration(),
                "evidence is behind intent again");

        var excluded = fixture.reconciler("owner-c", TENANT).sweepOnce();
        var notOwned = assertInstanceOf(DeploymentReconcileOutcome.NotOwned.class, excluded.get(0));
        assertEquals("owner-b", notOwned.holder(),
                "a non-owner names the holder instead of acting, and writes nothing");
        assertEquals(2, fixture.record(TENANT, id).generation(), "and the excluded sweep changed nothing");
    }

    /**
     * Issue 91 criterion 4: once ownership moves, the superseded owner can no longer publish lifecycle
     * state, and the new owner's fence is strictly greater.
     */
    @Test
    void afterTakeoverTheSupersededOwnersFenceIsRefusedForever() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.unhostedDeployment(TENANT, "takeover");
        fixture.coordinator("owner-old").submit(TENANT, id,
                new LifecycleCommand.Start("s", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));
        DeploymentRegistry.Lease stale = fixture.record(TENANT, id).lease();
        assertEquals("owner-old", stale.owner());

        fixture.clock.advance(CoordinatorFixture.LEASE_TTL.plusSeconds(1));
        fixture.host(id);
        var outcomes = fixture.reconciler("owner-new", TENANT).sweepOnce();

        var reconciled = assertInstanceOf(DeploymentReconcileOutcome.Reconciled.class, outcomes.get(0));
        assertTrue(reconciled.fence() > stale.fence(),
                "the fence orders successive holders, so a takeover strictly advances it");
        assertEquals("owner-new", fixture.record(TENANT, id).lease().owner());

        var refused = assertInstanceOf(DeploymentRegistry.RegistryException.class,
                assertThrowsCause(() -> fixture.registry.observe(
                        new DeploymentRegistry.Observation(ObservedKind.READY, 1L, 1,
                                fixture.record(TENANT, id).updatedAt()),
                        stale,
                        new DeploymentRegistry.Command(TENANT, id, "stale-evidence", "c".repeat(64),
                                ai.ravenroot.api.persistence.RevisionExpectation.exactly(
                                        fixture.record(TENANT, id).revision()),
                                GenerationExpectation.any()))
                        .toCompletableFuture().join()));
        assertInstanceOf(DeploymentRegistry.FailureReason.Fenced.class, refused.reason(),
                "a write presented with a superseded token is refused whether or not its holder still "
                        + "believes its lease is live");
        assertNotEquals(stale.fence(), fixture.record(TENANT, id).lease().fence());
    }

    /** A sweep reports, and does not act on, a deployment whose runtime lives on another host. */
    @Test
    void anIntentForARuntimeThisProcessDoesNotHostIsDeferredRatherThanFailed() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.unhostedDeployment(TENANT, "elsewhere");
        fixture.coordinator("owner-a").submit(TENANT, id,
                new LifecycleCommand.Start("s", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));

        var outcomes = fixture.reconciler("owner-a", TENANT).sweepOnce();

        var deferred = assertInstanceOf(DeploymentReconcileOutcome.Deferred.class, outcomes.get(0));
        assertEquals("no deployment runtime hosted here", deferred.reason());
        assertEquals(1, fixture.record(TENANT, id).generation(), "the intent still stands");
        assertEquals(0, fixture.record(TENANT, id).observed().observedGeneration(),
                "and is still outstanding, so the host that can reach the runtime will finish it");
    }

    /**
     * Issue 91 criterion 7, tenant isolation: a reconciler configured for one tenant never touches
     * another's deployments, even when they are outstanding in the same store.
     */
    @Test
    void aReconcilerNeverTouchesATenantItWasNotConfiguredWith() {
        var fixture = new CoordinatorFixture();
        DeploymentId mine = fixture.unhostedDeployment(TENANT, "mine");
        DeploymentId theirs = fixture.unhostedDeployment("other-tenant", "theirs");
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, mine, new LifecycleCommand.Start("a", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));
        coordinator.submit("other-tenant", theirs, new LifecycleCommand.Start("b", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));
        var mineTarget = fixture.host(mine);
        var theirsTarget = fixture.host(theirs);

        var outcomes = fixture.reconciler("owner-a", TENANT).sweepOnce();

        assertEquals(1, outcomes.size());
        assertEquals(mine, outcomes.get(0).deploymentId());
        assertEquals(List.of("start:1@1"), mineTarget.effects());
        assertEquals(List.of(), theirsTarget.effects(),
                "a tenant this reconciler does not serve is not swept, even though its runtime is "
                        + "reachable from this process and its intent is outstanding");
        assertEquals(List.of(), fixture.reconciler("owner-a", TENANT).sweepOnce("other-tenant"),
                "and asking for it by name answers 'not mine' rather than sweeping it anyway");
    }

    /**
     * A recovered drain is bounded by this service's configured bound, because the operator's own
     * bound travels on the command and the durable intent has nowhere to carry it.
     */
    @Test
    void aRecoveredDrainUsesTheServicesBoundBecauseTheIntentCarriesNone() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.unhostedDeployment(TENANT, "drain");
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));
        coordinator.submit(TENANT, id, new LifecycleCommand.Drain("d", java.time.Duration.ofHours(4)),
                GenerationExpectation.exactly(1));

        var target = fixture.host(id);
        target.start(1, 1).toCompletableFuture().join();
        var outcomes = fixture.reconciler("owner-a", TENANT).sweepOnce();

        assertInstanceOf(DeploymentReconcileOutcome.Reconciled.class, outcomes.get(0));
        assertEquals(List.of("start:1@1", "closeAdmission@2", "drain@2"), target.effects(),
                "recovery converged to the drained level; the four-hour bound the operator wrote is a "
                        + "property of the command and is replaced by the service's own recovery bound");
    }

    private static DeploymentRegistry.RegistryException assertThrowsCause(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected the registry to refuse this write");
        } catch (java.util.concurrent.CompletionException wrapped) {
            return assertInstanceOf(DeploymentRegistry.RegistryException.class, wrapped.getCause());
        }
    }
}
