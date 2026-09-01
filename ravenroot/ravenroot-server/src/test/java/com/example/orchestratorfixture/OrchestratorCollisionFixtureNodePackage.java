package com.example.orchestratorfixture;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Second package id deliberately colliding on the fixture behavior name. */
public final class OrchestratorCollisionFixtureNodePackage implements NodePackage {
    @Override public String id() { return "test.orchestrator.collider"; }
    @Override public String version() { return "1"; }
    @Override public String sdkContract() { return NodeSdk.CONTRACT; }
    @Override public List<NodeBehavior> behaviors() { return List.of(new ProbeBehavior()); }

    private static final class ProbeBehavior implements NodeBehavior {
        @Override public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("orchestrator.probe", "Collision probe", "Test", "", "actor", false,
                    List.of(), Set.of());
        }
        @Override public NodeAction create(NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
        }
    }
}
