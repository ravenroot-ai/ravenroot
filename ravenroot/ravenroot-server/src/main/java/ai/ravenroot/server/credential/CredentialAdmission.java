package ai.ravenroot.server.credential;

import ai.ravenroot.api.security.RequestContext;

import java.util.Objects;
import java.util.Set;

/**
 * The rule that makes an author-entered credential <b>theirs</b> at use time, not only at list time.
 *
 * <h2>Why the check is here and not in the resolver</h2>
 * <p>Because the resolver has no idea who is asking. {@code CredentialResolver.resolve(String)} takes
 * a reference and nothing else; it is a contract in {@code ravenroot-application-api} implemented in
 * core, in the mail extension and in third-party adapters, and
 * {@code CredentialResolverTenantScopeTest} exists precisely to fail if its arity changes. SEC-07
 * records the remaining identity gap in that resolver contract.</p>
 *
 * <p>Admission is where the identity is: {@code RavenrootServer} has the authenticated
 * {@link RequestContext} and the document's bytes in the same method, before an execution id is
 * minted and before the engine sees anything. So the rule is applied there, once, for every node type
 * at once — {@code llm-prompt}, {@code agent}, {@code http.request}, {@code mail.send} and whatever
 * declares a {@code SECRET_REFERENCE} next — rather than in each consumer.</p>
 *
 * <h2>What it refuses, and what it deliberately does not</h2>
 * <p>It refuses a submission carrying <b>any</b> minted reference the submitter does not own. It says
 * nothing about operator-provisioned references: those have no owner, are provisioned by whoever runs
 * the deployment, and are governed by the pre-existing environment path. That separation is what
 * {@link CredentialReference#PREFIX} exists for.</p>
 *
 * <p>The refusal does not distinguish "no such reference" from "somebody else's". Two answers would
 * let a caller enumerate which references exist, which is the oracle a minted namespace removes.</p>
 *
 * <h2>The honest limit</h2>
 * <p>This is a check at one door, and today that is every door — <b>measured, not assumed</b>:
 * {@code grep -rn "startGraphMl" ravenroot --include='*.java'}, restricted to
 * {@code ravenroot-server}'s main sources, returns exactly one call site, which is
 * {@code RavenrootServer#submitExecution} and is where this class is invoked; and {@code RouteTable}
 * declares no route that creates a graph deployment, so managed ingress is installed by the
 * composition root from node packages rather than from anything a caller submits. The CLI's embedded
 * path calls {@code startGraphMl} too and composes no credential store at all, so a minted reference
 * resolves to nothing there.</p>
 *
 * <p><b>What that does not cover.</b> A future entry point reaching the engine without passing here
 * would not inherit the rule, because resolution itself is still by reference alone. That is stated
 * in {@link UserCredentialStore} too, and it is the reason this class scans bytes rather than parsed
 * nodes: the check must not depend on a list of the places a reference may appear, because a place
 * nobody thought of is a place the check does not reach.</p>
 */
public final class CredentialAdmission {

    private final UserCredentialStore store;

    public CredentialAdmission(UserCredentialStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * A refusal to admit, carrying no detail a caller could learn from.
     *
     * <p>Extends {@link SecurityException} so it is unmistakably not an ordinary argument error, and
     * so a handler that forgot to catch it cannot turn it into a 400 that reads like a typo.</p>
     */
    public static final class NotYours extends SecurityException {
        public NotYours() {
            super("the submitted graph names a stored credential this caller does not own");
        }
    }

    /**
     * @throws NotYours when the document names a minted reference this caller does not own
     */
    public void require(RequestContext context, byte[] document) {
        Objects.requireNonNull(context, "context");
        Set<String> named = CredentialReference.foundIn(document);
        for (String reference : named) {
            if (!store.isOwnedBy(reference, context.tenantId(), context.subject())) {
                throw new NotYours();
            }
        }
    }

    /**
     * The admission a deployment with no credential store gets: nothing to own, so nothing to refuse.
     *
     * <p>Not a weakening. Where no store is composed, no reference can have been minted, so a
     * minted-shaped string in a document resolves to nothing and the node fails closed at execution.
     * The alternative — refusing every minted-shaped string outright — would refuse documents that
     * are merely quoting one, with no credential at risk either way.</p>
     */
    public static CredentialAdmission permissive() {
        return new CredentialAdmission(new UserCredentialStore() {
            @Override
            public StoredCredential mint(String tenantId, String subject, String label,
                                         CredentialScheme scheme, String username, char[] secret) {
                throw new UnsupportedOperationException("this deployment stores no credentials");
            }

            @Override
            public java.util.List<StoredCredential> listFor(String tenantId, String subject) {
                return java.util.List.of();
            }

            @Override
            public boolean isOwnedBy(String reference, String tenantId, String subject) {
                // True, not false: see this method's own comment. Nothing was minted, so nothing is
                // being protected, and refusing would only break documents that mention a string.
                return true;
            }

            @Override
            public java.util.Optional<ai.ravenroot.api.security.SecretValue> resolve(String reference) {
                return java.util.Optional.empty();
            }
        });
    }
}
