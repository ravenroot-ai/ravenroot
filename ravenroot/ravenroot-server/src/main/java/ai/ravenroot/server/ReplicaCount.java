package ai.ravenroot.server;

import java.util.Map;
import java.util.Objects;

/**
 * The one parser for how many replicas a deployment runs.
 *
 * <h2>Why this class exists at all</h2>
 * <p>It exists because there were two parsers and they read different variables. {@code
 * RavenrootServerMain} read {@code RAVENROOT_REPLICAS}, which {@code compose.yaml},
 * {@code deploy/kubernetes/ravenroot.yaml}, the Helm deployment template and the OCR container smoke
 * script all set; the embed browser's multi-replica guard read {@code RAVENROOT_REPLICA_COUNT},
 * which nothing sets. The guard therefore always saw its own default of one, and its test stayed
 * green because the test set the variable the guard read rather than the one a deployment sets — a
 * guarantee asserted and never exercised. One constant and one parser is what stops that recurring:
 * a future reader cannot introduce a second spelling without deleting this class.</p>
 *
 * <h2>Malformed fails closed</h2>
 * <p>An unparseable or non-positive value throws instead of defaulting to one. A deployment that
 * mistyped its replica count is a deployment whose single-process assumptions are unverified, and
 * quietly assuming the safest-looking number is how the assumption stops being checked.</p>
 */
public final class ReplicaCount {

    /** The environment variable every Ravenroot deployment descriptor actually sets. */
    public static final String VARIABLE = "RAVENROOT_REPLICAS";

    private ReplicaCount() {
    }

    public static int fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String value = environment.getOrDefault(VARIABLE, "1").trim();
        try {
            int replicas = Integer.parseInt(value);
            if (replicas < 1) throw new NumberFormatException();
            return replicas;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(VARIABLE + " must be a positive integer");
        }
    }
}
