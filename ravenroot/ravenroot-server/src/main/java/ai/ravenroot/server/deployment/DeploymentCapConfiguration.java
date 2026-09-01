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
 * <h2>The default is a provisional placeholder, not a computed derivation -- said explicitly so it is
 * not mistaken for one</h2>
 * <p>Of the Shape 2 formula's four terms, three are implemented, cited and independently verifiable:
 * the 6s drain grace period, the 30s Pekko {@code ActorSystem}-level close bound (three sequential
 * 10s-bounded phases -- drain, cancel stragglers, await termination -- inside
 * {@code PekkoExecutionEngine.close()}, `ravenroot-pekko/src/main/java/ai/ravenroot/pekko/PekkoExecutionEngine.java:55,242,252,262`),
 * and the 10s HTTP stop delay. The fourth term, {@code D} -- the bound on closing the deployment
 * domains themselves -- belongs to the deployment-scoped spawn/close boundary in ADR 0021 and
 * carries no citation anywhere in this tree. Deriving a cap value that is actually sized from the formula requires {@code D};
 * inventing a number here and presenting it as formula-derived would make an unsupported claim
 * about code that does not yet exist. So {@link
 * #DEFAULT_MAX_ACTIVE_DEPLOYMENTS} is a conservative, explicitly-labelled placeholder chosen to be
 * small enough not to be a de facto unlimited cap while {@code D} is undefined, not a value computed
 * from {@code 6 + D + 30 + 10}. <strong>Recompute it when {@code D} has a citation.</strong></p>
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
     * Provisional. See the class Javadoc's "default is a provisional placeholder" section -- this is
     * not derived from the Shape 2 formula, because the formula's {@code D} term is undefined.
     * Chosen only to be a real, finite, operationally-plausible ceiling rather than an accidental
     * unlimited default.
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
