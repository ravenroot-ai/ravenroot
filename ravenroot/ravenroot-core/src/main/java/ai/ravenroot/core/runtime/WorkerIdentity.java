package ai.ravenroot.core.runtime;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;

/**
 * Who a process says it is when it takes a lease, in a shape an operator can read.
 *
 * <h2>What replaced a bare random UUID</h2>
 * <p>Two independent {@code "ravenroot-" + UUID.randomUUID()} values used to identify this
 * deployment's two lease-taking roles. They were unique and nothing else: a
 * {@code ProcessInventoryEntry.ownerWorkerId} of {@code ravenroot-3f2a...} answers "is this lease
 * held?" and refuses to answer the two questions an operator actually has — <em>which replica</em>
 * holds it, and <em>which restart</em> of that replica took it. With one process those questions had
 * one answer and the identifier could afford not to carry it. With several they do not.</p>
 *
 * <p>So the identity is {@code <replica-name>:<incarnation>/<role>}, and each part answers exactly
 * one of those: the replica name says which deployment member, the incarnation says which start of
 * it, and the role says which of that member's two lease-takers. The three separators are reserved
 * characters rather than a formatting choice — see {@link #requireName} — so the rendered value can
 * be split back apart without a quoting rule.</p>
 *
 * <h2>Uniqueness comes from the incarnation, not from the name</h2>
 * <p>The replica name is chosen for readability and can therefore repeat: two hosts can be called the
 * same thing, an operator can set the same value on both, and an embedder that supplies no name at
 * all gets {@link #LOCAL_REPLICA_NAME}. None of that can make two live processes share an identity,
 * because {@link #processIncarnation()} is drawn once per JVM start from {@link SecureRandom} and is
 * part of every identity this class mints. Deriving the incarnation from the process id or the start
 * time instead would be readable and wrong: both repeat across hosts, and a pod restarting into the
 * same pid within the same second is exactly the case the incarnation exists to distinguish.</p>
 *
 * <h2>Why the roles stay distinct</h2>
 * <p>{@link Role#RUNTIME} and {@link Role#RECOVERY} are two identities in one process on purpose, and
 * collapsing them into one — which reads like an obvious simplification — would break fencing. The
 * shared store's claim-candidate query deliberately skips an instance whose live lease is held by a
 * <em>different</em> worker, and a claim by the <em>same</em> worker keeps the existing fencing token
 * while a claim by a different one rotates it. Give the sweep the runtime's identity and the sweep
 * stops skipping the work its own runtime is currently advancing, claims it, and keeps the fencing
 * token — so the runtime's recorder goes on writing under a fence that no longer means what it meant.
 * Two identities make the sweep a different worker from the runtime, which is what it is.</p>
 */
public record WorkerIdentity(String replicaName, String incarnation, Role role) {

    /**
     * The replica name used when nothing supplies one.
     *
     * <p>Core has no configuration channel and must not grow one for this, so it cannot read an
     * operator's chosen name or the host's name here; the composition root does that and passes the
     * result inward. A literal is the honest default: it says "nobody told this process what it is
     * called", which is true of an embedder, a test and a CLI invocation alike. It is deliberately not
     * a hostname lookup — that is a name resolution, it can block, it can fail, and core performing
     * one in a field initializer would make constructing an application depend on the resolver.</p>
     */
    public static final String LOCAL_REPLICA_NAME = "local";

    /**
     * Separates the replica name from the incarnation; reserved in both.
     *
     * <p>A colon rather than a hash, and the reason is transport rather than taste. This value is not
     * only displayed: the process inventory accepts it back as an exact-match filter, and an operator
     * filtering by what the inventory just showed them is the workflow that parameter exists for. A
     * hash ends a URL's query and begins its fragment, so a value carrying one arrives at the server
     * truncated to the replica name, matches nothing, and returns an empty page with no error at all —
     * the failure that looks like an answer. A colon is legal unencoded in a query value and is
     * already outside the character set a replica name may use, so it separates as clearly and
     * survives the round trip.</p>
     */
    private static final char INCARNATION_SEPARATOR = ':';

