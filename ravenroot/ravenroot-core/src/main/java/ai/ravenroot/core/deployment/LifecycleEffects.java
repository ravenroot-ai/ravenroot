package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.Desired;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * The two ways durable lifecycle intent becomes a runtime effect, side by side so the difference
 * between them is visible rather than duplicated.
 *
 * <h2>Why there are two and not one</h2>
 * <p>{@link #apply} runs with the command in hand and can honour everything the operator wrote:
 * the drain bound, the undeploy disposition, and the fact that a barrier was a barrier.
 * {@link #converge} runs from durable state alone, because that is all a reconciler has after a
 * restart or a takeover, and {@code Desired} — a published, frozen record — carries a level, a
 * version and a strategy and nothing else. What the second one loses is stated below rather than
 * papered over, because a reader who assumed recovery replays the original command exactly would be
 * wrong in three specific ways.</p>
 *
 * <h2>What recovery cannot recover, and why each loss is safe</h2>
 * <ul>
 *   <li><b>The drain bound.</b> {@code Drain} carries a positive bound and {@code Desired} has
 *       nowhere to put it. A reconciler therefore drains under the service's configured recovery
 *       bound and reports that it did. Widening {@code Desired} to carry the bound would change a
 *       published record for the benefit of a value that is a property of one command rather than of
 *       the state it converges to.</li>
 *   <li><b>The undeploy disposition.</b> Recovery converges a {@code REMOVED} intent by draining
 *       first and then terminating, never by cancelling in flight. This is safe in the one direction
 *       that matters: draining work that a {@code CANCEL_IN_FLIGHT} would have discarded costs time,
 *       while cancelling work that a {@code DRAIN_FIRST} promised to finish destroys it.
 *       {@code REFUSE_IF_BUSY} needs no recovery rule at all, because it is evaluated as a refusal
 *       <em>before</em> any intent is persisted — a durable {@code REMOVED} intent is proof that the
 *       deployment was not busy when the operator's condition was checked.</li>
 *   <li><b>That a barrier happened.</b> A barrier leaves the desired level untouched, so a
 *       reconciler reading durable state sees a generation that moved and a level that did not, and
 *       converges to the level — which is exactly what ADR 0038 D5 requires of an interrupted
 *       {@code Restart}. The barrier's own effect is not lost: a barrier interrupted by a crash is
 *       completed <em>by</em> the crash, since the work it was ending died with the process holding
 *       it, and a barrier interrupted by a takeover is completed by the fence, since the superseded
 *       owner's writes are refused from the moment ownership moves.</li>
 * </ul>
 */
final class LifecycleEffects {

    private LifecycleEffects() {
    }

    /**
     * Performs exactly what one command asked for, at the generation the command was recorded at.
     *
     * @param target runtime port for this deployment.
     * @param command command whose effects are being carried out.
     * @param desired durable intent the command established.
     * @param generation generation the command advanced the deployment to.
     * @param drainBound bound used where the command names none of its own.
     */
    static void apply(DeploymentLifecycleTarget target, LifecycleCommand command, Desired desired,
                      long generation, Duration drainBound) {
        switch (command) {
            case LifecycleCommand.Start start -> await(target.start(start.version(), generation));
            case LifecycleCommand.Pause ignored -> await(target.closeAdmission(generation));
            case LifecycleCommand.Resume ignored -> await(target.openAdmission(generation));
            case LifecycleCommand.Drain drain -> {
                await(target.closeAdmission(generation));
                // The bound is honoured by the target rather than by a timeout here: a caller that
                // abandoned the stage would leave the runtime still draining while the authority
                // recorded that it had finished. A drain that ran out of time is evidence, and the
                // observation written afterwards is where it shows up.
                await(target.drain(drain.bound(), generation));
            }
            case LifecycleCommand.Stop ignored -> {
                await(target.closeAdmission(generation));
                await(target.terminateDomain(generation));
            }
            case LifecycleCommand.Undeploy undeploy -> {
                await(target.closeAdmission(generation));
                switch (undeploy.disposition()) {
                    case DRAIN_FIRST -> await(target.drain(drainBound, generation));
                    case CANCEL_IN_FLIGHT -> await(target.barrier(generation));
                    // Nothing was in flight when the condition was checked under this deployment's
                    // own single-flight and lease, so there is nothing to dispose of.
                    case REFUSE_IF_BUSY -> { }
                }
                await(target.terminateDomain(generation));
            }
            // A cancel ends the work admitted before the barrier and leaves the deployment exactly as
            // restrictive as it was: it answers "not this work", never "not this deployment".
            case LifecycleCommand.Cancel ignored -> await(target.barrier(generation));
            case LifecycleCommand.Restart ignored -> {
                await(target.barrier(generation));
                await(target.start(requireVersion(desired), generation));
            }
        }
    }

    /**
     * Drives the runtime to whatever durable intent says, using only durable facts.
     *
     * @param target runtime port for this deployment.
     * @param desired durable intent to converge to.
     * @param generation generation the intent was recorded at.
     * @param drainBound bound this service gives a recovered drain.
     */
    static void converge(DeploymentLifecycleTarget target, Desired desired, long generation,
                         Duration drainBound) {
        DeploymentLifecycleTarget.Reading reading = await(target.observe());
        switch (desired.kind()) {
            // A held deployment is resumed rather than restarted. The discriminator is evidence and
            // not intent, deliberately: Start, Resume and Restart all record RUNNING at a new
            // generation, so durable intent cannot tell them apart, while the runtime saying "I am
            // paused" is a fact about the activation that is still loaded. Restarting a paused
            // deployment would discard an activation the operator explicitly asked to retain.
            case RUNNING -> {
                if (reading.state() == ObservedKind.PAUSED) {
                    await(target.openAdmission(generation));
                } else {
                    await(target.start(requireVersion(desired), generation));
                }
            }
            case PAUSED -> await(target.closeAdmission(generation));
            case DRAINED -> {
                await(target.closeAdmission(generation));
                await(target.drain(drainBound, generation));
            }
            case STOPPED -> {
                await(target.closeAdmission(generation));
                await(target.terminateDomain(generation));
            }
            case REMOVED -> {
                await(target.closeAdmission(generation));
                await(target.drain(drainBound, generation));
                await(target.terminateDomain(generation));
            }
        }
    }

    private static long requireVersion(Desired desired) {
        Long version = desired.desiredVersion();
        if (version == null) {
            // Unreachable through Desired's own constructor, which rejects RUNNING without a version.
            // Asserted rather than assumed because the alternative is a NullPointerException reported
            // to an operator as an unclassified lifecycle failure.
            throw new IllegalStateException("a running deployment must name the version it activates");
        }
        return version;
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
