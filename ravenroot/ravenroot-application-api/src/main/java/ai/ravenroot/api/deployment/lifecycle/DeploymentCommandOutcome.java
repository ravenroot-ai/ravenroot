package ai.ravenroot.api.deployment.lifecycle;

import ai.ravenroot.api.deployment.registry.DeploymentRegistry.ObservedKind;

/**
 * The nine answers a {@link LifecycleCommand} can receive (ADR 0038 D4).
 *
 * <h2>Returned as a value, never thrown</h2>
 * <p>Eight of these nine are outcomes a correct caller must plan for: a command can converge, replay,
 * collide with another client's use of the same key, arrive against a generation that has moved, be
 * overtaken by a higher-precedence command, be refused for a reason drawn from a closed vocabulary,
 * or find the deployment already ended. None of that is exceptional. Throwing them would move the
 * ordinary answers of this contract onto the path reserved for the ones nobody anticipated, and would
 * discard the exhaustiveness check that is the whole reason the hierarchy is sealed. {@code Failed}
 * is included in the same hierarchy rather than left as the one exception, because a caller that must
 * already handle eight answers gains nothing from a ninth arriving by a different mechanism.</p>
 *
 * <p>{@code DeploymentRegistry.RegistryException} is unchanged and keeps its own role: it reports
 * that the <em>store</em> could not answer. That is a different event from the lifecycle answering
 * "no", and collapsing the two would make a durable-storage outage indistinguishable from a
 * deployment declining a command.</p>
 *
 * <h2>Three answers this hierarchy deliberately does not contain</h2>
 * <p>{@code Unauthorized} is not here: authorization is decided by the surface that authenticated the
 * caller, before a lifecycle command exists, and answering it from inside this hierarchy would let a
 * client probe which deployments exist by reading which refusal it got. {@code Fenced} and
 * {@code LeaseLost} are not here either: both describe the relationship between an internal owner and
 * the authority, which a client neither holds nor can act on. A coordinator that loses its fence
 * steps down and the surviving owner answers; that is an internal reconciliation event, not
 * something to report to the operator as the fate of their command.</p>
 */
public sealed interface DeploymentCommandOutcome {

/**
 * Returns the identity of the command this outcome answers for.
 *
 * <p>This is the coordinator-minted identity of an accepted command, not the client's idempotency
 * key: the key names a ledger slot scoped to {@code (tenant, deployment, kind)} and is reused by
 * design across retries, while this identifies the one decision that was actually recorded. It is
 * empty for the answers that record no decision.</p>
 * @return command identity, or {@code null} when this outcome recorded no command.
 */
    default String commandId() { return null; }

    /**
     * The command was accepted and advanced the lifecycle from one generation to the next.
     *
     * <p>{@code toGeneration} is always {@code fromGeneration + 1}: a lifecycle move is one step, and
     * a gap would mean a decision was applied that nothing recorded. The constructor enforces it
     * rather than documenting it, because a coordinator that computed the pair wrongly would
     * otherwise publish a plausible-looking lie.</p>
     * @param commandId coordinator-minted identity of the accepted command.
     * @param fromGeneration generation the command was decided against.
     * @param toGeneration generation the deployment now carries.
     */
    record Accepted(String commandId, long fromGeneration, long toGeneration) implements DeploymentCommandOutcome {
        /** Requires an identity and a single-step, non-negative generation advance. */
        public Accepted {
            identity(commandId);
            if (fromGeneration < 0 || toGeneration != fromGeneration + 1) {
                throw new IllegalArgumentException("an accepted command advances the generation by exactly one");
            }
        }
    }

    /**
     * The deployment was already at the level the command asked for, so nothing moved.
     *
     * <p>Distinct from {@link Replayed}, which is a different fact: a replay found <em>this</em>
     * command's own recorded outcome, while a convergence found the deployment already where it was
     * being asked to go, whether or not any command put it there. Reporting convergence as acceptance
     * would tell a caller a generation advanced when it did not, and every consumer keying on that
     * generation would then be one step ahead of the authority.</p>
     * @param commandId coordinator-minted identity of the command that observed convergence.
     * @param generation generation at which the deployment was already converged.
     * @param observed runtime state observed at that generation.
     */
    record Converged(String commandId, long generation, ObservedKind observed) implements DeploymentCommandOutcome {
        /** Requires an identity, a non-negative generation, and an observation. */
        public Converged {
            identity(commandId);
            if (generation < 0) throw new IllegalArgumentException("generation cannot be negative");
            if (observed == null) throw new IllegalArgumentException("Converged requires an observation");
        }
    }

    /**
     * The idempotency key and digest matched a decision already recorded; this is that decision.
     *
     * <p>The original is carried whole rather than summarized, so a retrying client receives exactly
     * what the first attempt received — including which generation it advanced — instead of a
     * second, weaker answer it would have to reconcile against the first. Nesting is rejected: a
     * replay of a replay has no meaning the inner value does not already carry, and permitting it
     * would let a chain grow without bound across retries.</p>
     * @param original the outcome recorded for the first accepted delivery of this command.
     */
    record Replayed(DeploymentCommandOutcome original) implements DeploymentCommandOutcome {
        /** Requires a non-null original that is not itself a replay. */
        public Replayed {
            if (original == null) throw new IllegalArgumentException("Replayed requires the original outcome");
            if (original instanceof Replayed) throw new IllegalArgumentException("a replay cannot nest a replay");
        }
        @Override public String commandId() { return original.commandId(); }
    }

