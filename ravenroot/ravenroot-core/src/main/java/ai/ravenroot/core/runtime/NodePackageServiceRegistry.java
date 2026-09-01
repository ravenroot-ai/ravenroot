package ai.ravenroot.core.runtime;

import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit operator composition of immutable service views, keyed by exact NodePackage id. */
public final class NodePackageServiceRegistry {
    private static final NodePackageServiceRegistry EMPTY = new NodePackageServiceRegistry(Map.of());

    private final Map<String, Entry> byPackageId;

    private NodePackageServiceRegistry(Map<String, Entry> byPackageId) {
        this.byPackageId = Map.copyOf(byPackageId);
    }

    public static NodePackageServiceRegistry empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    NodePackageServices servicesFor(String packageId) {
        Entry entry = byPackageId.get(packageId);
        return entry == null ? NodePackageServices.unavailable() : entry.services();
    }

    public Set<NodePackageCapability> capabilitiesFor(String packageId) {
        Entry entry = byPackageId.get(packageId);
        return entry == null ? Set.of() : entry.capabilities();
    }

    public static final class Builder {
        private final Map<String, Entry> grants = new LinkedHashMap<>();

        /** Registers exactly one immutable view for an exact package id. Duplicate grants fail. */
        public Builder grant(String packageId, NodePackageServices services) {
            String id = NodePackages.requireValidPackageId(packageId);
            NodePackageServices safe = Objects.requireNonNull(services, "services");
            Set<NodePackageCapability> capabilities = Set.copyOf(
                    Objects.requireNonNull(safe.capabilities(), "services.capabilities"));
            if (grants.putIfAbsent(id, new Entry(safe, capabilities)) != null) {
                throw new IllegalArgumentException("Node package services already registered for '" + id + "'");
            }
            return this;
        }

        public NodePackageServiceRegistry build() {
            return grants.isEmpty() ? EMPTY : new NodePackageServiceRegistry(grants);
        }
    }

    private record Entry(NodePackageServices services, Set<NodePackageCapability> capabilities) {
    }
}
