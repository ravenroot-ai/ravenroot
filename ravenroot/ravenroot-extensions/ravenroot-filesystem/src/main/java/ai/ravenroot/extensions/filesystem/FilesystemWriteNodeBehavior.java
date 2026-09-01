package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class FilesystemWriteNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "filesystem.write";
    private static final String VERSION = "filesystem.write.v1";
    private static final Set<String> TEXT_KEYS = Set.of("version", "text");
    private static final Set<String> BASE64_KEYS = Set.of("version", "base64");
    private final FilesystemRuntime runtime;

    public FilesystemWriteNodeBehavior() { this(new FilesystemRuntime(new EnvironmentFilesystemProfileResolver())); }
    FilesystemWriteNodeBehavior(FilesystemRuntime runtime) { this.runtime = runtime; }

    @Override public NodeTypeDescriptor descriptor() {
        List<NodePropertyDescriptor> properties = new ArrayList<>();
        properties.add(NodePropertyDescriptor.required("filesystemProfile", "Filesystem profile", NodePropertyType.STRING,
                "Opaque tenant-scoped operator profile; the root never comes from GraphML."));
        properties.add(NodePropertyDescriptor.required("path", "Relative path", NodePropertyType.STRING,
                "Slash-separated relative path allowed by the selected profile."));
        properties.add(FilesystemReadNodeBehavior.optional("encoding", "Encoding", NodePropertyType.STRING,
                "Required body representation: strict UTF-8 or canonical Base64.", "utf-8", List.of("utf-8", "base64")));
        properties.add(FilesystemReadNodeBehavior.optional("mode", "Write mode", NodePropertyType.STRING,
                "Create a previously absent target or atomically replace an existing target.", "create-new",
                List.of("create-new", "replace")));
        properties.add(FilesystemReadNodeBehavior.optional("maxBytes", "Maximum bytes", NodePropertyType.INTEGER,
                "May only tighten the profile byte ceiling.", "", List.of()));
        properties.add(FilesystemReadNodeBehavior.optional("deadlineMs", "Deadline (ms)", NodePropertyType.INTEGER,
                "May only tighten the profile total deadline.", "", List.of()));
        properties.add(RecoveryRepeatabilityProperty.declaration(
                "Whether repeating the complete atomic publication is safe after an ambiguous final move."));
        return new NodeTypeDescriptor(BEHAVIOR, "Write file", "Filesystem",
                "Atomically creates or replaces one bounded regular file through an operator-owned root-confined profile.",
                "actor", false, List.copyOf(properties), Set.of("filesystem", "side-effect"));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> {
            long startedNanos = System.nanoTime();
            try {
                FilesystemInvocation invocation = FilesystemInvocation.resolve(configuration, runtime,
                        message.tenantId(), true);
                byte[] body = body(message.payload(), invocation.encoding(), invocation.maxBytes());
                FilesystemAccess.WriteMode mode = switch (configuration.property("mode", "create-new")) {
                    case "create-new" -> FilesystemAccess.WriteMode.CREATE_NEW;
                    case "replace" -> FilesystemAccess.WriteMode.REPLACE;
                    default -> throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT);
                };
                FilesystemAccess.InvocationState state = new FilesystemAccess.InvocationState();
                return runtime.execute(message.tenantId(), invocation.profile(), invocation.remainingSince(startedNanos), () -> {
                    FilesystemAccess.Write write = runtime.access.write(invocation.profile(), invocation.path(),
                            body, mode, state);
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("version", "filesystem.write.result.v1");
                    output.put("status", write.replaced() ? "REPLACED" : "CREATED");
                    output.put("bytes", write.bytes());
                    output.put("sha256", write.sha256());
                    return NodeResult.continueWith(Map.copyOf(output));
                }, state);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure instanceof FilesystemNodeException ? failure
                        : FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT));
            }
        };
    }

    private static byte[] body(Object payload, String encoding, long maxBytes) {
        if (!(payload instanceof Map<?, ?> map) || !VERSION.equals(map.get("version"))
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT);
        }
        Set<?> keys = map.keySet();
        if (encoding.equals("utf-8") && keys.equals(TEXT_KEYS) && map.get("text") instanceof String text) {
            return FilesystemEncoding.encodeText(text, maxBytes);
        }
        if (encoding.equals("base64") && keys.equals(BASE64_KEYS) && map.get("base64") instanceof String base64) {
            return FilesystemEncoding.decodeBase64(base64, maxBytes);
        }
        throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT);
    }
}
