package ai.ravenroot.server.credential;

import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Author-entered credentials first, then the operator's environment bindings.
 *
 * <h2>Why a chain and not a replacement</h2>
 * <p>The environment path remains supported: every graph already
 * authored against {@code RAVENROOT_CREDENTIAL_<HEX>} keeps resolving, and a CLI-only deployment
 * still has a way to bind a credential — which the server-minted-reference contract names as the condition under which
 * API-05 would become a hard prerequisite rather than a stated dependency. Withdrawing the
 * environment path would have tripped that falsifier; chaining does not.</p>
 *
 * <h2>The two namespaces cannot collide, so the order is not a policy</h2>
 * <p>A minted reference matches {@link CredentialReference#isMinted} and an operator's does not, so at
 * most one link can answer any given reference. The order below therefore decides nothing about
 * precedence; it is only the order the questions are asked in, and the user store is first because it
 * answers its own namespace without touching the database for anything else.</p>
 *
 * <p>This is worth stating because a chain <em>usually</em> is a policy — first-wins shadowing is how
 * a resolver chain lets one source silently override another — and the reason it is not one here is a
 * property of {@link CredentialReference}, not a habit. If that prefix were ever dropped, this class
 * would silently become a precedence rule.</p>
 *
 * <h2>Concurrency</h2>
 * <p>{@link CredentialResolver}'s own contract requires implementations to be safe for concurrent use
 * because one resolver serves every node that reads a binding, and those reads happen in parallel.
 * This class holds an immutable list and delegates; the links it is given carry that
 * obligation themselves.</p>
 */
public final class CredentialResolverChain implements CredentialResolver {

    private final List<CredentialResolver> links;

    public CredentialResolverChain(CredentialResolver... links) {
        this.links = List.of(Objects.requireNonNull(links, "links"));
    }

    @Override
    public Optional<SecretValue> resolve(String reference) {
        for (CredentialResolver link : links) {
            Optional<SecretValue> resolved = link.resolve(reference);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }
}
