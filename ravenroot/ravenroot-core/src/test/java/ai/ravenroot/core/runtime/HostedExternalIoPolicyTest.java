package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.catalog.NodeBypassProperty;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.node.ExecutionIoCapacityCapable;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodeExternalIoCapacity;
import ai.ravenroot.api.node.service.NodePackageServices;
import ai.ravenroot.api.persistence.ResolvedOperationalPolicy;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A deployment runner selects one exact node-I/O snapshot for each overlapping execution. */
class HostedExternalIoPolicyTest {
    private static final SecurityContext IDENTITY = new SecurityContext("request", "tenant-a", "subject",
            PrincipalType.WORKLOAD, "issuer");

    @Test void oldThenNewPinsCoexistOnOneHostedRunner() {
        assertCoexistingPins(false);
    }

    @Test void newThenOldPinsCoexistOnOneHostedRunner() {
        assertCoexistingPins(true);
    }

    @Test void bypassedWebSocketSendNeverResolvesItsMissingProfileOrCreatesAnAction() {
        var behavior = new MissingProfileWebSocketSendBehavior();
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage(behavior));
        GraphNode socket = new GraphNode("socket", NodeKind.BEHAVIOR, "websocket.send",
                Map.of(NodeBypassProperty.NAME, "true", "websocketProfile", "missing"));
        var definition = new GraphDefinition(List.of(GraphNode.start("start"), socket,
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "socket"), GraphEdge.to("socket", "end")));

        var engine = new SameThreadExecutionEngine();
        try (var manager = GraphManager.from(definition);
             var runner = new GraphRunner(manager, engine, registry, new ExecutionMonitor())) {
            GraphExecutionResult result = runner.execute(IDENTITY, "payload").toCompletableFuture().join();

            assertEquals(Set.of("socket"), result.bypassedNodes());
            assertEquals(0, behavior.resolutions.get(),
                    "an authored bypass must not ask package code to resolve a missing profile");
            assertEquals(0, behavior.actionCreations.get(),
                    "an authored bypass must not construct an external-I/O action");
        } finally {
            engine.close();
        }
    }

    private static void assertCoexistingPins(boolean narrowFirst) {
        var behavior = new CapacityBehavior();
        BehaviorRegistry registry = NodePackages.register(new BehaviorRegistry(), nodePackage(behavior));
        GraphNode socket = new GraphNode("socket", NodeKind.BEHAVIOR, "test.io", Map.of());
        var definition = new GraphDefinition(List.of(GraphNode.start("start"), socket,
                GraphNode.error("error"), GraphNode.end("end")), List.of(
                GraphEdge.to("start", "socket"), GraphEdge.to("socket", "end")));
        var oldCapacity = new NodeExternalIoCapacity(512, 8, Duration.ofSeconds(4), 2);
        var newCapacity = new NodeExternalIoCapacity(128, 2, Duration.ofSeconds(1), 1);
        String binding = registry.externalIoBindingDigest(socket);
        ResolvedOperationalPolicy base = registry.unpinnedOperationalPolicy(GraphExecutionLimits.DEFAULTS);
        ResolvedOperationalPolicy oldPolicy = withNodeCapacity(base, binding, oldCapacity);
        ResolvedOperationalPolicy newPolicy = withNodeCapacity(base, binding, newCapacity);
        behavior.currentAuthorization.set("current-profile-auth");

        var engine = new SameThreadExecutionEngine();
        try (var manager = GraphManager.from(definition);
             var runner = new GraphRunner(manager, engine, null, registry, new ExecutionMonitor(),
                     ExecutionIdentitySource.randomUuids(), Duration.ofSeconds(1),
                     GraphExecutionLimits.DEFAULTS, true)) {
            assertEquals(0, behavior.resolutions.get(),
                    "hosted startup must not resolve today's numeric profile before a process pin is loaded");
            var firstPolicy = narrowFirst ? newPolicy : oldPolicy;
            var secondPolicy = narrowFirst ? oldPolicy : newPolicy;
            String first = narrowFirst ? "new" : "old";
            String second = narrowFirst ? "old" : "new";
            CompletableFuture<?> firstRun = execute(runner, first, firstPolicy);
            CompletableFuture<?> secondRun = execute(runner, second, secondPolicy);

            assertEquals(oldCapacity, behavior.seenCapacities.get("old"));
            assertEquals(newCapacity, behavior.seenCapacities.get("new"));
            assertEquals(Map.of("old", "current-profile-auth", "new", "current-profile-auth"),
                    behavior.seenAuthorizations);
            assertEquals(2, behavior.actionCreations.get(),
                    "each traversal materializes its pin-capable action once");
            assertEquals(2, registry.activeOperationalPolicyCount());

            behavior.completions.get(first).complete(null);
            behavior.completions.get(second).complete(null);
            firstRun.join();
            secondRun.join();
            assertEquals(0, registry.activeOperationalPolicyCount(),
                    "handler scopes are released with their traversal policy bindings");
        } finally {
            engine.close();
        }
    }

    private static CompletableFuture<?> execute(GraphRunner runner, String payload,
                                                 ResolvedOperationalPolicy policy) {
        return runner.execute(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), payload, "graph-v1",
                "deployment", payload, null, policy, GraphExecutionLimits.DEFAULTS).toCompletableFuture();
    }

    private static ResolvedOperationalPolicy withNodeCapacity(ResolvedOperationalPolicy base, String binding,
                                                               NodeExternalIoCapacity capacity) {
        return new ResolvedOperationalPolicy(base.graph(), base.results(), base.builtInHttp(),
                base.nodePackages(), base.persistence(),
                List.of(new ResolvedOperationalPolicy.NodeIoCapacity(binding, capacity)));
    }

    private static NodePackage nodePackage(CapacityBehavior behavior) {
        return new NodePackage() {
            @Override public String id() { return "test.io.package"; }
            @Override public String version() { return "1"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
    }

    private static NodePackage nodePackage(NodeBehavior behavior) {
        return new NodePackage() {
            @Override public String id() { return "test.websocket.package"; }
            @Override public String version() { return "1"; }
            @Override public String sdkContract() { return NodeSdk.CONTRACT; }
            @Override public List<NodeBehavior> behaviors() { return List.of(behavior); }
        };
    }

    private static final class MissingProfileWebSocketSendBehavior
            implements NodeBehavior, ExecutionIoCapacityCapable {
        private final AtomicInteger resolutions = new AtomicInteger();
        private final AtomicInteger actionCreations = new AtomicInteger();

        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("websocket.send", "WebSocket send", "Test", "", "actor", false,
                    List.of(), Set.of());
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            actionCreations.incrementAndGet();
            throw new AssertionError("a bypassed websocket.send must not resolve its missing profile");
        }

        @Override public NodeExternalIoCapacity resolveExecutionIoCapacity(NodeConfiguration configuration) {
            resolutions.incrementAndGet();
            throw new IllegalArgumentException("missing WebSocket profile");
        }

        @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services,
                                           NodeExternalIoCapacity capacity) {
            actionCreations.incrementAndGet();
            throw new AssertionError("a bypassed websocket.send must not materialize a pinned action");
        }
    }

    private static final class CapacityBehavior implements NodeBehavior, ExecutionIoCapacityCapable {
        private final AtomicInteger resolutions = new AtomicInteger();
        private final AtomicInteger actionCreations = new AtomicInteger();
        private final AtomicReference<String> currentAuthorization = new AtomicReference<>("startup-auth");
        private final Map<String, NodeExternalIoCapacity> seenCapacities = new ConcurrentHashMap<>();
        private final Map<String, String> seenAuthorizations = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<Void>> completions = new ConcurrentHashMap<>();

        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("test.io", "I/O", "Test", "", "actor", false,
                    List.of(), Set.of());
        }

        @Override public NodeAction create(NodeConfiguration configuration) {
            throw new AssertionError("hosted execution must use its pinned capacity overload");
        }

        @Override public NodeExternalIoCapacity resolveExecutionIoCapacity(NodeConfiguration configuration) {
            resolutions.incrementAndGet();
            return new NodeExternalIoCapacity(64, 1, Duration.ofMillis(100), 1);
        }

        @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services,
                                           NodeExternalIoCapacity capacity) {
            actionCreations.incrementAndGet();
            String authorization = currentAuthorization.get();
            return message -> {
                String name = String.valueOf(message.payload());
                seenCapacities.put(name, capacity);
                seenAuthorizations.put(name, authorization);
                CompletableFuture<Void> completion = completions.computeIfAbsent(name,
                        ignored -> new CompletableFuture<>());
                return completion.thenApply(ignored -> ai.ravenroot.api.execution.NodeResult.continueWith(name));
            };
        }
    }
}
