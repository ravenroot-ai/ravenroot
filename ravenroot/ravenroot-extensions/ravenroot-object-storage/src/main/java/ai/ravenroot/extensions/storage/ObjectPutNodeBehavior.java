package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

public final class ObjectPutNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "object.put";
    private static final Set<String> FIELDS = Set.of("version", "text", "base64", "contentType", "ifMatch", "ifNoneMatch");
    private final StorageRuntime runtime;

    public ObjectPutNodeBehavior() { this(new StorageRuntime(new EnvironmentStorageProfileResolver())); }
    ObjectPutNodeBehavior(StorageRuntime runtime) { this.runtime = runtime; }

    @Override public Set<NodePackageCapability> requiredServices() {
        return Set.of(NodePackageCapability.OUTBOUND_HTTP);
    }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor(BEHAVIOR, "Put object", "Object storage",
                "Writes one bounded object to an operator-owned S3-compatible profile.", "actor", false,
                List.of(ObjectGetNodeBehavior.required("storageProfile", "Storage profile", "Opaque operator-owned bucket profile."),
                        ObjectGetNodeBehavior.required("key", "Object key", "Relative key inside the operator prefix."),
                        ObjectGetNodeBehavior.optionalInt("maxBytes", "Maximum bytes", "May only tighten the profile ceiling."),
                        ObjectGetNodeBehavior.optionalInt("timeoutMs", "Deadline", "May only tighten the profile deadline."),
                        ObjectGetNodeBehavior.optionalInt("maxConcurrency", "Concurrency", "May only tighten the profile ceiling.")),
                Set.of("network", "credential-reference", "side-effect"));
    }

    @Override public NodeAction create(NodeConfiguration configuration) {
        return create(configuration, NodePackageServices.unavailable());
    }

    @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
        StorageSettings settings = StorageSettings.compile(configuration, runtime.profiles, StorageProfile.Operation.PUT);
        Semaphore action = new Semaphore(settings.maxConcurrency(), true);
        return message -> {
            try {
                Prepared prepared = prepare(message.payload(), settings);
                return runtime.execute(message, services, settings, action, prepared.body, prepared.headers);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure instanceof StorageException ? failure
                        : StorageException.of(StorageException.Code.INVALID_INPUT));
            }
        };
    }

    private static Prepared prepare(Object value, StorageSettings settings) {
        Map<String, Object> map = StorageValues.object(value, "payload");
        if (!map.keySet().stream().allMatch(FIELDS::contains) || !"object.put.v1".equals(map.get("version"))
                || (map.containsKey("text") == map.containsKey("base64"))) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
        byte[] body;
        if (map.containsKey("text")) {
            if (!(map.get("text") instanceof String text)) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
            if (text.length() > settings.maxBytes()) {
                throw StorageException.of(StorageException.Code.REQUEST_TOO_LARGE);
            }
            body = StorageRuntime.strictTextBytes(text);
        } else {
            String encoded = StorageValues.string(map.get("base64"), "base64",
                    Math.multiplyExact(settings.maxBytes(), 2));
            try {
                body = Base64.getDecoder().decode(encoded);
                if (!Base64.getEncoder().encodeToString(body).equals(encoded)) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
        }
        if (body.length > settings.maxBytes()) throw StorageException.of(StorageException.Code.REQUEST_TOO_LARGE);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        optional(map, "contentType").ifPresent(valueText -> {
            String normalized = valueText.toLowerCase(Locale.ROOT);
            if (!settings.profile().allowedContentTypes().contains(normalized)) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
            headers.put("content-type", List.of(normalized));
        });
        optional(map, "ifMatch").ifPresent(valueText -> {
            if (!settings.profile().allowIfMatch() || !conditional(valueText)) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
            headers.put("if-match", List.of(valueText));
        });
        optional(map, "ifNoneMatch").ifPresent(valueText -> {
            if (!settings.profile().allowIfNoneMatch() || !conditional(valueText)) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
            headers.put("if-none-match", List.of(valueText));
        });
        return new Prepared(body, Map.copyOf(headers));
    }

    private static java.util.Optional<String> optional(Map<String, Object> map, String field) {
        Object value = map.get(field);
        return value == null ? java.util.Optional.empty()
                : java.util.Optional.of(StorageValues.string(value, field, 512));
    }

    private static boolean conditional(String value) {
        return value.equals("*") || (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                && value.substring(1, value.length() - 1).chars().allMatch(ch -> ch >= 0x21 && ch != 0x7f && ch != '"'));
    }

    private record Prepared(byte[] body, Map<String, List<String>> headers) { }
}
