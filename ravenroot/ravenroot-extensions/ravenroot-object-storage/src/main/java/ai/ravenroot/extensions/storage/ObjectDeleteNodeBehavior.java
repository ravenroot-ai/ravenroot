package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/** Deletes one object, optionally constrained to an explicitly permitted version identifier. */
public final class ObjectDeleteNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "object.delete";
    private static final Set<String> FIELDS = Set.of("version", "versionId");
    private final StorageRuntime runtime;

    public ObjectDeleteNodeBehavior() { this(new StorageRuntime(new EnvironmentStorageProfileResolver())); }
    ObjectDeleteNodeBehavior(StorageRuntime runtime) { this.runtime = runtime; }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Delete object", "Object storage",
                "Deletes one object inside an operator-owned S3-compatible profile.", "actor", false,
                List.of(ObjectGetNodeBehavior.required("storageProfile", "Storage profile",
                                "Opaque operator-owned bucket profile."),
                        ObjectGetNodeBehavior.required("key", "Object key",
                                "Relative key inside the operator prefix."),
                        ObjectGetNodeBehavior.optionalInt("timeoutMs", "Deadline",
                                "May only tighten the profile deadline."),
                        ObjectGetNodeBehavior.optionalInt("maxConcurrency", "Concurrency",
                                "May only tighten the profile ceiling."),
                        RecoveryRepeatabilityProperty.declaration(
                                "Declare repeatable only when this exact delete is safe to repeat after an unknown outcome.")),
                Set.of("network", "credential-reference", "side-effect"));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        try {
            StorageProfile profile = StorageSettings.profile(configuration, runtime.profiles,
                    StorageProfile.Operation.DELETE);
            String key = configuration.requiredProperty("key");
            int timeout = StorageSettings.tighten(configuration, "timeoutMs", profile.timeoutMs());
            int concurrency = StorageSettings.tighten(configuration, "maxConcurrency", profile.maxConcurrency());
            Semaphore gate = new Semaphore(concurrency, true);
            return StorageRuntime.action((message, cancellation) -> {
                try {
                    Map<String, Object> payload = StorageValues.object(message.payload(), "payload");
                    if (!payload.keySet().stream().allMatch(FIELDS::contains)
                            || !"object.delete.v1".equals(payload.get("version"))) {
                        throw StorageException.of(StorageException.Code.INVALID_INPUT);
                    }
                    String versionId = null;
                    if (payload.containsKey("versionId")) {
                        if (!profile.allowedOperations().contains(StorageProfile.Operation.DELETE_VERSION)) {
                            throw StorageException.of(StorageException.Code.INVALID_INPUT);
                        }
                        versionId = boundedVersion(StorageValues.string(payload.get("versionId"), "versionId", 1024));
                    }
                    URI destination = StorageUri.deleteDestination(profile, key, versionId);
                    StorageRuntime.Request request = new StorageRuntime.Request(destination, "DELETE", Map.of(),
                            new byte[0], timeout, Math.min(profile.maxObjectBytes(), 64 * 1024), 0,
                            StorageRuntime.Semantics.MUTATION,
                            ObjectDeleteNodeBehavior::project);
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

    private static String boundedVersion(String value) {
        if (value.codePoints().anyMatch(code -> code < 0x20 || code == 0x7f)) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
        return value;
    }

    private static NodeResult project(ai.ravenroot.api.node.service.OutboundHttpResponse response) {
        int status = response.statusCode();
        if (status >= 300 && status < 400) throw StorageException.of(StorageException.Code.REDIRECT_REFUSED);
        boolean deleted;
        String outcome;
        if (status == 404) {
            deleted = false;
            outcome = "NOT_FOUND";
        } else if (status == 200 || status == 204) {
            deleted = true;
            outcome = "DELETED";
        } else {
            throw StorageException.of(StorageException.Code.REMOTE_REJECTED);
        }
        if (status != 404 && response.body().length != 0) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", "object.delete.result.v1");
        output.put("status", outcome);
        output.put("deleted", deleted);
        return NodeResult.continueWith(Map.copyOf(output));
    }
}
