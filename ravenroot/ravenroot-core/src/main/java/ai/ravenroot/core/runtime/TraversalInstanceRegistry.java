package ai.ravenroot.core.runtime;

import ai.ravenroot.api.execution.NodeRef;
import ai.ravenroot.api.execution.RavenNode;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** One demand-created actor per traversal/node identity, reused for cycle re-entry. */
final class TraversalInstanceRegistry {
    private final BiFunction<String, RavenNode, NodeRef> spawner;
    private final ConcurrentHashMap<TraversalInstanceIdentity, TraversalInstance> live =
            new ConcurrentHashMap<>();
    private final java.util.Set<TraversalInstance> retiring = ConcurrentHashMap.newKeySet();

    TraversalInstanceRegistry(BiFunction<String, RavenNode, NodeRef> spawner) {
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    TraversalInstance acquire(TraversalInstanceIdentity identity, RavenNode runtime,
                              Supplier<ExecutionBudget.Actor> actorReservation) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(actorReservation, "actorReservation");
        return live.computeIfAbsent(identity, key -> {
            ExecutionBudget.Actor reservation = actorReservation.get();
            try {
                return new TraversalInstance(key, spawner.apply(key.actorName(), runtime), reservation);
            } catch (RuntimeException failure) {
                reservation.close();
                throw failure;
            }
        });
    }

    List<TraversalInstance> deregister(UUID traversalId) {
        var removed = new java.util.ArrayList<TraversalInstance>();
        live.forEach((identity, instance) -> {
            if (traversalId.equals(identity.traversalId()) && live.remove(identity, instance)) {
                removed.add(instance);
                retiring.add(instance);
                instance.actorReservation().close();
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
    void deregisterAll() {
        live.values().forEach(instance -> instance.actorReservation().close());
        retiring.forEach(instance -> instance.actorReservation().close());
        live.clear();
        retiring.clear();
    }
    int liveCount() { return live.size(); }
    int liveCount(String nodeId) {
        return (int) live.keySet().stream().filter(key -> key.nodeId().equals(nodeId)).count();
    }

    record TraversalInstance(TraversalInstanceIdentity identity, NodeRef ref,
                             ExecutionBudget.Actor actorReservation) { }
}
