package com.example.orchestratorfixture;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageCapability;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Classpath fixture proving operator service composition through the server orchestrator. */
public final class ServiceAwareOrchestratorFixtureNodePackage implements NodePackage {
    public static final AtomicBoolean LEGACY_CREATE_CALLED = new AtomicBoolean();
    public static final AtomicReference<NodePackageServices> RECEIVED_SERVICES = new AtomicReference<>();

    public static void reset() {
        LEGACY_CREATE_CALLED.set(false);
        RECEIVED_SERVICES.set(null);
    }

    @Override public String id() { return "test.orchestrator.services"; }
    @Override public String version() { return "1"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return List.of(new Behavior()); }

    private static final class Behavior implements NodeBehavior {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("orchestrator.services", "Services", "Test", "", "actor", false,
                    List.of(), Set.of());
        }
        @Override public Set<NodePackageCapability> requiredServices() {
            return Set.of(NodePackageCapability.OUTBOUND_HTTP);
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            LEGACY_CREATE_CALLED.set(true);
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
        @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
            RECEIVED_SERVICES.set(services);
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
    }
}
