package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.RavenNode;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/** One demand-created actor per traversal/node identity, reused for cycle re-entry. */
final class TraversalInstanceRegistry {
    private final BiFunction<String, RavenNode, NodeRef> spawner;
    private final ConcurrentHashMap<TraversalInstanceIdentity, TraversalInstance> live =
            new ConcurrentHashMap<>();
    private final java.util.Set<TraversalInstance> retiring = ConcurrentHashMap.newKeySet();

    TraversalInstanceRegistry(BiFunction<String, RavenNode, NodeRef> spawner) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    TraversalInstance acquire(TraversalInstanceIdentity identity, RavenNode runtime) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(runtime, "runtime");
        return live.computeIfAbsent(identity, key ->
                new TraversalInstance(key, spawner.apply(key.actorName(), runtime)));
    }

    List<TraversalInstance> deregister(UUID traversalId) {
        var removed = new java.util.ArrayList<TraversalInstance>();
        live.forEach((identity, instance) -> {
            if (traversalId.equals(identity.traversalId()) && live.remove(identity, instance)) {
                removed.add(instance);
                retiring.add(instance);
            }
        });
        return List.copyOf(removed);
    }

    Collection<TraversalInstance> outstanding() {
        var all = new java.util.ArrayList<>(live.values());
        all.addAll(retiring);
        return List.copyOf(all);
    }
    void retired(Collection<TraversalInstance> instances) { retiring.removeAll(instances); }
    void deregisterAll() { live.clear(); retiring.clear(); }
    int liveCount() { return live.size(); }
    int liveCount(String nodeId) {
        return (int) live.keySet().stream().filter(key -> key.nodeId().equals(nodeId)).count();
    }

    record TraversalInstance(TraversalInstanceIdentity identity, NodeRef ref) { }
}
