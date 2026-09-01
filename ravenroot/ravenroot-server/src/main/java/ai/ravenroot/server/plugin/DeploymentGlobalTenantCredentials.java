package ai.ravenroot.server.plugin;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.core.security.nodepackage.TenantCredentialResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Adapts the server's single composed {@link CredentialResolver} to the
 * {@link TenantCredentialResolver} port {@code ManagedNodePackageServices} composes against.
 *
 * <p>The server already builds exactly one credential path — the author-entered store chained with
 * the operator's environment bindings, see {@code CredentialResolverChain} — and hands it to
 * {@code BehaviorEnvironment}. A node package's managed channel resolves credentials through that
 * same path and not a second one, which is the whole reason this class is an adapter rather than a
 * resolver of its own: two credential namespaces reachable from one deployment would be two places
 * to provision a secret and two places to get it wrong.</p>
 *
 * <h2>Which boundary applies to which capability</h2>
 * <p>{@code NodePackageEgressPolicy} fixes the destination a secret may be sent to and the header it
 * may occupy. That is true of the two <em>egress</em> capabilities and of nothing else:</p>
 *
 * <ul>
 *   <li><strong>{@code outbound-http} and {@code outbound-websocket}</strong> — the secret is never
 *       handed to the package. The managed executor resolves it internally and places it at the
 *       operator-fixed origin, in the operator-fixed header, or uses it to sign for the
 *       operator-fixed SigV4 grant. Destination and header are genuinely bounded here.</li>
 *   <li><strong>{@code credential-resolution}</strong> — the policy is <strong>not consulted at
 *       all</strong>. {@code NodeCredentialService.resolve} returns the secret in the clear to the
 *       package, for any reference that is non-empty, at most 256 characters and free of control
 *       characters. There is no destination, no header and, unless the operator writes one, no list
 *       of admissible references. Granting this capability is itself the whole boundary: what the
 *       package then does with the secret is outside every policy in this runtime.</li>
 * </ul>
 *
 * <p>This is stated at length because the short version — "the grant fixes where a secret can go and
 * which header it lands in" — is true of the egress pair and false of {@code credential-resolution},
 * and a reader who carries the short version across to the third capability believes in a
 * protection that is not there.</p>
 *
 * <h2>The one boundary {@code credential-resolution} can have: an admissible-reference list</h2>
 * <p>{@link #restrictedTo} narrows this adapter to a fixed set of references: anything outside the
 * set resolves empty, exactly as an unprovisioned reference does, so a package cannot distinguish
 * "not allowed" from "not configured" and the refusal leaks nothing. It applies to <em>every</em>
 * route into the credential path — the {@code credential-resolution} capability, the HTTP and
 * WebSocket credential placements, and SigV4 signing — because all four go through this one
 * resolver.</p>
 *
 * <p>The list is optional and its absence grants nothing new: with no list the adapter delegates to
 * the composed resolver. It restricts and can never widen, which is why an absent list is the same deny-by-
 * default posture as an absent grant.</p>
 *
 * <h3>One reference set for four paths, and what that admits</h3>
 * <p>{@link TenantCredentialResolver#resolve} receives a package id, a tenant id and a reference, and
 * <strong>nothing that identifies the calling path</strong>. This class cannot therefore say "admit
 * this reference for signing but not for reading": one set governs all four routes, and a reference
 * admitted for any of them is admitted for every one.</p>
 *
 * <p>So a reference in the set <strong>is readable in the clear</strong> through
 * {@code NodeCredentialService.resolve} by a package that holds {@code credential-resolution}, even
 * one the operator bound only to <em>sign</em> toward a single HTTPS origin — the signing path never
 * hands the secret to the package, but membership of this set does.</p>
 *
 * <p>{@code EnvironmentNodePackageServiceGrants.credentialScope}, which builds the set, therefore
 * splits on that capability. Without {@code credential-resolution} there is no clear-text path, and
 * the references the grant's own {@code awsSigV4Bindings} name are added silently, so that writing a
 * list cannot break a signing binding the same document already authorized. With it, a bound
 * reference the list omits is a <strong>startup refusal</strong>: the operator adds it to the list
 * and reads it there, rather than inheriting an exposure they never saw. The refusal removes the
 * surprise, not the exposure. This adapter does not decide whether the list must be mandatory.</p>
 *
 * <h2>Known limitation: the reference namespace is deployment-global, not per tenant</h2>
 * <p>{@link TenantCredentialResolver} takes a package id and a tenant id alongside the opaque
 * reference; {@link CredentialResolver#resolve(String)} takes the reference alone. This adapter
 * therefore <strong>discards</strong> both the package id and the tenant id, and a reference
 * resolves to the same secret for every tenant and every package in the deployment.</p>
 *
 * <p>That is not a shortcut taken here: it is the credential model this runtime already has. The
 * core HTTP node resolves the very same way, {@code EnvironmentCredentialResolver} derives its
 * variable from the reference and nothing else, and {@code SqliteUserCredentialStore} mints
 * references into one deployment-wide namespace. Narrowing the namespace inside this adapter would
 * make a package's managed channel resolve differently from every other credential read in the same
 * process, which is a worse failure than the honest one: it would look tenant-scoped while the
 * neighbouring path was not.</p>
 *
 * <p>What softens it in practice, and is worth crediting because it is a property of the shipped
 * bundles rather than of this class: the packages that ship here take the reference from an operator
 * profile that is already selected per tenant, not from the graph. Two tenants configured with two
 * profiles therefore name two references and do not converge on one secret. That is a property of
 * those bundles, so it does not generalise to a third-party one, which may take the reference from
 * wherever it likes.</p>
 *
 * <p>A deployment that needs per-tenant secrets enforced by the runtime does not have them today; it
 * needs a tenant-aware credential store first, and that is a change to the credential boundary, not
 * to this adapter.</p>
 */
public final class DeploymentGlobalTenantCredentials implements TenantCredentialResolver {

    private final CredentialResolver delegate;

    public DeploymentGlobalTenantCredentials(CredentialResolver delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * @param packageId ignored; see the class-level known limitation
     * @param tenantId ignored; see the class-level known limitation
     * @param opaqueReference the reference the caller declared, never credential material
     * @return whatever the deployment's one credential path answers for that reference
     */
    @Override
    public Optional<SecretValue> resolve(String packageId, String tenantId, String opaqueReference) {
        return delegate.resolve(opaqueReference);
    }

    /**
     * A view of {@code delegate} that answers only for {@code allowedReferences}.
     *
     * <p>Lives here, beside the limitation it narrows, rather than in the grant reader that decides
     * to apply it: this is the only place where "which references may this package resolve" is a
     * property of an object rather than of a parse, and a reader who arrives at the known-limitation
     * block above should find the mitigation in the same file rather than by search.</p>
     *
     * @param delegate the resolver to narrow; must not be null
     * @param allowedReferences the exact references that may resolve; must not be empty, because a
     *     view that admits nothing is a composition mistake rather than a way to express no grant
     * @return a resolver answering {@link Optional#empty()} for every reference outside the set
     */
    public static TenantCredentialResolver restrictedTo(TenantCredentialResolver delegate,
                                                        Set<String> allowedReferences) {
        Objects.requireNonNull(delegate, "delegate");
        Set<String> allowed = Set.copyOf(Objects.requireNonNull(allowedReferences, "allowedReferences"));
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("allowedReferences must not be empty");
        }
        return new ReferenceRestricted(delegate, allowed);
    }

    private record ReferenceRestricted(TenantCredentialResolver delegate, Set<String> allowed)
            implements TenantCredentialResolver {

        @Override
        public Optional<SecretValue> resolve(String packageId, String tenantId, String opaqueReference) {
            // Answering empty rather than throwing is deliberate: it is byte-for-byte what an
            // unprovisioned reference already answers, so a package learns that this reference does
            // not resolve and cannot use the difference between "refused" and "absent" to enumerate
            // which references the deployment holds.
            if (opaqueReference == null || !allowed.contains(opaqueReference)) {
                return Optional.empty();
            }
            return delegate.resolve(packageId, tenantId, opaqueReference);
        }
    }
}
