package ai.ravenroot.api.deployment.lifecycle;

import ai.ravenroot.api.deployment.registry.DeploymentRegistry.DesiredKind;
import ai.ravenroot.api.deployment.registry.DeploymentRegistry.UpdateStrategy;
import ai.ravenroot.api.deployment.registry.GenerationExpectation;

import java.time.Duration;
import java.util.Optional;

/**
 * The eight things an operator can ask of a deployment (ADR 0038 D5, D7, D11).
 *
 * <h2>Two natures, not eight special cases</h2>
 * <p>Six of these commands are <b>desired-state commands</b>: they name a lifecycle level and ask
 * the deployment to converge to it, so applying the same one twice is applying it once. Two —
 * {@link Cancel} and {@link Restart} — are <b>barriers</b>: they leave the desired level exactly
 * where it was and instead capture a generation, which is what separates the work already admitted
 * from the work that arrives next. Asking a barrier to be a level would force a level to mean
 * "running, but the second time", and asking a level to be a barrier would make a deployment that is
 * already running have nothing to converge to.</p>
 *
 * <p>{@link #targetLevel()} is present exactly for the six and empty exactly for the two, and
 * {@link #barrier()} is its complement. Both are on the interface rather than recovered by a
 * {@code switch} at every call site, because a caller that has to enumerate the hierarchy to learn
 * which nature it is holding will eventually enumerate it wrongly.</p>
 *
 * <h2>The barrier is half-open</h2>
 * <p>A barrier captured at generation {@code G} closes admission at {@code G}, lets the work already
 * admitted complete <em>under</em> {@code G}, and admits everything that arrives afterwards at
 * {@code G + 1}. The comparison is exact equality and never {@code >=}: work carrying {@code G}
 * belongs to the closing side and work carrying {@code G + 1} to the opening side, and an ordering
 * test would place a much older {@code G - 2} on the closing side of a barrier it predates
 * entirely.</p>
 *
 * <h2>Precedence</h2>
 * <p>{@code Shutdown > Stop > Cancel > Drain > Pause}. For the desired-state commands this falls out
 * of {@link DesiredKind#restriction()} directly. {@code Cancel}, being a barrier, has no level, so
 * its rank is stated here as {@link #precedence()} on the same scale, sitting between {@code Stop}
 * and {@code Drain} — a cancellation outranks a drain because it stops the work a drain was waiting
 * for, and is outranked by a stop because a stop subsumes it. Service shutdown is not in this
 * hierarchy at all: it is service-scoped, carries its own epoch on the model of
 * {@code AgentAuthorityControl.epoch}, and is not a deployment generation (ADR 0038 D7).</p>
 *
 * <h2>Idempotency</h2>
 * <p>{@link #idempotencyKey()} is chosen by the client and scoped to
 * {@code (tenant, deployment, kind)}: the same key used for a {@code Pause} and for a {@code Stop}
 * is two keys, because they are two decisions. What the key <em>protects</em> is the digest, and the
 * digest is taken over {@link #canonicalBody()} together with the generation the command was decided
 * against — see {@link #canonicalForm(LifecycleCommand, GenerationExpectation)} for why the
 * generation is inside it and not beside it.</p>
 */
public sealed interface LifecycleCommand {

    /** The closed command vocabulary, and the third component of an idempotency key's scope. */
    enum Kind {
        /** Bring the deployment to {@link DesiredKind#RUNNING} on a named version. */
        START,
        /** Close admission and retain accepted work, resumably. */
        PAUSE,
        /** Return a paused deployment to {@link DesiredKind#RUNNING}. */
        RESUME,
        /** Close admission and carry accepted work to completion. */
        DRAIN,
        /** Close admission, finish, and release resources. */
        STOP,
        /** Remove the deployment, leaving an auditable tombstone. */
        UNDEPLOY,
        /** Barrier: end the work admitted so far without changing the desired level. */
        CANCEL,
        /** Barrier: replace the current activation without changing the desired level. */
        RESTART
    }

