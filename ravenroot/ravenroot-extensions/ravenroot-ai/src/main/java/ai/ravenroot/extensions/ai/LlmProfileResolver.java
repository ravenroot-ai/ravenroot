package ai.ravenroot.extensions.ai;

import java.util.Optional;

/** Resolves an operator profile from its name. Never consults graph content beyond that name. */
@FunctionalInterface
public interface LlmProfileResolver {
    /**
     * @param profileName the name a graph wrote into the node's {@code provider} property
     * @return the operator's profile, or empty when the operator declared none under that name
     */
    Optional<LlmProfile> resolve(String profileName);
}
