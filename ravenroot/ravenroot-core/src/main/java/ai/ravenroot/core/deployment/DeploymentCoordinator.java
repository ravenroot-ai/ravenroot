package ai.ravenroot.core.deployment;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.lifecycle.DeploymentCommandOutcome;
import ai.ravenroot.api.deployment.lifecycle.DeploymentLifecycleTarget;
import ai.ravenroot.api.deployment.lifecycle.LifecycleCommand;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.Desired;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.DesiredKind;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.Record;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;
import ai.ravenroot.api.persistence.RevisionExpectation;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The one place a {@link LifecycleCommand} becomes a durable decision, answered by a typed outcome.
 *
 * <h2>Intent is durable before any effect runs</h2>
 * <p>Every accepted command writes its idempotency key, the generation it was decided against, the
 * generation it produces, the desired state, and the owner and fence that will carry it out —
 * <em>then</em> touches the runtime. The ordering is the whole design: after it, a crash leaves the
 * durable record either naming a lifecycle move whose effect may or may not have happened, or naming
 * nothing at all. There is no third state in which an effect happened that nothing recorded, so
 * {@link DeploymentReconciler} always has something to reconcile <em>towards</em>, and
 * {@link DeploymentLifecycleTarget}'s per-generation idempotence is what lets it get there without
 * doing the action twice.</p>
 *
 * <p>The reverse ordering — act, then record — was rejected for the reason it is always rejected: the
 * crash window then contains an effect nobody can find. A deployment would be running with no record
 * that anyone started it, and the authority's next decision would be made against a state it
 * believes it knows.</p>
 *
 * <h2>Recording intent does not require owning the runtime</h2>
 * <p>{@code DeploymentRegistry.command} takes no lease, and that is deliberate rather than an
 * oversight this class works around: recording what an operator decided is not the same act as
 * carrying it out. A process that cannot take the lease — because another live owner holds it — still
 * records the decision and answers {@code Accepted}, and the owner's reconciler performs the effect.
 * Refusing the operator because the runtime happens to live on another host would make the authority
 * as available as the least available host it fronts, which is the property a durable authority
 * exists to remove. Evidence is the other way round: {@code observe} and {@code fail} are lease-gated
 * at the registry, so only the current owner may publish what the runtime is.</p>
 *
 * <h2>Where {@code Conflict} is turned back into an answer</h2>
 * <p>{@code FailureReason} is sealed and says only {@code Conflict} for three genuinely different
 * events: the revision moved, the generation moved, or the idempotency key was reused for a different
 * body. ADR 0038 D10 puts the client-facing distinction one layer up, and this is that layer. It is
 * drawn by re-reading the record rather than by guessing:</p>
 * <ul>
 *   <li>The generation is checked <em>before</em> the write, against the expectation the caller
 *       stated, so {@link DeploymentCommandOutcome.StaleGeneration} is answered from the two numbers
 *       the caller and the record actually carry.</li>
 *   <li>A {@code Conflict} whose re-read shows the revision <em>unchanged</em> cannot have come from
 *       the compare-and-set, which was satisfied, nor from the generation, which was checked: the
 *       only remaining source inside {@code command} is the ledger refusing a reused key whose body
 *       differs. That is {@link DeploymentCommandOutcome.IdempotencyConflict}, and it is deduced
 *       rather than reported because the port has no way to say it.</li>
 *   <li>A {@code Conflict} whose re-read shows the revision moved is an ordinary race with another
 *       writer and is retried a bounded number of times.</li>
 * </ul>
 *
 * <h2>A command identity is derived, never minted</h2>
 * <p>ADR 0038 D12 refuses a sixth kind of identifier, and a freshly minted opaque command id would be
 * exactly that: a value that has to be kept consistent with the generation, the deployment and the
 * kind, and that can only ever disagree with them. The identity of an accepted command is therefore
 * <em>computed</em> from what is durably recorded — the generation it produced and the decision it
 * established — so any owner, in any process, reading the record afterwards derives the same
 * identity. That is what makes {@link DeploymentCommandOutcome.Superseded} able to name the decision
 * that won: it is read off the record rather than remembered by whichever process happened to accept
 * it. At most one command is recorded per generation, so the identity is unique by construction.</p>
 */
