package ai.ravenroot.extensions.filesystem;

import java.util.Optional;

@FunctionalInterface
public interface FilesystemProfileResolver {
    Optional<FilesystemProfile> resolve(String tenant, String profile);
}
