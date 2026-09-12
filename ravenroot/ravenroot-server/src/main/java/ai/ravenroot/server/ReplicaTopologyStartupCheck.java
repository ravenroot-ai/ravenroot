package ai.ravenroot.server;

import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Whether the configured replica count, the selected execution store and the deployment's
 * replica-local state can all be true at once.
 *
 * <h2>What this replaces</h2>
 * <p>Nothing. That is the problem it exists for: a plain server with several replicas configured
 * started silently. {@code ReplicaCount} was read in four places and only one of them — the managed
 * ingress registry — refused, so a deployment that scaled to three pods got three processes each
 * holding its own SQLite file, its own credential database, its own artifact registry and its own
 * audit trail, agreeing about none of it and saying so nowhere. Every refusal below names a
 * combination that was previously accepted and then behaved as if it had not been.</p>
 *
 * <h2>Why the checks are ordered rather than collected</h2>
 * <p>An operator fixes one thing at a time, and the fixes are not independent: selecting the shared
 * store is a prerequisite for the replica-local work, not an alternative to it. Reporting the
 * contradiction first, then the store topology, then the replica-local state means each refusal is
 * the next thing to do rather than one of a list whose order the operator has to infer.</p>
 *
 * <h2>Why several replicas are still refused on the shared store</h2>
 * <p>Because selecting the shared execution store does not make a deployment horizontally scalable —
 * it makes it <em>able</em> to be. Several other authorities in this process are still one file on
 * one pod's disk, and each of them fails differently and quietly when there are two: a credential
 * written on one pod resolves on that pod only, a consent recorded on one pod is asked for again on
 * another, an artifact built on one pod is missing on another, and an audit trail split across pods
 * is not the tamper-evident record it claims to be. Listing them by name is the difference between a
 * refusal an operator can plan against and one they can only be annoyed by.</p>
 */
public final class ReplicaTopologyStartupCheck {

    private ReplicaTopologyStartupCheck() {
    }

    /**
     * A refusal an operator can act on: a stable code, and a detail that names variables and
     * nothing else.
     *
     * <p>Shaped like {@code EmbedStartupCheck.Refusal} and deliberately not the same type. That one
     * belongs to a feature most deployments never enable; this one is on every deployment's startup
     * path, and sharing the type would make the embed package a dependency of starting a server at
     * all.</p>
     *
     * @param code stable token an operator or a log query can match on.
     * @param detail what is wrong and what to change, containing no path, URL or credential.
     */
    public record Refusal(String code, String detail) {
        public Refusal {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(detail, "detail");
        }

        /** The exact line the server prints on stderr before exiting. */
        public String diagnostic() {
            return "{\"event\":\"startup_refused\",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}";
        }
    }

