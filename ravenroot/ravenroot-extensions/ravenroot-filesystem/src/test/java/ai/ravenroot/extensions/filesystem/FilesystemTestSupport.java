package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class FilesystemTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "workspace";

    static FilesystemProfile profile(Path root) {
        return new FilesystemProfile(PROFILE, root, true, true, Set.of("**", "*"),
                1024, 8, Duration.ofSeconds(5));
    }

    static NodeConfiguration configuration(String behavior, Map<String, Object> overrides) {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("filesystemProfile", PROFILE);
        properties.put("path", "folder/file.txt");
        properties.putAll(overrides);
        return new NodeConfiguration("filesystem-node", behavior, properties);
    }

    static NodeMessage message(Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", TENANT, "subject", PrincipalType.WORKLOAD, "issuer"),
                id, id, id, id, Set.of(), "filesystem-node", payload, Map.of());
    }

    private FilesystemTestSupport() { }
}
