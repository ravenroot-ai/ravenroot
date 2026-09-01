package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class FilesystemReadNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "filesystem.read";
    private static final String VERSION = "filesystem.read.v1";
    private final FilesystemRuntime runtime;

    public FilesystemReadNodeBehavior() { this(new FilesystemRuntime(new EnvironmentFilesystemProfileResolver())); }
    FilesystemReadNodeBehavior(FilesystemRuntime runtime) { this.runtime = runtime; }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Read file", "Filesystem",
                "Reads one bounded regular file through an operator-owned root-confined profile.", "actor", false,
                List.of(
                        NodePropertyDescriptor.required("filesystemProfile", "Filesystem profile", NodePropertyType.STRING,
                                "Opaque tenant-scoped operator profile; the root never comes from GraphML."),
                        NodePropertyDescriptor.required("path", "Relative path", NodePropertyType.STRING,
                                "Slash-separated relative path allowed by the selected profile."),
                        optional("encoding", "Encoding", NodePropertyType.STRING,
                                "Result body encoding: strict UTF-8 or canonical Base64.", "utf-8", List.of("utf-8", "base64")),
                        optional("maxBytes", "Maximum bytes", NodePropertyType.INTEGER,
                                "May only tighten the profile byte ceiling.", "", List.of()),
                        optional("deadlineMs", "Deadline (ms)", NodePropertyType.INTEGER,
                                "May only tighten the profile total deadline.", "", List.of())),
                Set.of("filesystem"));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return message -> {
            long startedNanos = System.nanoTime();
            try {
                requireReadInput(message.payload());
                FilesystemInvocation invocation = FilesystemInvocation.resolve(configuration, runtime,
                        message.tenantId(), false);
                FilesystemAccess.InvocationState state = new FilesystemAccess.InvocationState();
                return runtime.execute(message.tenantId(), invocation.profile(), invocation.remainingSince(startedNanos), () -> {
                    FilesystemAccess.Read read = runtime.access.read(invocation.profile(), invocation.path(),
                            invocation.maxBytes(), state);
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("version", VERSION);
                    output.put("encoding", invocation.encoding());
                    if (invocation.encoding().equals("utf-8")) output.put("text", FilesystemEncoding.decodeText(read.body()));
                    else output.put("base64", FilesystemEncoding.encodeBase64(read.body()));
                    output.put("bytes", read.body().length);
                    output.put("sha256", read.sha256());
                    return NodeResult.continueWith(Map.copyOf(output));
                }, state);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure instanceof FilesystemNodeException ? failure
                        : FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT));
            }
        };
    }

    private static void requireReadInput(Object payload) {
        if (!(payload instanceof Map<?, ?> map) || map.size() != 1 || !VERSION.equals(map.get("version"))
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT);
        }
    }

    static NodePropertyDescriptor optional(String name, String label, NodePropertyType type, String description,
                                           String defaultValue, List<String> allowed) {
        return new NodePropertyDescriptor(name, label, type, false, description, defaultValue, allowed);
    }
}
