package ai.ravenroot.server.deployment;

import java.util.Map;

/**
 * CORE-LIFECYCLE, the deployment-admission contract. The operator-facing cap on active long-lived deployments per
 * pod, in the same {@code fromEnvironment(Map)} idiom as {@code ReadinessConfiguration},
 * {@code RateLimitConfiguration}, {@code AuthenticationConfiguration} and {@code TelemetryConfiguration}
 * (`ravenroot-server/src/main/java/ai/ravenroot/server/readiness/ReadinessConfiguration.java:67-73`
 * is the pattern this class follows).
 *
 * <h2>Where this is read, and where it deliberately is not</h2>
 * <p><strong>{@code ravenroot-server} reads the environment; {@code ravenroot-core} never does.</strong>
 * This class parses {@value #MAX_ACTIVE_DEPLOYMENTS_VARIABLE} and hands core activation admission
 * a plain {@code int}, as required by the server/core boundary. That split is deliberate, not incidental:
 * where platform configuration lives remains a platform boundary, and letting
 * {@code ravenroot-core}/{@code ravenroot-application-api}
 * read an environment variable directly would answer that question by accident, from inside an
 * unrelated layer. Keeping the read here, exactly like every other {@code *Configuration} class in
 * this package, keeps it from answering it at all.</p>
 *
 * <h2>The definition (the deployment-admission contract)</h2>
 * <p>"The maximum number of active deployments on a pod such that the pod's worst-case graceful
 * shutdown completes within its {@code terminationGracePeriodSeconds}." The same formula sizes
 * {@code terminationGracePeriodSeconds} itself from the pod's actual shutdown sequence (see
 * {@code ai.ravenroot.server.GracefulShutdown}'s own Javadoc for the ordered call chain this class's
 * formula below is derived from) -- one arithmetic relationship, two consumers.
 * ({@code docs/runbooks/plat-02-readiness-drain-and-alerts.md} does not exist in this tree; do not
 * follow that path from older comments in this codebase -- {@code GracefulShutdown} and this class are
 * the actual source of truth.)</p>
 *
 * <h2>The formula: {@code M}-dependent, because the pod's own shutdown sequence closes deployments
 * ONE AT A TIME, not concurrently</h2>
 * <p><b>Corrected after being disproved by reading the shutdown path itself.</b> An earlier version of
 * this Javadoc claimed a fixed, {@code M}-independent total (6 + D + 30 + 10 = 66 seconds), reasoning
 * from {@link ai.ravenroot.api.execution.ExecutionDomain}'s engine-level guarantee that one domain's
 * {@code close()} is not delayed by another closing concurrently -- a real, now deterministically
 * tested property (see {@code ExecutionEngineContract.closesDomainsConcurrentlyRatherThanInSeries()}).
 * That property is true, and it does not apply here: nothing in this pod's actual shutdown path
 * exercises it. {@code DefaultRavenrootApplication.close()} iterates {@code deployments.values()}
 * sequentially and blocks on each deployment's own shutdown stage --
 * {@code ending.toCompletableFuture().get(30, SECONDS)} -- before starting the next
 * (`ravenroot-core/src/main/java/ai/ravenroot/core/runtime/DefaultRavenrootApplication.java:2382-2392`);
 * a second deployment's domain is never even asked to close while the first is still closing. Each
 * iteration's own bound is exactly that 30-second {@code get} -- it is what makes one slow deployment's
 * shutdown finite from the loop's perspective, regardless of what {@code doStop()} does internally
 * (source stops, {@code GraphRunner.close()}, then {@code joinBounded(domain.close())} at up to another
 * 30s -- `ravenroot-core/src/main/java/ai/ravenroot/core/runtime/DefaultGraphDeployment.java:807,873-880`
 * -- all absorbed by the outer 30-second cap, which gives up and moves on regardless of how far internal
 * cleanup got). So the pod-level, deployment-count-dependent worst case is:
 * <pre>{@code
 *   podShutdownSeconds(M) = drainGrace(6) + httpStopDelay(10) + M * perDeploymentBound(30) + engineClose(30)
 *                          = 46 + 30 * M
 * }</pre>
 * Terms, in the order {@code GracefulShutdown.run} actually invokes them
 * (`ravenroot-server/src/main/java/ai/ravenroot/server/GracefulShutdown.java:60-69`): the 6s drain
 * grace period ({@code ReadinessConfiguration.DEFAULT_DRAIN_GRACE_PERIOD}), the 10s HTTP stop delay
 * ({@code RavenrootServer.close()}, `ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootServer.java:4578`,
 * which is where {@code application.close()} -- the sequential deployment loop above -- is reached from,
 * `RavenrootServer.java:4599`), the sequential {@code M * 30s} deployment loop just derived, and finally
 * the 30s engine-wide {@code ActorSystem}-level close (three sequential 10s-bounded phases -- drain,
 * cancel stragglers, await termination -- inside {@code PekkoExecutionEngine.close()},
 * `ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java:56,377,387,397`), which
 * {@code GracefulShutdown.run} invokes once, last, engine-wide, after {@code server.close()}'s cascade.</p>
 *
 * <p><b>Scope: this formula is silent on in-flight executions, deliberately matching the scope of every
 * earlier version of it.</b> {@code DefaultRavenrootApplication.close()} also iterates
 * {@code activeExecutions.values()} sequentially, before the deployments loop, and {@code
 * GracefulShutdown}'s own Javadoc already documents that as a separate, known, sequential concern
 * ("several in-flight executions are stopped one at a time, not in parallel"). Neither this formula nor
 * any version of it before it has ever included a term for that -- it was never part of what {@code
 * MAX_ACTIVE_DEPLOYMENTS} claims to bound, and still is not. {@code podShutdownSeconds(M)} above is a
 * lower bound on the pod's true worst case when executions are also in flight at the shutdown instant,
 * not a complete one.</p>
 *
 * <p><b>Why the domain-close bound {@code D} (nominally 20s: two sequential
 * {@code TERMINATION_BOUND_SECONDS = 10}-bounded phases inside {@code SubtreeDomain.close()} in both
 * adapters -- `ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java:56,581-608`
 * and `ravenroot-akka/src/main/java/ai/ravenroot/akka/AkkaExecutionEngine.java:92,620-647` -- followed
 * by an unbounded final step, {@code guardian.tell(new StopDomain(stopped))}, with no {@code orTimeout}
 * of its own: a single local actor-mailbox send, expected sub-millisecond, but not itself contractually
 * bounded, so {@code D} is "~20s plus an unbounded but practically negligible tail", not exactly 20s)
 * does not appear as its own term above:</b> it is real, correctly measured, and does not vanish -- it is
 * simply already inside, and smaller than, the 30-second outer {@code get} that
 * {@code DefaultRavenrootApplication.close()}'s deployment loop applies to each iteration. Citing it
 * separately here would double-count it. It remains the reason each iteration is bounded at all (rather
 * than only by the outer 30s cap giving up on a wedged domain) and it is what
 * {@code ExecutionEngineContract.closesDomainsConcurrentlyRatherThanInSeries()} measures and enforces at
 * the engine level.</p>
 *
 * <h2>The cap is recalculated from this formula, not decoupled from it</h2>
 * <p>Because {@code podShutdownSeconds(M)} genuinely grows with {@code M}, {@code
 * terminationGracePeriodSeconds} must be sized FOR the configured cap, not fixed independent of it.
 * {@link #DEFAULT_MAX_ACTIVE_DEPLOYMENTS} stays {@code 8}: not because the formula is independent of it
 * (it is not), but because {@code podShutdownSeconds(8) = 46 + 30*8 = 286} seconds is an operationally
 * reasonable pod termination grace period, and 8 concurrent long-lived deployments is an operationally
 * reasonable per-pod default. <strong>An operator raising {@code
 * RAVENROOT_MAX_ACTIVE_DEPLOYMENTS} above 8 must raise {@code terminationGracePeriodSeconds} to at
 * least {@code 46 + 30 * newCap} seconds, or a pod at the new cap can be SIGKILLed mid-drain with
 * deployments never given the chance to close.</strong> This is the opposite of the earlier, disproved
 * claim that any cap value left shutdown time unchanged -- that claim was true of the engine-level
 * domain-close property in isolation, and false of this pod's actual shutdown path, which does not
 * exercise concurrent domain closing across deployments at all. See
 * {@code deploy/kubernetes/ravenroot.yaml} and {@code deploy/helm/ravenroot/templates/deployment.yaml}
 * for where an operator sizing {@code terminationGracePeriodSeconds} for a non-default cap is pointed
 * back to this formula.</p>
 *
 * <h2>Engine coverage of the domain-close property this formula depends on</h2>
 * <p>{@code ExecutionEngineContract.closesDomainsConcurrentlyRatherThanInSeries()} runs, and passes,
 * against the Pekko adapter in this repository's normal build and test environment. THIS FILE IS NOT
 * COMPILED OR TESTED IN THIS REPOSITORY'S ENVIRONMENT: the {@code akka} Maven profile (which builds
 * {@code ravenroot-akka}, including its instance of that same shared contract test) is not part of the
 * default reactor and, as of this writing, cannot resolve {@code
 * com.typesafe.akka:akka-actor-typed_2.13:2.10.20} from Maven Central in this environment (see
 * {@code AkkaExecutionEngine.java:54-88} for the established form of this caveat). {@code
 * AkkaExecutionEngine.SubtreeDomain.close()} is structurally identical to Pekko's own implementation
 * cited above, by inspection, but that identity has not been exercised by a passing test run in this
 * environment.</p>
 *
 * <h2>Fail-closed rejection is a type, not a message</h2>
 * <p>Core activation admission rejects over-cap activation by throwing {@code
 * DeploymentAdmissionException} ({@code ai.ravenroot.api.deployment}) -- alert
 * wiring and this runbook's operational guidance key on that type, never on message text, the same
 * discipline {@link ai.ravenroot.server.readiness.ReadinessGate} already applies to {@code
 * DeploymentState}: the state (or here, the exception type) is the signal, the human-readable detail
 * is separate and never parsed.</p>
 */