    /**
     * The idempotency key was already used, within its scope, for a different command body.
     *
     * <p>The digest covers the generation the command was decided against, so this is also the answer
     * for the same client retrying the same words after the lifecycle moved underneath it. That is
     * the case the outcome exists for: a silent replay would tell the client its newer decision had
     * been applied when the older one had.</p>
     * @param key the client-chosen idempotency key that collided.
     */
    record IdempotencyConflict(String key) implements DeploymentCommandOutcome {
        /** Requires the colliding key so a caller can name it without parsing a message. */
        public IdempotencyConflict {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("IdempotencyConflict requires a key");
        }
    }

    /**
     * The command was decided against a generation the deployment has since left.
     *
     * <p>Both numbers are reported because the caller's next action depends on the distance: one step
     * behind is an ordinary race to re-read and retry, while a large gap says the caller has been
     * making decisions against a stale view for some time and should stop rather than retry into it.
     * The comparison is exact equality, never {@code >=} — see {@link LifecycleCommand}'s barrier
     * section for why an ordering test is the wrong instrument here.</p>
     * @param expected generation the refused command was decided against.
     * @param current generation the deployment actually carries.
     */
    record StaleGeneration(long expected, long current) implements DeploymentCommandOutcome {
        /** Requires two non-negative generations that genuinely differ. */
        public StaleGeneration {
            if (expected < 0 || current < 0) throw new IllegalArgumentException("generations cannot be negative");
            if (expected == current) {
                throw new IllegalArgumentException("a matching generation is not stale");
            }
        }
    }

    /**
     * A higher-precedence command took the deployment before this one could converge.
     *
     * <p>The distinction from {@link Refused} is the caller's next move: a refusal says this command
     * will not apply as asked, while supersession says a different, ranking decision applied instead
     * and names it, so the caller can go and look at what actually happened rather than retrying into
     * a decision that has already been made for it (ADR 0038 D7).</p>
     * @param bySupersedingLevel the level (formatted {@code "g<generation>/<DesiredKind>"}) the
     *     superseding decision left the deployment at. This is a level, not an identity: the record
     *     durably carries the desired level, not the kind or identity of the command that set it, so
     *     that is what a coordinator has on hand to name here (see the known limit documented on
     *     {@code DeploymentCoordinator.supersession}).
     * @param generation generation at which the superseding command applied.
     */
    record Superseded(String bySupersedingLevel, long generation) implements DeploymentCommandOutcome {
        /** Requires the superseding level and a non-negative generation. */
        public Superseded {
            identity(bySupersedingLevel);
            if (generation < 0) throw new IllegalArgumentException("generation cannot be negative");
        }
        @Override public String commandId() { return null; }
    }

    /**
     * The command will not be applied, for a reason drawn from a closed vocabulary.
     * @param reason closed-vocabulary refusal reason.
     */
    record Refused(Reason reason) implements DeploymentCommandOutcome {
        /** Requires a reason; a refusal without one is not actionable. */
        public Refused {
            if (reason == null) throw new IllegalArgumentException("Refused requires a reason");
        }
    }

    /**
     * Why a command was refused. Closed on purpose: a caller must be able to branch on the whole set.
     *
     * <p>A free-text reason would make every consumer a string parser and would break silently the
     * first time the wording improved — the same rule {@code DeploymentState} already states about
     * its own cause.</p>
     */
    enum Reason {
        /** The deployment's current level cannot reach the requested one, such as resuming what is not paused. */
        IncompatibleState,
        /** An undeploy arrived without stating what happens to work still in flight (ADR 0038 D7). */
        MissingDisposition,
        /** A service-scoped shutdown intent is in effect; no further lifecycle command is accepted. */
        ShuttingDown,
        /** Accepting would exceed a declared capacity bound. */
        CapacityExceeded,
        /** The deployment has been removed; its aggregate accepts no further command. */
        Tombstoned
    }

    /**
     * Applying the command failed, reported by a classifier rather than by a message.
     *
     * <p>The classifier is drawn from the failure's class name and never from its message, the same
     * rule {@code DurableExecutionResult} follows for the identical reason: a message may carry
     * payload fragments, credentials, or author-controlled text, and this value reaches operator
     * surfaces and logs, which are exactly the places a leaked secret persists.</p>
     * @param classifiedCause bounded, sanitized classification of what failed.
     */
    record Failed(String classifiedCause) implements DeploymentCommandOutcome {
        /** Requires a bounded, control-character-free classifier. */
        public Failed {
            if (classifiedCause == null || !classifiedCause.matches("[A-Za-z_$][A-Za-z0-9_$.]{0,254}")) {
                throw new IllegalArgumentException("Failed requires a sanitized classifier, never a message");
            }
        }
    }

    /**
     * The deployment reached its absorbing end: it is removed, and no command will ever be accepted.
     *
     * <p>This is the <em>success</em> answer for an undeploy that converged to
     * {@code DesiredKind.REMOVED}, and it is not the same fact as {@link Reason#Tombstoned}. That
     * refusal answers a <em>different</em> command that arrived after removal and was declined;
     * this answers the command that performed the removal. Collapsing the two would make the operator
     * who successfully removed a deployment and the operator whose command arrived a second too late
     * receive the identical reply.</p>
     * @param commandId identity of the command that ended the lifecycle.
     * @param generation generation at which the deployment was removed.
     */
    record Terminal(String commandId, long generation) implements DeploymentCommandOutcome {
        /** Requires an identity and a non-negative terminal generation. */
        public Terminal {
            identity(commandId);
            if (generation < 0) throw new IllegalArgumentException("generation cannot be negative");
        }
    }

    private static void identity(String commandId) {
        if (commandId == null || commandId.isBlank() || commandId.length() > 128
                || commandId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid command identity");
        }
    }
}
