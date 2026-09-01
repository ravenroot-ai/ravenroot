package ai.ravenroot.api.ingress;

import java.util.List;
import java.util.Optional;

/**
 * Optional capability implemented by a {@code NodePackage} which needs managed HTTP ingress.
 *
 * <p>The package declares limits before a listener is bound; it never receives the server or an
 * authority to bind a port. A package that does not implement this interface has no ingress
 * authority. The composition root validates all declarations as one transaction.</p>
 */
public interface IngressAuthorityContributor {
/**
 * Declares the bounded ingress authorities requested by this package.
 * @return immutable declarations that the composition root validates as a unit.
 */
    List<IngressAuthorityDeclaration> ingressAuthorities();

    /**
     * Optional operator/package projection policy paired with this package's authority declaration.
     *
     * <p>The default preserves the pre-projection contract: query/path bounds remain enforced, but
     * no request header is disclosed to package code. Implementations return a policy only from
     * operator-owned package configuration; graph data must never construct or widen it.</p>
 * @return operator-owned projection policy, or empty when headers must not be exposed.
     */
    default Optional<IngressRequestProjectionPolicy> ingressRequestProjection() {
        return Optional.empty();
    }
}