    /** Precedence rank of {@link Cancel} on {@link DesiredKind#restriction()}'s scale. */
    int CANCEL_PRECEDENCE = 25;

    /** Longest accepted idempotency key, matching the bound the registry's ledger keys already use. */
    int MAXIMUM_KEY_LENGTH = 128;

    /** Longest accepted sanitized operator reason, matching {@code DeploymentRegistry.Failure}. */
    int MAXIMUM_REASON_LENGTH = 256;

/**
 * Returns the client-chosen idempotency key, scoped to {@code (tenant, deployment, kind)}.
 * @return non-blank, bounded, control-character-free key.
 */
    String idempotencyKey();

/**
 * Returns the closed-vocabulary kind, which is the third component of the idempotency scope.
 * @return this command's kind.
 */
    Kind kind();

/**
 * Returns the level this command converges to, empty for a barrier.
 * @return target lifecycle level, or empty when this command is a barrier.
 */
    Optional<DesiredKind> targetLevel();

/**
 * Returns the sanitized operator reason, when this command carries one.
 *
 * <p>Named for the audience rather than for the field, because four of these records already have a
 * component called {@code reason} whose generated accessor returns the bare {@code String}.</p>
 * @return bounded operator-facing reason, or empty.
 */
    Optional<String> operatorReason();

/**
 * Returns the deterministic rendering of this command's body, excluding the idempotency key.
 *
 * <p>The key names the ledger slot; the body is what the slot must be found to contain. Folding the
 * key into its own digest would make every replay compare equal to itself and detect nothing.</p>
 * @return stable canonical rendering of this command's decided content.
 */
    String canonicalBody();

/**
 * Whether this command is a barrier rather than a desired-state command.
 * @return whether this command captures a generation instead of naming a level.
 */
    default boolean barrier() {
        return targetLevel().isEmpty();
    }

/**
 * Returns this command's precedence rank, on {@link DesiredKind#restriction()}'s scale.
 * @return rank used to decide which of two competing commands governs.
 */
    default int precedence() {
        return targetLevel().map(DesiredKind::restriction).orElse(CANCEL_PRECEDENCE);
    }

    /**
     * Renders the exact input the idempotency digest is taken over.
     *
     * <p>The generation is inside the digest rather than beside it because a command decided against
     * generation 4 and the identical command decided against generation 5 are different decisions
     * that a client, quite reasonably, gives the same key: "stop this deployment", retried after the
     * lifecycle moved underneath it. If the generation were outside, the second would replay the
     * first's recorded outcome and the client would be told its newer decision had been applied when
     * it had not. Inside, the second is an
     * {@code DeploymentCommandOutcome.IdempotencyConflict} — a visible, actionable answer instead of
     * a silent wrong one (ADR 0038 D11).</p>
     *
     * <p>Fields are separated by {@code \0} rather than by a space, on the precedent of the
     * registry's own cursor encoding, because an operator reason is free text that may contain
     * spaces: with a space separator, a {@code Stop} whose reason ends in "urgent" and one whose
     * reason ends in "urgent " would be different decisions rendering to the same form, and the
     * digest that is supposed to detect exactly that difference would not.</p>
     *
     * @param command command whose body is being digested.
     * @param expectedGeneration generation the command was decided against.
     * @return canonical, deterministic digest input.
     */
    static String canonicalForm(LifecycleCommand command, GenerationExpectation expectedGeneration) {
        if (command == null || expectedGeneration == null) {
            throw new IllegalArgumentException("canonical form requires a command and a generation expectation");
        }
        String generation = switch (expectedGeneration) {
            case GenerationExpectation.Any ignored -> "any";
            case GenerationExpectation.Exactly exact -> Long.toString(exact.generation());
        };
        return "rr1\0" + command.kind().name() + "\0" + command.canonicalBody() + "\0" + generation;
    }

