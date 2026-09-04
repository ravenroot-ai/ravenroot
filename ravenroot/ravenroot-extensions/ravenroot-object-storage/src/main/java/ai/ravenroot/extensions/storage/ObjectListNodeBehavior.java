package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** Lists a bounded projection of objects within one operator-owned storage scope. */
public final class ObjectListNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "object.list";
    private static final Set<String> FIELDS = Set.of("version", "cursor");
    private static final Set<String> PROJECTIONS = Set.of("size", "etag", "lastModified", "storageClass");
    private static final int HARD_MAX_RESULTS = 1_000;
    private final StorageRuntime runtime;

    public ObjectListNodeBehavior() { this(new StorageRuntime(new EnvironmentStorageProfileResolver())); }
    ObjectListNodeBehavior(StorageRuntime runtime) { this.runtime = runtime; }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "List objects", "Object storage",
                "Lists bounded safe metadata within an operator-owned S3-compatible profile.", "actor", false,
                List.of(ObjectGetNodeBehavior.required("storageProfile", "Storage profile",
                                "Opaque operator-owned bucket profile."),
                        ObjectGetNodeBehavior.optional("prefix", "Prefix",
                                "Optional relative prefix that only narrows the operator prefix.", "", List.of()),
                        new NodePropertyDescriptor("projection", "Metadata projection", NodePropertyType.STRING,
                                false, "Comma-separated safe metadata fields: size, etag, lastModified, storageClass.",
                                "", List.of()),
                        new NodePropertyDescriptor("maxResults", "Maximum results", NodePropertyType.INTEGER,
                                false, "Maximum entries returned by one page (1-1000).", "100", List.of()),
                        ObjectGetNodeBehavior.optionalInt("timeoutMs", "Deadline",
                                "May only tighten the profile deadline."),
                        ObjectGetNodeBehavior.optionalInt("maxConcurrency", "Concurrency",
                                "May only tighten the profile ceiling."),
                        new NodePropertyDescriptor("retries", "Retries", NodePropertyType.INTEGER, false,
                                "Bounded retries for transient list failures (0-3) under one deadline.", "0", List.of()),
                        RecoveryRepeatabilityProperty.declaration(
                                "Declare repeatable only when repeating this bounded read is acceptable.")),
                Set.of("network", "credential-reference"));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        try {
            StorageProfile profile = StorageSettings.profile(configuration, runtime.profiles,
                    StorageProfile.Operation.LIST);
            String prefix = configuration.property("prefix", "");
            StorageUri.scopedPrefix(profile, prefix);
            Set<String> projection = projection(configuration.property("projection", ""));
            int maximum = StorageSettings.boundedPositive(configuration, "maxResults", 100, HARD_MAX_RESULTS);
            int timeout = StorageSettings.tighten(configuration, "timeoutMs", profile.timeoutMs());
            int concurrency = StorageSettings.tighten(configuration, "maxConcurrency", profile.maxConcurrency());
            int retries = StorageSettings.boundedNonNegative(configuration, "retries", 0, 3);
            Semaphore gate = new Semaphore(concurrency, true);
            return StorageRuntime.action((message, cancellation) -> {
                try {
                    Map<String, Object> payload = StorageValues.object(message.payload(), "payload");
                    if (!payload.keySet().stream().allMatch(FIELDS::contains)
                            || !"object.list.v1".equals(payload.get("version"))) {
                        throw StorageException.of(StorageException.Code.INVALID_INPUT);
                    }
                    String providerCursor = null;
                    if (payload.containsKey("cursor")) {
                        providerCursor = StorageCursor.decode(profile, message.tenantId(), prefix, maximum, projection,
                                StorageValues.string(payload.get("cursor"), "cursor", 4096));
                    }
                    URI destination = StorageUri.listDestination(profile, prefix, maximum, providerCursor);
                    StorageRuntime.Request request = new StorageRuntime.Request(destination, "GET", Map.of(),
                            new byte[0], timeout, profile.maxObjectBytes(),
                            StorageRuntime.projectedOutputLimit(profile.maxObjectBytes()), retries,
                            StorageRuntime.Semantics.RETRYABLE_READ,
                            response -> StorageListXml.project(profile, message.tenantId(), prefix, maximum,
                                    projection, response));
                    return runtime.execute(message, cancellation, services, profile, gate, request);
                } catch (RuntimeException failure) {
                    return CompletableFuture.failedFuture(failure instanceof StorageException ? failure
                            : StorageException.of(StorageException.Code.INVALID_INPUT));
                }
            });
        } catch (StorageException safe) {
            throw safe;
        } catch (RuntimeException invalid) {
            throw StorageException.of(StorageException.Code.CONFIGURATION);
        }
    }

    private static Set<String> projection(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String field : configured.split(",", -1)) {
            String value = field.strip();
            if (!PROJECTIONS.contains(value) || !result.add(value)) {
                throw StorageException.of(StorageException.Code.CONFIGURATION);
            }
        }
        return Set.copyOf(result);
    }
}
