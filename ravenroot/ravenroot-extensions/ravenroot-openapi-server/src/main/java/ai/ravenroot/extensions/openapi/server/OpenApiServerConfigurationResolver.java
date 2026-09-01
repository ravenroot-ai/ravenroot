package ai.ravenroot.extensions.openapi.server;

import java.util.Optional;

/** Resolves package authority and profiles from operator-owned configuration only. */
@FunctionalInterface
public interface OpenApiServerConfigurationResolver {
    Optional<OpenApiServerConfiguration> resolve();
}