    /** Separates the incarnation from the role; reserved in both, and legal unencoded in a query. */
    private static final char ROLE_SEPARATOR = '/';

    /**
     * A name long enough for a Kubernetes pod name (253 characters is the DNS subdomain ceiling, but
     * a pod name is bounded at 63) with room to spare, and short enough that a mistyped value cannot
     * turn every lease row into a page of text. The store's own column is unbounded {@code TEXT}, so
     * this bound protects the operator reading the inventory rather than the database.
     */
    private static final int MAX_NAME_LENGTH = 100;

    /**
     * The permitted shape, in one sentence, so a composition root can report it against the variable
     * an operator actually set without restating the rule and drifting from it.
     */
    public static final String NAME_RULE =
            "must be 1 to " + MAX_NAME_LENGTH + " letters, digits, '.', '-' or '_'";

    /**
     * Drawn once per JVM start. {@code SecureRandom} rather than {@code Random} because a predictable
     * incarnation is a predictable worker id, and a worker id is what the store's fencing decisions
     * are keyed on; guessing one is not an attack this deployment should have to think about.
     * Rendered base 36 so it stays short and stays within the identifier charset below.
     */
    private static final String PROCESS_INCARNATION =
            Long.toUnsignedString(new SecureRandom().nextLong(), 36);

    /** Which of a process's lease-takers an identity belongs to. */
    public enum Role {
        /** The application itself, advancing traversals it accepted. */
        RUNTIME,
        /** The sweep that settles work an earlier process left behind. */
        RECOVERY;

        /** The lowercase token this role contributes to a rendered identity. */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public WorkerIdentity {
        requireName(replicaName, "replicaName");
        requireName(incarnation, "incarnation");
        Objects.requireNonNull(role, "role");
    }

    /** The token distinguishing this JVM start from every other, shared by every role in it. */
    public static String processIncarnation() {
        return PROCESS_INCARNATION;
    }

    /**
     * The identity of {@code role} in this JVM start, under the given replica name.
     *
     * @param replicaName operator-visible name of the deployment member, never blank.
     * @param role which of the process's lease-takers this identity is for.
     * @return the identity to hand a store as a worker id.
     */
    public static WorkerIdentity of(String replicaName, Role role) {
        return new WorkerIdentity(replicaName, PROCESS_INCARNATION, role);
    }

    /**
     * The identity an embedder that names nothing gets: {@link #LOCAL_REPLICA_NAME} and this JVM's
     * incarnation. Still unique per process, still readable, and unchanged in meaning from the random
     * identifier it replaced except that it now says which role it is.
     *
     * @param role which of the process's lease-takers this identity is for.
     * @return the unnamed-replica identity for {@code role}.
     */
    public static WorkerIdentity unnamed(Role role) {
        return of(LOCAL_REPLICA_NAME, role);
    }

    /**
     * The rendered form written to the store and read back from
     * {@code ProcessInventoryEntry.ownerWorkerId()}.
     *
     * @return {@code <replica-name>:<incarnation>/<role>}.
     */
    public String value() {
        return replicaName + INCARNATION_SEPARATOR + incarnation + ROLE_SEPARATOR + role.token();
    }

    @Override
    public String toString() {
        return value();
    }

    /**
     * Rejects a part that could not survive the rendering, rather than escaping it.
     *
     * <p>The alternative — accepting anything and quoting the separators — buys a free choice of name
     * and pays for it with an identifier nobody can split by eye, which defeats the reason this class
     * exists. The permitted set is what a pod name, a host name and a deployment name already use, so
     * the restriction bites only on values that were going to read badly anyway.</p>
     */
    private static void requireName(String value, String field) {
        Objects.requireNonNull(value, field);
        // The offending value is never echoed. A replica name is operator-supplied and reaches stderr
        // from here; what the operator needs is the permitted set, which is fixed and stated once.
        if (value.isBlank() || value.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(field + " " + NAME_RULE);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean permitted = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '-' || character == '_';
            if (!permitted) {
                throw new IllegalArgumentException(field + " " + NAME_RULE);
            }
        }
    }
}
