package ai.ravenroot.api.deployment.lifecycle;

import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * The runtime effects a lifecycle authority is allowed to perform on one deployment (ADR 0038 D9).
 *
 * <h2>Why this is not {@code GraphDeployment}</h2>
 * <p>{@code GraphDeployment} is the embedding application's handle: it starts, stops and restarts a
 * deployment on behalf of whoever composed the process. This port is the opposite direction — it is
 * what a <em>remote</em> authority is permitted to reach through — and the two must not be the same
 * type, because the set of things each is allowed to do is different. Widening
 * {@code GraphDeployment} to carry the lifecycle levels would hand the deployment authority every
 * method that type already has, and the reason ADR 0038 D9 forbids that is specific: none of the
 * operations below may reach {@code engine.drain()} or {@code engine.close()}. An engine is shared by
 * every deployment hosted on it, so a port that could close one would let an operator draining a
 * single deployment take down every sibling — precisely the blast radius ADR 0012's supervision
 * boundary exists to bound, and precisely what issue 91's sixth acceptance criterion forbids.</p>
 *
 * <p>The constraint is expressed as the <em>shape</em> of this interface rather than as a rule an
 * implementor is asked to remember: there is no method here whose meaning is "shut the engine down",
 * so an implementation that closed the engine would be answering a question nobody asked. The widest
 * thing this port can say is {@link #terminateDomain(long)}, and its name is the boundary: the
 * deployment's own domain — its sources, its admission, its in-flight work — and nothing the
 * deployment shares with another.</p>
 *
 * <h2>Every operation is idempotent for one generation</h2>
 * <p>The coordinator persists lifecycle intent <em>before</em> it performs any effect, so a crash
 * between the two leaves durable intent whose effect may or may not have run. Recovery cannot tell
 * which, and asking it to would require a third durable fact written between the two writes — which
 * has the same crash window one level down. The port closes that instead: <b>applying the same
 * operation twice at the same {@code deploymentGeneration} must have the effect of applying it
 * once.</b> That is what lets a reconciler re-drive an interrupted command without duplicating its
 * action, and it is why every method takes the generation rather than inferring it.</p>
 *
 * <h2>The generation is half-open, and this port is where that becomes observable</h2>
 * <p>A barrier captured at {@code G} closes admission at {@code G}, lets work already admitted
 * complete <em>under</em> {@code G}, and admits everything arriving afterwards at {@code G + 1}. The
 * comparison an implementation performs is exact equality and never {@code >=}: work carrying
 * {@code G} belongs to the closing side and work carrying {@code G + 1} to the opening side, while an
 * ordering test would place a much older {@code G - 2} on the closing side of a barrier it predates
 * entirely (ADR 0038 D6).</p>
 *
 * <h2>Failure is reported by failing the stage</h2>
 * <p>These methods return a stage that fails, rather than an outcome value, because a runtime effect
 * that did not happen has no lifecycle meaning of its own: the coordinator classifies the failure by
 * its class name into {@code DeploymentCommandOutcome.Failed} and never by its message, which may
 * carry payload fragments or author-controlled text and reaches operator surfaces and logs.</p>
 */
public interface DeploymentLifecycleTarget {

    /**
     * What the runtime currently is, sampled as evidence rather than declared as intent.
     *
     * <p>{@code inFlight} is here rather than on a method of its own because exactly one caller needs
     * it — {@code Undeploy} with {@link LifecycleCommand.Undeploy.Disposition#REFUSE_IF_BUSY}, which
     * must decide whether anything is still running before it removes anything — and a second call to
     * ask would be sampled at a different instant from the state beside it, which is the one thing a
     * disposition decision must not be.</p>
     *
     * @param state runtime lifecycle state, in the registry's observed vocabulary.
     * @param activeVersion graph version currently activated, or {@code null} when none is.
     * @param inFlight count of admitted units of work that have not finished, never negative.
     */
    record Reading(ObservedKind state, Long activeVersion, long inFlight) {
        /** Requires a state, a positive version when one is named, and a non-negative in-flight count. */
        public Reading {
            if (state == null) throw new IllegalArgumentException("a reading requires a state");
            if (activeVersion != null && activeVersion < 1) {
                throw new IllegalArgumentException("activeVersion must be at least 1");
            }
            if (inFlight < 0) throw new IllegalArgumentException("inFlight cannot be negative");
        }
    }

    /**
     * Activates the named graph version and opens admission at this generation.
     *
     * <p>Subsumes {@link #openAdmission(long)}: a deployment that has started is admitting. The two
     * are separate methods because {@code Resume} must reopen admission on the activation a
     * {@code Pause} retained, and naming a version there would make it a disguised start whose
     * version could silently disagree with what is actually loaded.</p>
     *
     * @param graphVersion immutable graph version to activate, at least one.
     * @param deploymentGeneration generation this activation is admitted under.
     * @return stage completing when the deployment is activated and admitting.
     */
    CompletionStage<Void> start(long graphVersion, long deploymentGeneration);

    /**
     * Stops admitting new work at this generation, retaining whatever has already been admitted.
     *
     * <p>Closing admission is not releasing resources: this is the operation behind {@code Pause} and
     * the first half of {@code Drain}, {@code Stop} and {@code Undeploy}, and after it the deployment
     * is still bound to the version it was running.</p>
     *
     * @param deploymentGeneration generation at which admission closes.
     * @return stage completing when nothing further will be admitted.
     */
    CompletionStage<Void> closeAdmission(long deploymentGeneration);

    /**
     * Resumes admitting work on the activation a {@link #closeAdmission(long)} retained.
     *
     * @param deploymentGeneration generation new admissions now carry.
     * @return stage completing when the deployment is admitting again.
     */
    CompletionStage<Void> openAdmission(long deploymentGeneration);

    /**
     * Ends the work admitted under generations before {@code deploymentGeneration} and admits
     * afterwards at {@code deploymentGeneration}.
     *
     * <p>This is the one operation both barrier commands reach: {@code Cancel} stops here, and
     * {@code Restart} follows it with a {@link #start(long, long)}. Neither changes the desired
     * level, so a deployment that was running is running when the barrier completes.</p>
     *
     * @param deploymentGeneration generation the barrier opens; work carrying an earlier one is ended.
     * @return stage completing when work from before the barrier has been ended.
     */
    CompletionStage<Void> barrier(long deploymentGeneration);

    /**
     * Carries already-admitted work to completion within a bound, without releasing the activation.
     *
     * <p>The bound is honoured by the implementation rather than by the caller, because a caller that
     * timed the stage out would leave the runtime still draining while the authority recorded that it
     * had stopped. Reporting {@code false} is the honest answer for a drain that ran out of time, and
     * it is what lets a slow shutdown be reported as evidence instead of being waited on forever.</p>
     *
     * @param bound positive maximum time admitted work is given to finish.
     * @param deploymentGeneration generation the drain is carried out under.
     * @return stage completing {@code true} when everything finished inside the bound, {@code false}
     *         when the bound elapsed with work still in flight.
     */
    CompletionStage<Boolean> drain(Duration bound, long deploymentGeneration);

    /**
     * Releases everything this deployment owns, and nothing it shares.
     *
     * <p>The widest operation on this port, and deliberately still narrow: the deployment's sources,
     * admission and in-flight work end here, while the engine that hosts it, the actor system it
     * shares and every sibling deployment are untouched. An implementation that reached
     * {@code engine.drain()} or {@code engine.close()} would violate ADR 0038 D9 and issue 91's sixth
     * criterion in the same call.</p>
     *
     * @param deploymentGeneration generation at which the deployment's own domain is released.
     * @return stage completing when this deployment holds nothing.
     */
    CompletionStage<Void> terminateDomain(long deploymentGeneration);

    /**
     * Samples what the runtime currently is, so the authority can record evidence beside intent.
     *
     * @return stage yielding a coherent single-instant reading of this deployment.
     */
    CompletionStage<Reading> observe();
}
