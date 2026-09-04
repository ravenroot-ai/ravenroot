package ai.ravenroot.api.persistence;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a principal must present before a trigger may act on a durable handler (PERS-05).
 *
 * <h2>Opaque tokens, deliberately</h2>
 * <p>Roles and scopes are {@code String}s rather than {@code ai.ravenroot.api.security.Role} values
 * for the same reason {@link ExecutionKey} carries the tenant opaquely and
 * {@link EventEnvelope#eventType()} is not an enum: the persistence port must not enumerate policy
 * vocabulary, or every new role would be a port change and a schema migration. The store stores and
 * returns these tokens and never interprets them. Evaluation belongs to the runtime, which holds the
 * ingress {@code RequestContext} the tokens are compared against — and which is the only layer that
 * has ever been allowed to reason about authority.</p>
 *
 * <h2>Conjunction, not disjunction</h2>
 * <p>{@link #satisfiedBy(Set, Set)} requires <em>every</em> declared role and <em>every</em> declared
 * scope. A disjunction would make adding a requirement a widening rather than a narrowing, so a
 * handler tightened by an author would silently become easier to resolve. An empty set on either
 * axis imposes no requirement on that axis; {@link #none()} imposes none at all and is the honest
 * spelling of "any authenticated principal of this tenant", which the tenant scope of the lookup
 * already guarantees.</p>
 *
 * @param requiredRoles  opaque role tokens the resolving principal must hold, all of them
 * @param requiredScopes opaque scope tokens the resolving principal must hold, all of them
 */
public record HandlerAuthorization(Set<String> requiredRoles, Set<String> requiredScopes) {

    /** Rejects blank tokens and snapshots both sets in a stable iteration order. */
    public HandlerAuthorization {
        requiredRoles = copyOfTokens(requiredRoles, "requiredRoles");
        requiredScopes = copyOfTokens(requiredScopes, "requiredScopes");
    }

    /**
     * Requirement that any principal of the owning tenant satisfies.
     * @return authorization declaring no role and no scope.
     */
    public static HandlerAuthorization none() {
        return new HandlerAuthorization(Set.of(), Set.of());
    }

    /**
     * Requirement expressed purely as roles.
     * @param roles opaque role tokens the resolving principal must hold.
     * @return authorization declaring those roles and no scope.
     */
    public static HandlerAuthorization ofRoles(String... roles) {
        return new HandlerAuthorization(Set.of(roles), Set.of());
    }

    /**
     * Tests whether a principal holding {@code roles} and {@code scopes} may act on the handler.
     *
     * <p>Absent arguments are treated as empty rather than rejected: a principal that holds nothing
     * is a legitimate input, and throwing here would make the fail-closed answer harder to reach
     * than the fail-open one.</p>
     * @param roles opaque role tokens held by the principal.
     * @param scopes opaque scope tokens held by the principal.
     * @return whether every declared role and scope is held.
     */
    public boolean satisfiedBy(Set<String> roles, Set<String> scopes) {
        Set<String> heldRoles = roles == null ? Set.of() : roles;
        Set<String> heldScopes = scopes == null ? Set.of() : scopes;
        return heldRoles.containsAll(requiredRoles) && heldScopes.containsAll(requiredScopes);
    }

    /**
     * Copies and validates one token set.
     *
     * <p>Control characters are rejected rather than escaped. These sets are stored by adapters as a
     * delimited list, and a token carrying a newline would either split into two authority tokens on
     * the way back out or force every adapter to invent its own escaping — a divergence a conformance
     * suite written in the port's vocabulary could not catch. Rejecting at construction makes the
     * encoding unambiguous for every adapter at once, and no legitimate role or scope name contains
     * one.</p>
     */
    private static Set<String> copyOfTokens(Set<String> tokens, String name) {
        var ordered = new LinkedHashSet<String>();
        for (String token : Objects.requireNonNull(tokens, name)) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException(name + " cannot contain a blank token");
            }
            for (int index = 0; index < token.length(); index++) {
                if (Character.isISOControl(token.charAt(index))) {
                    throw new IllegalArgumentException(
                            name + " cannot contain a token with a control character");
                }
            }
            ordered.add(token);
        }
        return Collections.unmodifiableSet(ordered);
    }
}
