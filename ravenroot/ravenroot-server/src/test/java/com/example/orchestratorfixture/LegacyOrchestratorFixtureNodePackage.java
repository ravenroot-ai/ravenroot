package com.example.orchestratorfixture;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;
import ai.ravenroot.api.node.service.NodePackageServices;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK /1 fixture proving the orchestrator never injects the additive service view. */
public final class LegacyOrchestratorFixtureNodePackage implements NodePackage {
    public static final AtomicBoolean LEGACY_CREATE_CALLED = new AtomicBoolean();
    public static final AtomicBoolean SERVICE_CREATE_CALLED = new AtomicBoolean();

    public static void reset() {
        LEGACY_CREATE_CALLED.set(false);
        SERVICE_CREATE_CALLED.set(false);
    }

    @Override public String id() { return "test.orchestrator.legacy"; }
    @Override public String version() { return "1"; }
    @Override public String sdkContract() { return NodeSdk.LEGACY_CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return List.of(new Behavior()); }

    private static final class Behavior implements NodeBehavior {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("orchestrator.legacy", "Legacy", "Test", "", "actor", false,
                    List.of(), Set.of());
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            LEGACY_CREATE_CALLED.set(true);
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
        @Override public NodeAction create(NodeConfiguration configuration, NodePackageServices services) {
            SERVICE_CREATE_CALLED.set(true);
            return create(configuration);
        }
    }
}