    /**
     * @param environment the process environment.
     * @param configuration the already-parsed execution store selection.
     * @return the reason to refuse startup, or {@code null} when the combination is supportable.
     */
    public static Refusal evaluate(Map<String, String> environment,
                                   ExecutionStoreConfiguration configuration) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(configuration, "configuration");
        boolean shared = configuration instanceof ExecutionStoreConfiguration.Shared;
        if (shared && isSet(environment, ExecutionStoreConfiguration.DIRECTORY_VARIABLE)) {
            // Two locations for one store. Ignoring the directory would leave an operator believing
            // their data is somewhere it is not; honouring it would contradict the selector. It is
            // also the variable ravenroot-cli's backup and restore read, so leaving it set under the
            // shared store leaves a backup command pointed at a directory the server no longer writes
            // — a backup that succeeds and captures nothing.
            return new Refusal("EXECUTION_STORE_LOCATION_CONFLICT",
                    ExecutionStoreConfiguration.DIRECTORY_VARIABLE + " is set while "
                            + ExecutionStoreConfiguration.SELECTOR_VARIABLE + " is '"
                            + ExecutionStoreConfiguration.POSTGRESQL_SELECTOR
                            + "'; the shared store has no directory, so unset "
                            + ExecutionStoreConfiguration.DIRECTORY_VARIABLE);
        }
        if (!shared) {
            // The mirror of the refusal above, and it fails in the more expensive direction. Those
            // settings are read by the shared store and by nothing else, so with the selector on the
            // single-host store they are silently inert: the server starts on a pod-local file while
            // an operator who configured a database believes their durable state is in it, and points
            // their backup procedure there. The converse at least leaves the data where the selector
            // says. Refusing both keeps one rule — the selector and the settings must agree — rather
            // than a rule and an exception nobody would predict the direction of.
            String configured = sharedSettingsSetWithoutTheSharedStore(environment);
            if (configured != null) {
                return new Refusal("EXECUTION_STORE_SELECTOR_CONFLICT",
                        configured + " is set while " + ExecutionStoreConfiguration.SELECTOR_VARIABLE
                                + " does not select '" + ExecutionStoreConfiguration.POSTGRESQL_SELECTOR
                                + "'; either select the shared store or unset the settings it reads");
            }
        }
        int replicas;
        try {
            replicas = ReplicaCount.fromEnvironment(environment);
        } catch (IllegalArgumentException malformed) {
            return new Refusal("REPLICA_CONFIGURATION_INVALID",
                    ReplicaCount.VARIABLE + " must be a positive integer");
        }
        if (replicas == 1) {
            return null;
        }
        if (!shared) {
            // The single-host store is one SQLite file on one pod's disk. Two replicas are two
            // stores, not one store with two writers: each accepts work, each records it where the
            // other cannot see it, and the ownership the execution store exists to arbitrate is
            // arbitrated twice. The maintenance lock does not save this — it is a file lock, so it
            // excludes a second process on the same host and nothing at all on another.
            return new Refusal("EXECUTION_STORE_SINGLE_HOST",
                    ReplicaCount.VARIABLE + " is greater than one while the execution store is "
                            + "single-host; set " + ExecutionStoreConfiguration.SELECTOR_VARIABLE
                            + "='" + ExecutionStoreConfiguration.POSTGRESQL_SELECTOR
                            + "' for a shared store, or run one replica");
        }
        List<String> replicaLocal = replicaLocalAuthorities(environment);
        if (!replicaLocal.isEmpty()) {
            return new Refusal("REPLICA_LOCAL_STATE_UNSUPPORTED",
                    ReplicaCount.VARIABLE + " is greater than one while these authorities are still "
                            + "per-replica: " + String.join(", ", replicaLocal)
                            + "; run one replica until each has a shared authority");
        }
        return null;
    }

    /**
     * The authorities that are still one file on one pod's disk.
     *
     * <p>Returned as stable tokens rather than as variable names, because two of them have no
     * variable to name: the credential store and the artifact registry are composed unconditionally,
     * with defaulted directories, so there is nothing an operator can unset. That is exactly why they
     * belong in this list — an authority with no off switch is one a deployment cannot opt out of by
     * configuration, and the only remaining answer is one replica.</p>
     */
    private static List<String> replicaLocalAuthorities(Map<String, String> environment) {
        var authorities = new ArrayList<String>();
        // Composed on every start (RavenrootServerMain), directory defaulted. A credential an author
        // enters through the interface is written to the pod that served the request and resolves
        // nowhere else, so the same execution succeeds or fails depending on which pod runs it.
        authorities.add("author-entered credentials");
        // Also unconditional. A program artifact built on one pod is absent on the others, so a
        // redemption that lands elsewhere refuses an artifact the deployment does have.
        authorities.add("program artifact registry");
        // A per-pod SQLite file too. Consent recorded on one pod is re-requested on another, which
        // reads to an author as consent that was not recorded.
        authorities.add("authoring assistant consent");
        // The trail is a directory of files this pod appends to. Several pods produce several
        // partial trails, and a tamper-evident record assembled from parts nobody reconciles is not
        // one. Listed unconditionally for the same reason as the two above: the variable selects
        // where it is written, never whether it is.
        authorities.add("audit trail");
        if ("true".equals(environment.get("RAVENROOT_EMBED_ENABLED"))) {
            // Conditional, unlike the four above, and already refused on its own terms by
            // EmbedStartupCheck. Named here as well so an operator who reads this refusal sees the
            // whole list rather than fixing four things and meeting a fifth.
            authorities.add("embed registration authority");
        }
        return List.copyOf(authorities);
    }

    private static boolean isSet(Map<String, String> environment, String variable) {
        String raw = environment.get(variable);
        return raw != null && !raw.isBlank();
    }

    /**
     * The first shared-store setting an operator configured while selecting a different store.
     *
     * <p>Returns the variable's name rather than a boolean so the refusal can say which one was seen.
     * The value itself is never read: naming a setting is a diagnosis, and a URL or a password in a
     * startup message is a leak regardless of how the process ends.</p>
     */
    private static String sharedSettingsSetWithoutTheSharedStore(Map<String, String> environment) {
        for (String variable : new String[]{
                ExecutionStoreConfiguration.URL_VARIABLE,
                ExecutionStoreConfiguration.USER_VARIABLE,
                ExecutionStoreConfiguration.PASSWORD_VARIABLE,
                ExecutionStoreConfiguration.POOL_SIZE_VARIABLE,
                ExecutionStoreConfiguration.POOL_TIMEOUT_VARIABLE,
                ExecutionStoreConfiguration.MANIFEST_PIN_ATTEMPTS_VARIABLE}) {
            if (isSet(environment, variable)) {
                return variable;
            }
        }
        return null;
    }
}