public record DeploymentCapConfiguration(int maxActiveDeployments) {

    /**
     * See the class Javadoc's "The cap is recalculated from this formula, not decoupled from it"
     * section: {@code podShutdownSeconds(8) = 46 + 30*8 = 286} seconds, an operationally reasonable
     * pod {@code terminationGracePeriodSeconds}. Raising this default requires raising
     * {@code terminationGracePeriodSeconds} to match -- {@code 46 + 30 * newCap} -- not just this
     * value.
     */
    static final int DEFAULT_MAX_ACTIVE_DEPLOYMENTS = 8;

    /**
     * The key core activation admission reads to enforce the cap (fail-closed
     * {@code DeploymentAdmissionException} at activation), in the same {@code …_VARIABLE} naming
     * pattern as every constant in
     * {@link ai.ravenroot.server.readiness.ReadinessConfiguration}.
     */
    public static final String MAX_ACTIVE_DEPLOYMENTS_VARIABLE = "RAVENROOT_MAX_ACTIVE_DEPLOYMENTS";

    public DeploymentCapConfiguration {
        if (maxActiveDeployments < 0) {
            throw new IllegalArgumentException(
                    "maxActiveDeployments cannot be negative, got " + maxActiveDeployments);
        }
    }

    /**
     * Zero is a legitimate, deliberate choice: a pod that must run zero long-lived deployments (e.g.
     * one reserved for one-shot/playground submissions only, ADR 0021 D2) sets this to {@code 0} and
     * every activation is rejected, exactly as the fail-closed admission contract requires.
     */
    public static DeploymentCapConfiguration defaults() {
        return new DeploymentCapConfiguration(DEFAULT_MAX_ACTIVE_DEPLOYMENTS);
    }

    public static DeploymentCapConfiguration fromEnvironment(Map<String, String> environment) {
        java.util.Objects.requireNonNull(environment, "environment");
        String raw = environment.get(MAX_ACTIVE_DEPLOYMENTS_VARIABLE);
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        try {
            return new DeploymentCapConfiguration(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    MAX_ACTIVE_DEPLOYMENTS_VARIABLE + "='" + raw + "' is not a whole number", notANumber);
        }
    }
}
