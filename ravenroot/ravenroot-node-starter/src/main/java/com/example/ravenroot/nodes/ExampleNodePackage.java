package com.example.ravenroot.nodes;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.node.NodeSdk;

import java.util.List;

/**
 * The deployable unit: what an operator names to install these node types.
 *
 * <p>Two ways to install it, and both are the deployment's own decision rather than any graph's:</p>
 *
 * <ul>
 *   <li><strong>Embedding application</strong> — build a {@code BehaviorRegistry}, call
 *   {@code NodePackages.register(registry, new ExampleNodePackage())}, and hand the registry to the
 *   application.</li>
 *   <li><strong>Shipped server or CLI</strong> — put this jar on the classpath and set
 *   {@code RAVENROOT_NODE_PACKAGES=com.example.ravenroot.nodes.ExampleNodePackage}. Both steps are
 *   required: being on the classpath is not enough, because classpath presence is not a decision
 *   anyone made about this package in particular.</li>
 * </ul>
 *
 * <p>Nothing in a graph can name this class, and nothing scans for it.</p>
 *
 * <p>A public no-argument constructor is required for the second form. The runtime instantiates only
 * class names it read from its own configuration.</p>
 */
public final class ExampleNodePackage implements NodePackage {

    @Override
    public String id() {
        return "com.example.ravenroot.nodes";
    }

    /** This package's own version, not the SDK's. Diagnostics only. */
    @Override
    public String version() {
        return "1.0.0";
    }

    /**
     * The SDK contract this package was compiled against.
     *
     * <p>Returns the constant, never a literal. {@code NodeSdk.CONTRACT} is a compile-time constant,
     * so its value is baked into this class file at build time — which is what lets a runtime detect
     * that this package was built against an SDK it no longer hosts. A hard-coded literal would say
     * the same thing today and would stop being true the moment it was copied into a package built
     * against a different SDK.</p>
     */
    @Override
    public String sdkContract() {
        return NodeSdk.CONTRACT;
    }

    @Override
    public List<NodeBehavior> behaviors() {
        return List.of(new GreetingNodeBehavior());
    }
}
