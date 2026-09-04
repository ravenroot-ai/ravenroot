package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

public final class ObjectGetNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "object.get";
    private final StorageRuntime runtime;

    public ObjectGetNodeBehavior() { this(new StorageRuntime(new EnvironmentStorageProfileResolver())); }
    ObjectGetNodeBehavior(StorageRuntime runtime) { this.runtime = runtime; }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Get object", "Object storage",
                "Reads one bounded object from an operator-owned S3-compatible profile.", "actor", false,
                List.of(required("storageProfile", "Storage profile", "Opaque operator-owned bucket profile."),
                        required("key", "Object key", "Relative key inside the operator prefix."),
                        optional("encoding", "Encoding", "text or canonical Base64.", "base64", List.of("text", "base64")),
                        optionalInt("maxBytes", "Maximum bytes", "May only tighten the profile ceiling."),
                        optionalInt("timeoutMs", "Deadline", "May only tighten the profile deadline."),
                        optionalInt("maxConcurrency", "Concurrency", "May only tighten the profile ceiling.")),
                Set.of("network", "credential-reference"));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        StorageSettings settings = StorageSettings.compile(configuration, runtime.profiles, StorageProfile.Operation.GET);
        Semaphore action = new Semaphore(settings.maxConcurrency(), true);
        return StorageRuntime.action((message, cancellation) -> {
            if (!(message.payload() instanceof Map<?, ?> map) || map.size() != 1
                    || !"object.get.v1".equals(map.get("version"))) {
                return CompletableFuture.failedFuture(StorageException.of(StorageException.Code.INVALID_INPUT));
            }
            return runtime.execute(message, cancellation, services, settings, action, new byte[0], Map.of());
        });
    }

    static NodePropertyDescriptor required(String name, String label, String description) {
        return NodePropertyDescriptor.required(name, label, NodePropertyType.STRING, description);
    }
    static NodePropertyDescriptor optional(String name, String label, String description, String defaultValue,
                                           List<String> allowed) {
        return new NodePropertyDescriptor(name, label, NodePropertyType.STRING, false, description, defaultValue, allowed);
    }
    static NodePropertyDescriptor optionalInt(String name, String label, String description) {
        return NodePropertyDescriptor.optional(name, label, NodePropertyType.INTEGER, description, "");
    }
}