    /**
     * Activate the deployment on one named version.
     *
     * <p>Carries the version and the update strategy because {@link DesiredKind#RUNNING} is the only
     * level that selects a version at all, and a {@code Desired} of that kind is rejected without
     * them.</p>
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     * @param version graph version to activate, at least one.
     * @param updateStrategy how an existing activation is replaced.
     */
    record Start(String idempotencyKey, long version, UpdateStrategy updateStrategy) implements LifecycleCommand {
        /** Requires a valid key, a positive version, and an explicit update strategy. */
        public Start {
            key(idempotencyKey);
            if (version < 1) throw new IllegalArgumentException("version must be at least 1");
            if (updateStrategy == null) throw new IllegalArgumentException("Start requires an update strategy");
        }
        @Override public Kind kind() { return Kind.START; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.of(DesiredKind.RUNNING); }
        @Override public Optional<String> operatorReason() { return Optional.empty(); }
        @Override public String canonicalBody() { return version + "\0" + updateStrategy.name(); }
    }

    /**
     * Close admission and retain accepted work, resumably.
     *
     * <p>The reason lives here and not on {@code DeploymentStatus}, whose invariant is that only
     * {@code DEGRADED} and {@code FAILED} carry a cause. An operator pause has a reason and is
     * neither degraded nor failed; widening that invariant to admit it would tell every probe and
     * dashboard that a cause may now appear on a healthy state, for the benefit of one case whose
     * reason is already recorded on the command that caused it (ADR 0038 D5).</p>
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     * @param reason sanitized, bounded operator-facing explanation.
     */
    record Pause(String idempotencyKey, String reason) implements LifecycleCommand {
        /** Requires a valid key and a sanitized reason. */
        public Pause {
            key(idempotencyKey);
            requireReason(reason);
        }
        @Override public Kind kind() { return Kind.PAUSE; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.of(DesiredKind.PAUSED); }
        @Override public Optional<String> operatorReason() { return Optional.of(reason); }
        @Override public String canonicalBody() { return reason; }
    }

    /**
     * Return a paused deployment to {@link DesiredKind#RUNNING}.
     *
     * <p>Valid only from {@link DesiredKind#PAUSED}; from any other level it is answered
     * {@code Refused(IncompatibleState)} (ADR 0038 D7). It names no version deliberately: a resume
     * continues the activation the pause held, and letting it name one would make it a disguised
     * {@link Start} whose version silently disagreed with what is actually loaded.</p>
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     */
    record Resume(String idempotencyKey) implements LifecycleCommand {
        /** Requires a valid key. */
        public Resume { key(idempotencyKey); }
        @Override public Kind kind() { return Kind.RESUME; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.of(DesiredKind.RUNNING); }
        @Override public Optional<String> operatorReason() { return Optional.empty(); }
        @Override public String canonicalBody() { return ""; }
    }

    /**
     * Close admission and carry accepted work to completion within a bound.
     *
     * <p>The bound is mandatory and positive: an unbounded drain is a deployment that never reaches
     * its own target, and "wait forever" is a decision an operator should have to write down.</p>
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     * @param bound positive maximum time accepted work is given to finish.
     */
    record Drain(String idempotencyKey, Duration bound) implements LifecycleCommand {
        /** Requires a valid key and a strictly positive drain bound. */
        public Drain {
            key(idempotencyKey);
            if (bound == null || bound.isNegative() || bound.isZero()) {
                throw new IllegalArgumentException("Drain requires a positive bound");
            }
        }
        @Override public Kind kind() { return Kind.DRAIN; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.of(DesiredKind.DRAINED); }
        @Override public Optional<String> operatorReason() { return Optional.empty(); }
        @Override public String canonicalBody() { return bound.toString(); }
    }

    /**
     * Close admission, finish, and release the deployment's resources.
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     * @param reason sanitized, bounded operator-facing explanation.
     */
    record Stop(String idempotencyKey, String reason) implements LifecycleCommand {
        /** Requires a valid key and a sanitized reason. */
        public Stop {
            key(idempotencyKey);
            requireReason(reason);
        }
        @Override public Kind kind() { return Kind.STOP; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.of(DesiredKind.STOPPED); }
        @Override public Optional<String> operatorReason() { return Optional.of(reason); }
        @Override public String canonicalBody() { return reason; }
    }

