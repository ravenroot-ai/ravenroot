package com.example.orchestratorfixture;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Test fixture only (PLAT-12): a real, working {@code NodePackage} for orchestrator-level
 * tests. Its package is deliberately {@code com.example.orchestratorfixture}, NOT {@code
 * ai.ravenroot.*}, so the reserved-namespace check admits it instead of rejecting the product-owned
 * {@code ai.ravenroot.server.*} root.
 */
public final class OrchestratorFixtureNodePackage implements NodePackage {
    @Override
    public String id() {
        return "test.orchestrator.fixture";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String sdkContract() {
        return NodeSdk.CONTRACT;
    }

    @Override
    public List<NodeBehavior> behaviors() {
        return List.of(new ProbeBehavior());
    }

    private static final class ProbeBehavior implements NodeBehavior {
        @Override
        public NodeTypeDescriptor descriptor() {
            return new NodeTypeDescriptor("orchestrator.probe", "Orchestrator Probe", "Test", "Test fixture only",
                    "actor", false, List.of(), Set.of());
        }

        @Override
        public NodeAction create(ai.ravenroot.api.node.NodeConfiguration configuration) {
            return message -> CompletableFuture.completedFuture(NodeResult.continueWith(null));
        }
    }
}
