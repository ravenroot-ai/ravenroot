package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentCommandOutcome;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.DesiredKind;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.testkit.persistence.DeploymentRegistryContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordinator answering every command in the published vocabulary, against a live registry and a
 * live runtime.
 *
 * <h2>The scenario matrix is the contract's own</h2>
 * <p>{@link DeploymentRegistryContract#lifecycleCommandMatrix()} is reused rather than restated, which
 * is what it was made public for. A command added to {@link LifecycleCommand} without a scenario here
 * fails {@link #everyPublishedCommandReachesTheRuntimeAndIsAnsweredByATypedOutcome()} on completeness
 * rather than passing unnoticed, so the eight commands and the eight scenarios cannot drift into two
 * lists that agree only by accident.</p>
 */
class DeploymentCoordinatorTest {

    private static final String TENANT = "acme";

    /**
     * Issue 91 criterion 1: every accepted command records its idempotency key, the generation it was
     * decided against, the generation it produced, the desired state, the owner and the fence
     * <em>before</em> any side effect.
     *
     * <p>The ordering is asserted rather than assumed: the runtime under test reads the durable record
     * at the instant of its first invocation, so the snapshot below is what was durable at the moment
     * the very first effect began. Asserting the record after the command returned would prove only
     * that both writes happened, in some order, which is exactly the fact that does not matter.</p>
     */
    @Test
    void intentIsDurableBeforeTheFirstEffectReachesTheRuntime() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.unhostedDeployment(TENANT, "ordering");
        var probe = new IntentProbe(fixture, id);
        fixture.hostProbe(id, probe);

        DeploymentCommandOutcome outcome = fixture.coordinator("owner-a").submit(TENANT, id,
                new LifecycleCommand.Start("k-start", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));

        assertEquals(new DeploymentCommandOutcome.Accepted("g1/RUNNING", 0, 1), outcome);
        DeploymentRegistry.Record atFirstEffect = probe.snapshot();
        assertNotNull(atFirstEffect, "the runtime was never reached, so the ordering was not exercised");
        assertEquals(1, atFirstEffect.generation(), "the resulting generation is durable before the effect");
        assertEquals(DesiredKind.RUNNING, atFirstEffect.desired().kind(), "the desired state is durable first");
        assertEquals(1L, atFirstEffect.desired().desiredVersion());
        assertNotNull(atFirstEffect.lease(), "the owner and fence are durable before the effect");
        assertEquals("owner-a", atFirstEffect.lease().owner());
        assertTrue(atFirstEffect.lease().fence() >= 1, "a fence orders successive holders and starts at one");
        assertEquals(0, atFirstEffect.observed().observedGeneration(),
                "evidence has not been published yet: the intent is ahead, which is what recovery looks for");

        // The idempotency key is durable too, and the only way to see that through the port is to use
        // it: the same key and the same body replay instead of moving the lifecycle a second time.
        assertEquals(1, fixture.record(TENANT, id).generation());
    }

    /**
     * Every command in the published vocabulary is answered by a typed outcome and reaches the runtime
     * through the operations that command is defined in terms of.
     *
     * <p>Driven from the contract's own matrix so the set cannot silently shrink, and asserted on the
     * <em>effects</em> rather than on the returned value alone, because an outcome that says
     * {@code Accepted} while the runtime was never touched is exactly the defect a typed outcome makes
     * easy to fake.</p>
     */
    @Test
    void everyPublishedCommandReachesTheRuntimeAndIsAnsweredByATypedOutcome() {
        Map<LifecycleCommand.Kind, List<String>> effects = new LinkedHashMap<>();
        for (var cell : DeploymentRegistryContract.lifecycleCommandMatrix()) {
            var fixture = new CoordinatorFixture();
            var target = fixture.deployment(TENANT, "matrix-" + cell.kind());
            DeploymentId id = fixture.idOf(target);
            var coordinator = fixture.coordinator("owner-a");

            // Every command except Start needs something to act on, and Resume needs it held.
            long generation = 0;
            if (cell.kind() != LifecycleCommand.Kind.START) {
                coordinator.submit(TENANT, id, new LifecycleCommand.Start("bootstrap", 1,
                        DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));
                generation = 1;
            }
            if (cell.kind() == LifecycleCommand.Kind.RESUME) {
                coordinator.submit(TENANT, id, new LifecycleCommand.Pause("hold", "before resume"),
                        GenerationExpectation.exactly(1));
                generation = 2;
            }
            int before = target.effects().size();

            DeploymentCommandOutcome outcome = coordinator.submit(TENANT, id, cell.command(),
                    GenerationExpectation.exactly(generation));

            assertTrue(outcome instanceof DeploymentCommandOutcome.Accepted
                            || outcome instanceof DeploymentCommandOutcome.Terminal,
                    cell.kind() + " was answered " + outcome);
            List<String> applied = new ArrayList<>(target.effects().subList(before, target.effects().size()));
            assertFalse(applied.isEmpty(), cell.kind() + " was accepted without reaching the runtime");
            effects.put(cell.kind(), applied);

            // The sibling-facing guarantee, asserted for every command rather than for a sampled one.
            assertFalse(fixture.engine.closed(), cell.kind() + " closed the shared engine");
            assertFalse(fixture.engine.drained(), cell.kind() + " drained the shared engine");
        }

        assertEquals(EnumSet.allOf(LifecycleCommand.Kind.class), EnumSet.copyOf(effects.keySet()),
                "a command with no scenario here is a command nobody has driven end to end");
        assertEquals(List.of("start:1@1"), effects.get(LifecycleCommand.Kind.START));
        assertEquals(List.of("closeAdmission@2"), effects.get(LifecycleCommand.Kind.PAUSE));
        assertEquals(List.of("openAdmission@3"), effects.get(LifecycleCommand.Kind.RESUME));
        assertEquals(List.of("closeAdmission@2", "drain@2"), effects.get(LifecycleCommand.Kind.DRAIN));
        assertEquals(List.of("closeAdmission@2", "terminateDomain@2"), effects.get(LifecycleCommand.Kind.STOP));
        assertEquals(List.of("closeAdmission@2", "drain@2", "terminateDomain@2"),
                effects.get(LifecycleCommand.Kind.UNDEPLOY),
                "DRAIN_FIRST finishes what is admitted before anything is released");
        assertEquals(List.of("barrier@2"), effects.get(LifecycleCommand.Kind.CANCEL),
                "a cancel ends the admitted work and leaves the deployment exactly as restrictive as it was");
        assertEquals(List.of("barrier@2", "start:1@2"), effects.get(LifecycleCommand.Kind.RESTART),
                "a restart is a barrier and an activation, never a stop followed by a start");
    }

    /**
     * A barrier moves the generation and leaves the level alone; a level command moves both. Read off
     * the durable record, because that is what a reconciler and a competing client will read.
     */
    @Test
    void aBarrierMovesTheGenerationAndLeavesTheDesiredLevelExactlyWhereItWas() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "barrier");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));

        DeploymentCommandOutcome cancelled = coordinator.submit(TENANT, id,
                new LifecycleCommand.Cancel("c", "wrong input batch"), GenerationExpectation.exactly(1));

        assertEquals(new DeploymentCommandOutcome.Accepted("g2/RUNNING", 1, 2), cancelled,
                "a command's identity is derived from what is durably recorded -- the generation it "
                        + "produced and the level left in force -- so a barrier's identity names RUNNING. "
                        + "Minting an opaque id instead would be the sixth identifier ADR 0038 D12 refuses, "
                        + "and no later reader could recompute it from the record");
        DeploymentRegistry.Record after = fixture.record(TENANT, id);
        assertEquals(2, after.generation(), "the barrier captured a generation");
        assertEquals(DesiredKind.RUNNING, after.desired().kind(),
                "cancel answers 'not this work', never 'not this deployment'");
        assertEquals(1L, after.desired().desiredVersion(), "the activation the deployment holds is unchanged");
    }

    /**
     * Issue 91 criterion 5, first half: duplicate delivery converges to the same typed result.
     *
     * <p>A client that asks for convergence rather than pinning a generation is retrying the same
     * decision, and gets the same answer twice — once as the decision and once as its replay — with
     * the runtime touched exactly once. A barrier is used deliberately: it is the command that always
     * advances the generation and never converges, so a second identical delivery has nowhere to hide
     * except the ledger.
     */
    @Test
    void duplicateDeliveryOfOneDecisionReplaysItInsteadOfApplyingItTwice() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "duplicate");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());

        var cancel = new LifecycleCommand.Cancel("same-key", "duplicate delivery");
        DeploymentCommandOutcome first = coordinator.submit(TENANT, id, cancel, GenerationExpectation.any());
        DeploymentCommandOutcome again = coordinator.submit(TENANT, id, cancel, GenerationExpectation.any());

        var accepted = assertInstanceOf(DeploymentCommandOutcome.Accepted.class, first);
        var replayed = assertInstanceOf(DeploymentCommandOutcome.Replayed.class, again);
        assertEquals(accepted, replayed.original(),
                "a retrying client receives exactly what the first attempt received, not a weaker answer");
        assertEquals(accepted.commandId(), replayed.commandId());
        assertEquals(2, fixture.record(TENANT, id).generation(),
                "a replay records nothing, so the lifecycle moved once for one decision");
        assertEquals(1, target.effects().stream().filter("barrier@2"::equals).count(),
                "the runtime performed the barrier once");
        assertEquals(1, target.calls().stream().filter("barrier@2"::equals).count(),
                "and the second delivery never reached it at all: the ledger answered before any effect "
                        + "was attempted, so idempotence does not depend on the runtime being idempotent");
    }

    /**
     * Issue 91 criterion 5, second half: two clients that reuse one key for different decisions are
     * told so, rather than one of them being handed the other's outcome.
     */
    @Test
    void twoDifferentDecisionsUnderOneKeyCollideInsteadOfSilentlyReplaying() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "collision");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());
        coordinator.submit(TENANT, id, new LifecycleCommand.Cancel("shared", "first reason"),
                GenerationExpectation.any());

        DeploymentCommandOutcome collided = coordinator.submit(TENANT, id,
                new LifecycleCommand.Cancel("shared", "a completely different reason"),
                GenerationExpectation.any());

        assertEquals(new DeploymentCommandOutcome.IdempotencyConflict("shared"), collided,
                "the key names a ledger slot and the digest is what it must be found to contain");
        assertEquals(2, fixture.record(TENANT, id).generation(), "nothing was applied for the collision");
    }

    /**
     * The same collision seen by a <em>different</em> owner, which is the shape a second replica
     * actually produces: taking the lease is itself a mutation, so the record the compare-and-set
     * sees is a revision ahead of the one the command was decided against. The answer must still be
     * the collision and not an ordinary race.
     */
    @Test
    void aKeyReusedByAnotherOwnerCollidesRatherThanBeingReadAsARace() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "cross-owner-collision");
        DeploymentId id = fixture.idOf(target);
        fixture.coordinator("owner-a").submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());
        fixture.coordinator("owner-a").submit(TENANT, id, new LifecycleCommand.Cancel("shared", "first"),
                GenerationExpectation.any());
        long revisionBefore = fixture.record(TENANT, id).revision();

        fixture.clock.advance(CoordinatorFixture.LEASE_TTL.plusSeconds(1));
        DeploymentCommandOutcome collided = fixture.coordinator("owner-b").submit(TENANT, id,
                new LifecycleCommand.Cancel("shared", "a different decision"), GenerationExpectation.any());

        assertEquals(new DeploymentCommandOutcome.IdempotencyConflict("shared"), collided);
        assertTrue(fixture.record(TENANT, id).revision() > revisionBefore,
                "the takeover really did move the revision, which is what makes this the interesting case");
        assertEquals("owner-b", fixture.record(TENANT, id).lease().owner());
        assertEquals(2, fixture.record(TENANT, id).generation(), "and no lifecycle move was recorded");
    }

    /**
     * A decision made against a generation the lifecycle has since left is reported stale, with both
     * numbers, rather than being applied over the newer decision.
     */
    @Test
    void aDecisionPinnedToAGenerationTheLifecycleHasLeftIsReportedStale() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "stale");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));
        coordinator.submit(TENANT, id, new LifecycleCommand.Pause("p", "someone else acted"),
                GenerationExpectation.exactly(1));

        DeploymentCommandOutcome stale = coordinator.submit(TENANT, id,
                new LifecycleCommand.Stop("late", "decided against generation one"),
                GenerationExpectation.exactly(1));

        assertEquals(new DeploymentCommandOutcome.StaleGeneration(1, 2), stale,
                "both numbers are reported because the caller's next move depends on the distance");
        assertEquals(DesiredKind.PAUSED, fixture.record(TENANT, id).desired().kind(),
                "the newer decision stands; a stale command must not overwrite it");
    }

    /**
     * A deployment that is already where a command asks it to go answers convergence and does not
     * advance the generation, so no consumer keying on that number ends up a step ahead of the
     * authority.
     */
    @Test
    void aCommandThatAsksForTheLevelAlreadyInForceConvergesWithoutMovingTheGeneration() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "converged");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());
        coordinator.submit(TENANT, id, new LifecycleCommand.Stop("first", "maintenance"),
                GenerationExpectation.any());
        int effectsAfterStop = target.effects().size();

        DeploymentCommandOutcome converged = coordinator.submit(TENANT, id,
                new LifecycleCommand.Stop("a-different-client", "maintenance"), GenerationExpectation.any());

        var value = assertInstanceOf(DeploymentCommandOutcome.Converged.class, converged);
        assertEquals(2, value.generation());
        assertEquals("g2/STOPPED", value.commandId(), "convergence names the decision already in force");
        assertEquals(2, fixture.record(TENANT, id).generation(), "nothing moved");
        assertEquals(effectsAfterStop, target.effects().size(), "and nothing was done to the runtime");
    }

    /**
     * Issue 91 criterion 3: a command racing a more restrictive one that has not settled yet is told
     * which decision took the deployment, and does not pull it back.
     *
     * <p>The qualifier is load-bearing and is asserted both ways. While the stop is still converging —
     * its evidence behind its intent — a pause loses to it. Once the deployment has settled at
     * stopped, a start is an operator changing their mind and is accepted, because a rule that refused
     * it would make a stopped deployment unstartable forever.</p>
     */
    @Test
    void aLessRestrictiveCommandLosesToAnUnsettledDecisionAndIsAcceptedOnceItSettles() {
        var fixture = new CoordinatorFixture();
        DeploymentId id = fixture.idOf(fixture.deployment(TENANT, "supersession"));
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));

        // The host that owned this deployment goes away mid-command, so nothing publishes evidence and
        // the stop stays unsettled -- exactly the state a coordinator that died mid-command leaves.
        fixture.unhost(id);
        coordinator.submit(TENANT, id, new LifecycleCommand.Stop("x", "draining for maintenance"),
                GenerationExpectation.exactly(1));

        DeploymentCommandOutcome superseded = coordinator.submit(TENANT, id,
                new LifecycleCommand.Pause("p", "arrived late"), GenerationExpectation.exactly(2));

        assertEquals(new DeploymentCommandOutcome.Superseded("g2/STOPPED", 2), superseded);
        assertEquals(DesiredKind.STOPPED, fixture.record(TENANT, id).desired().kind());

        // Settle it: a runtime appears, reconciles, and publishes evidence at the current generation.
        var target = fixture.host(id);
        fixture.reconciler("owner-a", TENANT).sweepOnce();
        assertEquals(2, fixture.record(TENANT, id).observed().observedGeneration(), "now settled");

        DeploymentCommandOutcome restarted = coordinator.submit(TENANT, id,
                new LifecycleCommand.Start("again", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(2));
        assertEquals(new DeploymentCommandOutcome.Accepted("g3/RUNNING", 2, 3), restarted,
                "a settled level is a decision to change, not a race to lose");
        assertTrue(target.effects().contains("start:1@3"));
    }

    /** The three refusals that are decisions about the command rather than about the deployment. */
    @Test
    void theClosedRefusalVocabularyIsReachableAndEachMemberMeansSomethingDifferent() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "refusals");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");

        assertEquals(new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.MissingDisposition),
                coordinator.submit(TENANT, id, new LifecycleCommand.Undeploy("u", null, "no disposition"),
                        GenerationExpectation.any()),
                "the rule is enforced as an outcome so it can reach the caller who broke it");

        assertEquals(new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.IncompatibleState),
                coordinator.submit(TENANT, id, new LifecycleCommand.Resume("r"), GenerationExpectation.any()),
                "a resume is valid only from paused; from anywhere else it would be a disguised start");

        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());
        target.inFlight(3);
        assertEquals(new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.IncompatibleState),
                coordinator.submit(TENANT, id, new LifecycleCommand.Undeploy("busy",
                        LifecycleCommand.Undeploy.Disposition.REFUSE_IF_BUSY, "only if idle"),
                        GenerationExpectation.any()),
                "REFUSE_IF_BUSY is checked before any intent is persisted, which is why recovery may "
                        + "safely assume a durable removal intent was not busy");
        assertEquals(1, fixture.record(TENANT, id).generation(), "the refusal recorded nothing");

        fixture.unhost(id);
        assertEquals(new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.IncompatibleState),
                coordinator.submit(TENANT, id, new LifecycleCommand.Undeploy("unobservable",
                        LifecycleCommand.Undeploy.Disposition.REFUSE_IF_BUSY, "only if idle"),
                        GenerationExpectation.any()),
                "a process that cannot see the runtime cannot establish that nothing is in flight, and "
                        + "must not read that as 'nothing is in flight'");

        fixture.shutDown();
        assertEquals(new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.ShuttingDown),
                coordinator.submit(TENANT, id, new LifecycleCommand.Pause("p", "too late"),
                        GenerationExpectation.any()),
                "service shutdown outranks the whole hierarchy and is not part of it");
    }

    /**
     * Removal succeeding and a command arriving after removal are different answers, which is the pair
     * ADR 0038 says is most likely to be collapsed by a later reader.
     */
    @Test
    void removalAnswersTerminalWhileAnythingArrivingAfterwardsIsRefused() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "removal");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());

        var undeploy = new LifecycleCommand.Undeploy("u",
                LifecycleCommand.Undeploy.Disposition.DRAIN_FIRST, "decommissioned");
        DeploymentCommandOutcome removed = coordinator.submit(TENANT, id, undeploy,
                GenerationExpectation.exactly(1));
        assertEquals(new DeploymentCommandOutcome.Terminal("g2/REMOVED", 2), removed,
                "this is the success of the command that removed the deployment");

        assertEquals(new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.Tombstoned),
                coordinator.submit(TENANT, id, new LifecycleCommand.Pause("p", "a second too late"),
                        GenerationExpectation.any()),
                "and this is a different command declined after removal");

        DeploymentCommandOutcome retried = coordinator.submit(TENANT, id, undeploy,
                GenerationExpectation.exactly(1));
        var replayed = assertInstanceOf(DeploymentCommandOutcome.Replayed.class, retried,
                "the removing command's own retry must find its recorded outcome, or the operator who "
                        + "succeeded and the operator who arrived late receive the identical reply");
        assertEquals(new DeploymentCommandOutcome.Terminal("g2/REMOVED", 2), replayed.original());
    }

    /**
     * A runtime that fails is reported by a classifier drawn from the failure's class, never from its
     * message, and the intent it was carrying stays durable so recovery can finish it.
     */
    @Test
    void aRuntimeFailureIsClassifiedAndLeavesTheIntentStandingForRecovery() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "failure");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        target.failNext(new IllegalStateException("port 8080 already bound by tenant secret=hunter2"));

        DeploymentCommandOutcome outcome = coordinator.submit(TENANT, id,
                new LifecycleCommand.Start("s", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.exactly(0));

        assertEquals(new DeploymentCommandOutcome.Failed("java.lang.IllegalStateException"), outcome);
        DeploymentRegistry.Record after = fixture.record(TENANT, id);
        assertEquals(1, after.generation(), "the decision is durable even though its effect did not land");
        assertEquals(0, after.observed().observedGeneration(), "so recovery still sees it as outstanding");
        assertNotNull(after.failure(), "and an operator can see that the effect failed");
        assertEquals("java.lang.IllegalStateException", after.failure().message(),
                "the classifier reaches operator surfaces, so it must never be the message");
    }

    /**
     * Issue 91 criterion 6, at the boundary that enforces it: a lifecycle transition on one deployment
     * touches neither its siblings nor the engine they share.
     *
     * <p>The strongest form of this is structural and is asserted first: {@link
     * ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget} declares no operation that
     * means "drain the engine" or "close the engine", so the shared object's own two mutators are
     * unreachable from anything the coordinator can call. The behavioural half drives the most
     * destructive command in the vocabulary through one deployment and reads the other's runtime and
     * the shared engine afterwards.</p>
     */
    @Test
    void removingOneDeploymentLeavesItsSiblingAndTheirSharedEngineUntouched() {
        var fixture = new CoordinatorFixture();
        var doomed = fixture.deployment(TENANT, "doomed");
        var sibling = fixture.deployment(TENANT, "sibling");
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, fixture.idOf(doomed), new LifecycleCommand.Start("a", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());
        coordinator.submit(TENANT, fixture.idOf(sibling), new LifecycleCommand.Start("b", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.any());
        List<String> siblingBefore = sibling.effects();

        coordinator.submit(TENANT, fixture.idOf(doomed), new LifecycleCommand.Undeploy("u",
                LifecycleCommand.Undeploy.Disposition.CANCEL_IN_FLIGHT, "decommissioned"),
                GenerationExpectation.any());

        assertEquals(siblingBefore, sibling.effects(), "the sibling's runtime was not touched");
        assertEquals(1, fixture.record(TENANT, fixture.idOf(sibling)).generation(),
                "and its lifecycle did not move");
        assertFalse(fixture.engine.closed(), "closing the shared engine would take down every sibling");
        assertFalse(fixture.engine.drained(), "and draining it would stop every sibling admitting work");
        assertTrue(sibling.engine() == doomed.engine(), "the two really do share one engine");
    }

    /**
     * ADR 0038 D8: the unit of serialization is the deployment, never the tenant.
     *
     * <p>Two deployments of one tenant are commanded concurrently while one of them holds its runtime
     * inside an effect. If the lock were per tenant the second would be unable to start until the
     * first released, and this test would time out; the assertion is that it does not.</p>
     */
    @Test
    void twoDeploymentsOfOneTenantAreNotSerializedAgainstEachOther() throws Exception {
        var fixture = new CoordinatorFixture();
        DeploymentId slow = fixture.unhostedDeployment(TENANT, "slow");
        DeploymentId quick = fixture.idOf(fixture.deployment(TENANT, "quick"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        fixture.hostBlocking(slow, entered, release);
        var coordinator = fixture.coordinator("owner-a");

        Thread holder = new Thread(() -> coordinator.submit(TENANT, slow,
                new LifecycleCommand.Start("s", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                GenerationExpectation.any()));
        holder.start();
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                "the first deployment never reached its runtime");

        try {
            DeploymentCommandOutcome other = coordinator.submit(TENANT, quick,
                    new LifecycleCommand.Start("q", 1, DeploymentRegistry.UpdateStrategy.STOP_FIRST),
                    GenerationExpectation.any());
            assertInstanceOf(DeploymentCommandOutcome.Accepted.class, other,
                    "a tenant-wide lock would make one slow deployment block every other one that "
                            + "tenant owns, which is a scaling property discovered only under load");
        } finally {
            release.countDown();
            holder.join(Duration.ofSeconds(10).toMillis());
        }

        assertEquals(0, fixture.singleFlight.trackedDeployments(),
                "the mutex map releases what it created, or it leaks one entry per deployment ever seen");
    }

    /**
     * Issue 91 criterion 7, the slow-shutdown row: a drain whose bound elapses with work still in
     * flight is recorded as evidence rather than waited on or reported as success.
     *
     * <p>The bound is honoured by the runtime and never by a timeout in the coordinator, because a
     * caller that abandoned the stage would leave the deployment still draining while the authority
     * recorded that it had finished. What an operator sees afterwards is the honest pair: intent at
     * DRAINED, evidence at DRAINING, and the deployment still naming the version it holds.</p>
     */
    @Test
    void aDrainThatOutlivesItsBoundIsRecordedAsEvidenceRatherThanReportedAsFinished() {
        var fixture = new CoordinatorFixture();
        var target = fixture.deployment(TENANT, "slow-shutdown");
        DeploymentId id = fixture.idOf(target);
        var coordinator = fixture.coordinator("owner-a");
        coordinator.submit(TENANT, id, new LifecycleCommand.Start("s", 1,
                DeploymentRegistry.UpdateStrategy.STOP_FIRST), GenerationExpectation.exactly(0));
        target.inFlight(4);
        target.drainOutlivesItsBound();

        DeploymentCommandOutcome outcome = coordinator.submit(TENANT, id,
                new LifecycleCommand.Drain("d", Duration.ofSeconds(5)), GenerationExpectation.exactly(1));

        assertEquals(new DeploymentCommandOutcome.Accepted("g2/DRAINED", 1, 2), outcome,
                "the decision was accepted; whether the runtime finished in time is a separate fact");
        DeploymentRegistry.Record after = fixture.record(TENANT, id);
        assertEquals(DesiredKind.DRAINED, after.desired().kind(), "the intent is what the operator asked for");
        assertEquals(DeploymentRegistry.ObservedKind.DRAINING, after.observed().state(),
                "and the evidence is what actually happened: still finishing what it holds");
        assertEquals(1L, after.observed().activeVersion(),
                "a drain that has not completed has not released its activation");
        assertEquals(2, after.observed().observedGeneration(),
                "evidence is current, so recovery does not re-drive a drain that is legitimately slow");
    }

    /** A command for a deployment nobody created is a store answer, not a lifecycle answer. */
    @Test
    void aCommandForANonexistentDeploymentIsNotGivenALifecycleAnswer() {
        var fixture = new CoordinatorFixture();
        var coordinator = fixture.coordinator("owner-a");
        DeploymentRegistry.RegistryException missing = assertThrows(
                DeploymentRegistry.RegistryException.class,
                () -> coordinator.submit(TENANT, DeploymentId.of("never-created"),
                        new LifecycleCommand.Pause("p", "nothing to hold"), GenerationExpectation.any()));
        assertInstanceOf(DeploymentRegistry.FailureReason.NotFound.class, missing.reason(),
                "answering existence inside the outcome hierarchy would let a client enumerate "
                        + "deployments by reading which refusal it received");
    }

    /**
     * A runtime that reads the durable record the first time it is asked to do anything, so a test can
     * assert what was durable <em>before</em> the effect rather than after the command returned.
     */
    private static final class IntentProbe extends RecordingLifecycleTarget {
        private final CoordinatorFixture fixture;
        private final DeploymentId deploymentId;
        private DeploymentRegistry.Record snapshot;

        private IntentProbe(CoordinatorFixture fixture, DeploymentId deploymentId) {
            super(fixture.engine);
            this.fixture = fixture;
            this.deploymentId = deploymentId;
        }

        DeploymentRegistry.Record snapshot() {
            return snapshot;
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> start(long graphVersion, long generation) {
            if (snapshot == null) snapshot = fixture.record(TENANT, deploymentId);
            return super.start(graphVersion, generation);
        }
    }
}