    /**
     * Remove the deployment, leaving an auditable tombstone.
     *
     * <h2>The disposition is nullable on purpose</h2>
     * <p>ADR 0038 D7 requires an undeploy to state explicitly what happens to work that is still in
     * flight. That rule is enforced as an <em>outcome</em> — {@code Refused(MissingDisposition)} —
     * rather than as a constructor rejection, because an outcome is the only form in which the rule
     * can reach the caller who broke it. A decoded request that omitted the field has to become some
     * value before anything can answer it; a record that refused to hold it would force the decoder
     * to invent an error shape of its own, outside the closed vocabulary this contract exists to
     * publish, and would make {@code MissingDisposition} unreachable and therefore untestable.</p>
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     * @param disposition what happens to work still in flight, or {@code null} when unstated.
     * @param reason sanitized, bounded operator-facing explanation.
     */
    record Undeploy(String idempotencyKey, Disposition disposition, String reason) implements LifecycleCommand {

        /** What an undeploy does with work that has been admitted and has not finished. */
        public enum Disposition {
            /** Carry admitted work to completion first, then remove. */
            DRAIN_FIRST,
            /** End admitted work now, then remove. */
            CANCEL_IN_FLIGHT,
            /** Remove only if nothing is in flight; otherwise refuse. */
            REFUSE_IF_BUSY
        }

        /** Requires a valid key and a sanitized reason; an absent disposition is answered, not thrown. */
        public Undeploy {
            key(idempotencyKey);
            requireReason(reason);
        }
        @Override public Kind kind() { return Kind.UNDEPLOY; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.of(DesiredKind.REMOVED); }
        @Override public Optional<String> operatorReason() { return Optional.of(reason); }
        @Override public String canonicalBody() {
            return (disposition == null ? "" : disposition.name()) + "\0" + reason;
        }
    }

    /**
     * Barrier: end the work admitted so far, leaving the desired level untouched.
     *
     * <p>A deployment that is running stays running: cancel answers "not this work", never "not this
     * deployment". Expressing it as a level would require a level meaning "running, having discarded
     * what was in flight", which is not a state anything can converge to — it is an event.</p>
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     * @param reason sanitized, bounded operator-facing explanation.
     */
    record Cancel(String idempotencyKey, String reason) implements LifecycleCommand {
        /** Requires a valid key and a sanitized reason. */
        public Cancel {
            key(idempotencyKey);
            requireReason(reason);
        }
        @Override public Kind kind() { return Kind.CANCEL; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.empty(); }
        @Override public Optional<String> operatorReason() { return Optional.of(reason); }
        @Override public String canonicalBody() { return reason; }
    }

    /**
     * Barrier: replace the current activation, leaving the desired level untouched.
     *
     * <p>Not a {@code Stop} followed by a {@code Start}: those are two decisions, and a failure
     * between them leaves a deployment an operator asked to keep running durably stopped. As a
     * barrier the desired level never leaves {@code RUNNING}, so a coordinator that dies mid-restart
     * reconciles back towards running rather than towards whatever the first half of the pair
     * happened to write.</p>
     * @param idempotencyKey client-chosen key, scoped to this deployment and kind.
     */
    record Restart(String idempotencyKey) implements LifecycleCommand {
        /** Requires a valid key. */
        public Restart { key(idempotencyKey); }
        @Override public Kind kind() { return Kind.RESTART; }
        @Override public Optional<DesiredKind> targetLevel() { return Optional.empty(); }
        @Override public Optional<String> operatorReason() { return Optional.empty(); }
        @Override public String canonicalBody() { return ""; }
    }

    private static void key(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > MAXIMUM_KEY_LENGTH
                || idempotencyKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid idempotency key");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAXIMUM_REASON_LENGTH
                || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid sanitized reason");
        }
    }
}
