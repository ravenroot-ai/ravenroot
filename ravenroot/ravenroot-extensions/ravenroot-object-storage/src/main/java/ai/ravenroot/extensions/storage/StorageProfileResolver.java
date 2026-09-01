package ai.ravenroot.extensions.storage;

import java.util.Optional;

@FunctionalInterface
public interface StorageProfileResolver {
    Optional<StorageProfile> resolve(String name);
}