public final class DeploymentCoordinator {

    /**
     * How many times a benign revision race is retried before the caller is told the deployment is
     * contended. Bounded rather than open, because an unbounded retry against a deployment that
     * another writer is updating continuously is a request that never returns, and a caller holding
     * a connection open forever is worse than a caller told to try again.
     */
    private static final int MAXIMUM_RACE_RETRIES = 3;

    private final DeploymentRegistry registry;
    private final DeploymentTargets targets;
    private final DeploymentSingleFlight singleFlight;
    private final ServiceShutdownIntent shutdown;
    private final DeploymentOwnership ownership;
    private final Duration drainBound;

    /**
     * Creates a coordinator that owns lifecycle decisions for whatever deployments it is asked about.
     *
     * @param registry durable authority for intent, evidence, leases and the idempotency ledger.
     * @param targets resolver for the runtimes this process actually hosts.
     * @param singleFlight per-deployment in-process serialization, shared with the reconciler so the
     *                     two never drive the same deployment at once.
     * @param shutdown service-scoped shutdown intent, read once per command.
     * @param ownerId stable identity this process presents when it takes a lease.
     * @param leaseTtl positive lease duration requested when ownership is taken.
     * @param drainBound bound given to a drain whose command names none, which is every undeploy.
     * @param clock time authority used only to decide whether this process's own lease is worth
     *              reusing; lease expiry itself is always decided by the registry's clock.
     */
    public DeploymentCoordinator(DeploymentRegistry registry, DeploymentTargets targets,
                                 DeploymentSingleFlight singleFlight, ServiceShutdownIntent shutdown,
                                 String ownerId, Duration leaseTtl, Duration drainBound, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.singleFlight = Objects.requireNonNull(singleFlight, "singleFlight");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.ownership = new DeploymentOwnership(registry, ownerId, leaseTtl, clock);
        this.drainBound = positive(drainBound, "drainBound");
    }

