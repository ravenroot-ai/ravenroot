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
 * {@code terminationGracePeriodSeconds} itself from the pre-deployment shutdown sequence
 * (`docs/runbooks/plat-02-readiness-drain-and-alerts.md` §2) -- one arithmetic relationship, two
 * consumers, and this class owns the constant jointly with that deployment configuration per the ADR.</p>
 *
 * <p>the design rejects a fixed number outright and asked for something proportional to horizontal
 * scaling and ideally derived from the pods actually observed (an autoscaling form). Under Shape 2
 * (ADR 0021 D1) the cap is deliberately <strong>per pod, independent of deployment count</strong>: the
 * documented Shape 2 shutdown time is {@code ≈ 6 + D + 30 + 10} seconds
 * (`docs/runbooks/plat-02-readiness-drain-and-alerts.md` §2, "Shape 2" subsection; the deployment-admission contract), and
 * that formula has no term that grows with the number of active deployments. An autoscaling form
 * needs a registry authoritative for the observed pod set; no such authority is composed here, so
 * this is necessarily a static per-pod operator setting carrying the same definition. This class
 * supplies the definition and configuration surface, not the autoscaling derivation.</p>
 *
 * <h2>{@code D} is now cited, and the formula is fixed -- 66s -- independent of the active-deployment
 * count {@code M}</h2>
 * <p>All four terms of the Shape 2 formula are now implemented, cited and independently verifiable.
 * The three already known: the 6s drain grace period, the 30s Pekko {@code ActorSystem}-level close
 * bound (three sequential 10s-bounded phases -- drain, cancel stragglers, await termination -- inside
 * {@code PekkoExecutionEngine.close()}, `ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java:55,242,252,262`),
 * and the 10s HTTP stop delay. The fourth, {@code D} -- the bound on closing one deployment's domain --
 * is <strong>20s</strong>: two sequential {@code TERMINATION_BOUND_SECONDS = 10}-bounded phases (settle
 * every member via {@code stop()}, escalate to {@code cancel()} only if that timed out) inside
 * {@code SubtreeDomain.close()} in both adapters --
 * `ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java:56,581-608` and
 * `ravenroot-akka/src/main/java/ai/ravenroot/akka/AkkaExecutionEngine.java:92,620-647` -- the same
 * two-phase, ten-second-bounded shape the already-cited 30s {@code ActorSystem} bound uses, one level
 * down. That gives a fixed worst-case Shape 2 total of {@code 6 + 20 + 30 + 10 = 66} seconds.</p>
 *
 * <p><b>Fixed, not merely bounded: {@code D} does not multiply by {@code M}.</b> {@code D} is the cost
 * of closing <em>one</em> deployment's domain, and {@link ai.ravenroot.api.execution.ExecutionDomain}
 * requires that closing one domain "must not be delayed by another closing concurrently" -- multiple
 * domains on a pod settle together, not end to end. That is no longer an assumed property: {@code
 * ExecutionEngineContract.closesDomainsConcurrentlyRatherThanInSeries()}
 * (`ravenroot-engine-testkit/src/main/java/ai/ravenroot/testkit/ExecutionEngineContract.java`) proves it
 * deterministically -- via a barrier every domain's close must pass through together, not by comparing
 * elapsed time -- for both supported engines. Without that guarantee the 66s figure above would need a
 * {@code + (M-1) * D} term and would not be a constant; with it, {@code D} contributes exactly once
 * regardless of how many deployments are active.</p>
 *
 * <h2>The cap is deliberately decoupled from the shutdown-budget formula</h2>
 * <p>Precisely because the formula above has no {@code M} term, there is nothing in it to solve for
 * {@link #DEFAULT_MAX_ACTIVE_DEPLOYMENTS}: any cap value leaves the pod's worst-case graceful-shutdown
 * time at the same fixed 66 seconds, so long as {@code terminationGracePeriodSeconds} is configured no
 * lower than that. That is the payoff of M-independence, not a gap still waiting on {@code D} -- {@code
 * D} was the missing citation, and citing it does not turn the formula into one that bounds {@code M}.
 * {@link #DEFAULT_MAX_ACTIVE_DEPLOYMENTS} therefore stays {@code 8}: not computed from {@code 6 + D + 30
 * + 10} (it cannot be, that expression has no {@code M} in it to invert), but a separate, explicitly
 * conservative per-pod ceiling justified on its own terms -- bounding per-pod resource footprint
 * (actor/thread and heap cost per active deployment) rather than shutdown time, small enough not to be a
 * de facto unlimited cap, and large enough to be operationally usable. An operator with a larger or
 * smaller resource budget overrides it via {@link #MAX_ACTIVE_DEPLOYMENTS_VARIABLE}; nothing about that
 * override interacts with {@code terminationGracePeriodSeconds}, which only needs to clear the fixed 66s
 * figure above.</p>
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
     * See the class Javadoc's "The cap is deliberately decoupled from the shutdown-budget formula"
     * section -- not derived from the Shape 2 formula, because that formula has no {@code M} term to
     * invert (that is the point of domains closing concurrently). A real, finite, operationally-plausible
     * per-pod ceiling on resource footprint rather than a value computed from {@code 6 + D + 30 + 10}.
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
