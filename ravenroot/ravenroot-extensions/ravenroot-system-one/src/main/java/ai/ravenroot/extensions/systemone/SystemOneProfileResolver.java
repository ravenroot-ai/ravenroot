package ai.ravenroot.extensions.systemone;

import java.util.Optional;

/** Resolves deployment-owned System One profiles by graph-visible name. */
@FunctionalInterface
public interface SystemOneProfileResolver {
    Optional<SystemOneProfile> resolve(String profileName);
}
