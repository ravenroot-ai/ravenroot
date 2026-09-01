package ai.ravenroot.extensions.openapi.client;

import java.util.Optional;

/** Resolves immutable operator configuration; graph data supplies only the opaque profile name. */
@FunctionalInterface
public interface OpenApiClientProfileResolver {
    Optional<OpenApiClientProfile> resolve(String profileName);
}