    /**
     * Answers one lifecycle command for one deployment.
     *
     * <p>The generation expectation is a parameter rather than a component of the command because it
     * is a fact about the <em>caller's view</em>, not about the decision: the same
     * {@code Stop("k", "maintenance")} decided against generation 4 and decided against generation 5
     * are the same words and two different decisions, which is exactly why ADR 0038 D11 puts the
     * generation inside the idempotency digest. A caller that pins
     * {@link GenerationExpectation#exactly(long)} is told {@link DeploymentCommandOutcome.StaleGeneration}
     * when the lifecycle moved underneath it; a caller that passes {@link GenerationExpectation#any()}
     * is asking for convergence against whatever it finds and gets a replay or a convergence instead.
     * Neither is the default: making one of them implicit would silently give every caller the other
     * one's failure mode.</p>
     *
     * @param tenantId tenant owning the deployment.
     * @param deploymentId deployment the command addresses.
     * @param command the lifecycle command to answer.
     * @param expectedGeneration generation the caller decided this command against.
     * @return the typed outcome; never {@code null}, and never an exception for a lifecycle answer.
     * @throws DeploymentRegistry.RegistryException when the <em>store</em> could not answer, which
     *         includes a deployment that does not exist: existence is a precondition of the
     *         lifecycle rather than one of its answers, and reporting it inside the outcome hierarchy
     *         would let a client learn which deployments exist by reading which refusal it received.
     */
    public DeploymentCommandOutcome submit(String tenantId, DeploymentId deploymentId,
                                           LifecycleCommand command,
                                           GenerationExpectation expectedGeneration) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(deploymentId, "deploymentId");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        return singleFlight.inFlight(tenantId, deploymentId,
                () -> answer(tenantId, deploymentId, command, expectedGeneration));
    }

    private DeploymentCommandOutcome answer(String tenantId, DeploymentId deploymentId,
                                            LifecycleCommand command,
                                            GenerationExpectation expectedGeneration) {
        // Refused before anything is read, because both refusals are decisions about the command
        // itself: one is malformed against ADR 0038 D7, and the other is a statement about the whole
        // service that no per-deployment state can change.
        if (command instanceof LifecycleCommand.Undeploy undeploy && undeploy.disposition() == null) {
            return new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.MissingDisposition);
        }
        if (shutdown.inEffect()) {
            return new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.ShuttingDown);
        }

        for (int attempt = 1; ; attempt++) {
            Record record = read(tenantId, deploymentId);

            if (record.tombstone() != null) {
                return afterRemoval(record, command);
            }
            Optional<DeploymentCommandOutcome> stale = staleness(record, expectedGeneration);
            if (stale.isPresent()) return stale.get();

            Optional<DeploymentCommandOutcome> refusal = refusal(record, command);
            if (refusal.isPresent()) return refusal.get();

            Optional<DeploymentCommandOutcome> superseded = supersession(record, command);
            if (superseded.isPresent()) return superseded.get();

            Optional<DeploymentCommandOutcome> converged = convergence(record, command);
            if (converged.isPresent()) return converged.get();

            try {
                return decide(tenantId, deploymentId, record, command, expectedGeneration);
            } catch (DeploymentRegistry.RegistryException conflict) {
                if (!(conflict.reason() instanceof DeploymentRegistry.FailureReason.Conflict)) throw conflict;
                Optional<DeploymentCommandOutcome> classified =
                        classifyConflict(tenantId, deploymentId, record, command, expectedGeneration);
                if (classified.isPresent()) return classified.get();
                if (attempt >= MAXIMUM_RACE_RETRIES) throw conflict;
            }
        }
    }

    // ------------------------------------------------------------------ the answers taken before writing

    /**
     * A command that arrives after removal, and the one command that performed it.
     *
     * <p>ADR 0038 D4 keeps {@code Terminal} and {@code Refused(Tombstoned)} deliberately distinct, and
     * the distinction is only observable if the removing command's own retry can still find its
     * recorded outcome. The tombstone ledger is consulted first for exactly that: a retry presenting
     * the same key and the same body replays into {@code Terminal}, and anything else — a different
     * body under the same key, or an unrelated command — is refused. Checking the refusal first would
     * give the operator who removed a deployment and the operator who arrived a second too late the
     * identical reply, which is the collapse the record names as most likely.</p>
     */
    private DeploymentCommandOutcome afterRemoval(Record record, LifecycleCommand command) {
        if (command instanceof LifecycleCommand.Undeploy undeploy) {
            try {
                Record replayed = await(registry.tombstone(
                        new DeploymentRegistry.Tombstone(undeploy.reason(), record.tombstone().at()),
                        removal(record, command)));
                return new DeploymentCommandOutcome.Replayed(
                        new DeploymentCommandOutcome.Terminal(identity(replayed), replayed.generation()));
            } catch (DeploymentRegistry.RegistryException notThisCommand) {
                if (!(notThisCommand.reason() instanceof DeploymentRegistry.FailureReason.Conflict)) {
                    throw notThisCommand;
                }
            }
        }
        return new DeploymentCommandOutcome.Refused(DeploymentCommandOutcome.Reason.Tombstoned);
    }

    private Optional<DeploymentCommandOutcome> staleness(Record record, GenerationExpectation expected) {
        if (expected instanceof GenerationExpectation.Exactly exactly
                && exactly.generation() != record.generation()) {
            return Optional.of(new DeploymentCommandOutcome.StaleGeneration(
                    exactly.generation(), record.generation()));
        }
        return Optional.empty();
    }

    private Optional<DeploymentCommandOutcome> refusal(Record record, LifecycleCommand command) {
        if (command instanceof LifecycleCommand.Resume && record.desired().kind() != DesiredKind.PAUSED) {
            // ADR 0038 D7: a resume returns a held deployment to running and means nothing anywhere
            // else. Treating it as a start from any level would let it reactivate a deployment an
            // operator stopped, under a command whose whole promise is that it changes nothing but
            // admission.
            return Optional.of(new DeploymentCommandOutcome.Refused(
                    DeploymentCommandOutcome.Reason.IncompatibleState));
        }
        if (command instanceof LifecycleCommand.Undeploy undeploy
                && undeploy.disposition() == LifecycleCommand.Undeploy.Disposition.REFUSE_IF_BUSY) {
            Optional<DeploymentLifecycleTarget> target =
                    targets.resolve(record.tenantId(), record.deploymentId());
            if (target.isPresent() && await(target.get().observe()).inFlight() > 0) {
                return Optional.of(new DeploymentCommandOutcome.Refused(
                        DeploymentCommandOutcome.Reason.IncompatibleState));
            }
        }
        return Optional.empty();
    }

    /**
     * A command loses to a more restrictive decision that has not finished converging yet.
     *
     * <p>"Still converging" is the load-bearing qualifier, and it is read off the record rather than
     * assumed: a deployment whose observation has caught up with its generation has <em>settled</em>
     * at its level, and a less restrictive command arriving then is an operator changing their mind,
     * not a late arrival losing a race. Refusing it would make a stopped deployment unstartable.
     * While the observation is behind, the same command is a genuine racer and is told which decision
     * took it, so the caller can look at what happened instead of retrying into a conclusion that has
     * already been reached (ADR 0038 D7).</p>
     *
     * <p><b>Known limit.</b> Supersession is decided against the desired <em>level</em>, which is what
     * the record durably carries. A barrier leaves the level untouched, so a command superseded by a
     * {@code Cancel} is named by the level in force rather than by the cancel, and a command that
     * merely races a barrier is not reported as superseded at all. Closing that would need the record
     * to carry the kind of the last accepted command, which the published contract does not have.</p>
     */
    private Optional<DeploymentCommandOutcome> supersession(Record record, LifecycleCommand command) {
        DesiredKind current = record.desired().kind();
        int rank = command.precedence();
        boolean settled = record.observed().observedGeneration() >= record.generation();
        if (!settled && current.restriction() > rank) {
            return Optional.of(new DeploymentCommandOutcome.Superseded(
                    identity(record.generation(), current.name()), record.generation()));
        }
        return Optional.empty();
    }

    /**
     * The deployment is already where the command asks it to go, so nothing moves.
     *
     * <p>This also answers a retry of the command that put it there, and that is correct rather than a
     * missed replay: {@code Converged} reports the generation that is actually in force, which is the
     * same number the first delivery returned, and its contract says in as many words that it holds
     * "whether or not any command put it there". Advancing the generation instead would tell every
     * consumer keying on it that a decision was applied when none was.</p>
     *
     * <p>Barriers never converge. A barrier names no level, so there is nothing for it to already be
     * at; asking whether a cancel has converged is asking whether an event has a state.</p>
     */
    private Optional<DeploymentCommandOutcome> convergence(Record record, LifecycleCommand command) {
        Optional<DesiredKind> level = command.targetLevel();
        if (level.isEmpty() || level.get() != record.desired().kind()) return Optional.empty();
        if (command instanceof LifecycleCommand.Start start
                && !Long.valueOf(start.version()).equals(record.desired().desiredVersion())) {
            // Running the wrong version is not converged: the level matches and the activation does
            // not, and reporting convergence would tell the operator their version is live.
            return Optional.empty();
        }
        return Optional.of(new DeploymentCommandOutcome.Converged(
                identity(record.generation(), level.get().name()), record.generation(),
                record.observed().state()));
    }

    // ------------------------------------------------------------------ the write path

    private DeploymentCommandOutcome decide(String tenantId, DeploymentId deploymentId, Record read,
                                            LifecycleCommand command, GenerationExpectation expected) {
        // Ownership first, so the owner and fence that will carry the decision out are in the durable
        // record before the decision is: criterion 1 asks for the owner and fence to be recorded
        // *before* side effects, and taking the lease afterwards would record them after the first one.
        Record record = ownership.own(read);
        long from = record.generation();

        Desired next = desiredFor(record, command);
        Record intent = await(registry.command(next, mutation(record, command, expected,
                RevisionExpectation.exactly(record.revision()))));

        // A ledger replay returns the historical record and moves nothing; an accepted decision
        // advances the generation by exactly one. The generation is the discriminator rather than the
        // revision because "advances by one" is what the contract guarantees about a lifecycle move,
        // while nothing in it fixes how far a revision travels.
        boolean replayed = intent.generation() <= from;
        long generation = intent.generation();

        DeploymentCommandOutcome outcome = replayed
                ? new DeploymentCommandOutcome.Replayed(new DeploymentCommandOutcome.Accepted(
                        identity(intent), generation - 1, generation))
                : new DeploymentCommandOutcome.Accepted(identity(intent), from, generation);

        if (replayed) return outcome;

        Optional<DeploymentLifecycleTarget> target = targets.resolve(tenantId, deploymentId);
        if (target.isEmpty() || !ownership.holds(intent)) {
            // The decision is durable and someone else will carry it out: either this process does not
            // host the runtime, or another live owner does. Reporting a failure here would tell an
            // operator their command was lost when it is recorded, fenced and reconcilable.
            return outcome;
        }

        try {
            LifecycleEffects.apply(target.get(), command, next, generation, drainBound);
        } catch (RuntimeException failure) {
            ownership.report(intent, failure);
            return new DeploymentCommandOutcome.Failed(DeploymentOwnership.classify(failure));
        }

        Record evidenced = ownership.observe(intent, target.get(), generation);
        if (command instanceof LifecycleCommand.Undeploy undeploy) {
            Record removed = await(registry.tombstone(
                    new DeploymentRegistry.Tombstone(undeploy.reason(), evidenced.updatedAt()),
                    removal(evidenced, command)));
            return new DeploymentCommandOutcome.Terminal(identity(removed), removed.generation());
        }
        return outcome;
    }

    /**
     * Deduces which of {@code Conflict}'s three meanings this one was, by re-reading the aggregate.
     *
     * @return the answer when it is decidable, or empty when the revision moved and the write is
     *         worth retrying against a fresh read.
     */
    private Optional<DeploymentCommandOutcome> classifyConflict(String tenantId, DeploymentId deploymentId,
                                                                Record before, LifecycleCommand command,
                                                                GenerationExpectation expected) {
        Record after = read(tenantId, deploymentId);
        if (after.revision() == before.revision()) {
            // Nothing was written between the read and the refusal, so the compare-and-set was
            // satisfied and the generation was checked before the call. Inside `command` the only
            // remaining source of Conflict is the ledger declining a key already used for a different
            // body, which is what the client needs to be told.
            return Optional.of(new DeploymentCommandOutcome.IdempotencyConflict(command.idempotencyKey()));
        }
        if (expected instanceof GenerationExpectation.Exactly exactly
                && exactly.generation() != after.generation()) {
            return Optional.of(new DeploymentCommandOutcome.StaleGeneration(
                    exactly.generation(), after.generation()));
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ ownership, evidence, plumbing

    private Desired desiredFor(Record record, LifecycleCommand command) {
        Optional<DesiredKind> level = command.targetLevel();
        if (level.isEmpty()) {
            // A barrier leaves the level exactly where it was and moves only the generation; the
            // generation the registry assigns is authoritative, so zero here is a placeholder the
            // adapter overwrites, exactly as it does for every other desired-state write.
            Desired held = record.desired();
            return new Desired(held.kind(), held.desiredVersion(), held.updateStrategy(), 0);
        }
        DesiredKind kind = level.get();
        if (kind != DesiredKind.RUNNING) return new Desired(kind, null, null, 0);
        long version = command instanceof LifecycleCommand.Start start
                ? start.version()
                : requireHeldVersion(record);
        return new Desired(kind, version, updateStrategy(record, command), 0);
    }

    private static DeploymentRegistry.UpdateStrategy updateStrategy(Record record, LifecycleCommand command) {
        if (command instanceof LifecycleCommand.Start start) return start.updateStrategy();
        DeploymentRegistry.UpdateStrategy held = record.desired().updateStrategy();
        return held == null ? DeploymentRegistry.UpdateStrategy.STOP_FIRST : held;
    }

    private static long requireHeldVersion(Record record) {
        Long desired = record.desired().desiredVersion();
        if (desired != null) return desired;
        Long active = record.observed().activeVersion();
        if (active != null) return active;
        // A resume from PAUSED always has one of the two, because PAUSED does not release the
        // activation; reaching here means the record disagrees with its own invariants.
        throw new IllegalStateException("a resumable deployment must still name the version it holds");
    }

    private DeploymentRegistry.Command mutation(Record record, LifecycleCommand command,
                                                GenerationExpectation expected,
                                                RevisionExpectation revision) {
        return new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(),
                ledgerKey(command), DeploymentOwnership.digestOf(LifecycleCommand.canonicalForm(command, expected)),
                revision, expected);
    }

    /**
     * The tombstone write, pinned to the generation the removal was recorded at.
     *
     * <p>Not {@link #mutation}: a tombstone is written <em>after</em> the lifecycle move that
     * requested it, so the caller's own expectation -- the generation it decided against -- is by then
     * one step behind and would be refused. Pinning the record's current generation instead makes the
     * removal's digest a function of durable state alone, which is what lets a retry arriving after
     * removal recompute the same digest and replay into {@code Terminal} rather than being told its
     * own successful command belonged to somebody else.</p>
     */
    private DeploymentRegistry.Command removal(Record record, LifecycleCommand command) {
        GenerationExpectation at = GenerationExpectation.exactly(record.generation());
        return new DeploymentRegistry.Command(record.tenantId(), record.deploymentId(),
                ledgerKey(command), DeploymentOwnership.digestOf(LifecycleCommand.canonicalForm(command, at)),
                RevisionExpectation.exactly(record.revision()), at);
    }

    /**
     * The ledger slot a command occupies, scoped to {@code (tenant, deployment, kind)}.
     *
     * <p>The registry's ledger is already scoped to the aggregate and to the operation, and every
     * lifecycle command uses the same operation, so the kind has to be folded into the key or a
     * {@code Pause} and a {@code Stop} sharing a client key would occupy one slot — and ADR 0038 D11
     * says in as many words that the same key used for two kinds is two keys, because they are two
     * decisions.</p>
     */
    private static String ledgerKey(LifecycleCommand command) {
        return command.kind().name() + ":" + command.idempotencyKey();
    }

    private static String identity(Record record) {
        return identity(record.generation(), record.desired().kind().name());
    }

    private static String identity(long generation, String decision) {
        return "g" + generation + "/" + decision;
    }

    private Record read(String tenantId, DeploymentId deploymentId) {
        return await(registry.get(tenantId, deploymentId)).orElseThrow(() ->
                new DeploymentRegistry.RegistryException(new DeploymentRegistry.FailureReason.NotFound()));
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.isZero()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static <T> T await(CompletionStage<T> stage) {
        return DeploymentOwnership.await(stage);
    }
}
