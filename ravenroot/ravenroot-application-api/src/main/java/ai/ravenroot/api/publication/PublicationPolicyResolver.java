package ai.ravenroot.api.publication;

import java.util.Optional;

/** Operator-owned lookup of immutable publication profiles. */
@FunctionalInterface
public interface PublicationPolicyResolver {
    /**
     * Resolves one exact profile identity without consulting graph-authored rule data.
     *
     * @param id stable operator-owned profile identifier
     * @param version immutable operator-owned version
     * @return the exact profile when available
     */
    Optional<PublicationPolicy> resolve(String id, String version);

    /**
     * Fail-closed resolver used when no profiles were configured.
     *
     * @return a resolver that never returns a profile
     */
    static PublicationPolicyResolver none() {
        return (id, version) -> Optional.empty();
    }
}
