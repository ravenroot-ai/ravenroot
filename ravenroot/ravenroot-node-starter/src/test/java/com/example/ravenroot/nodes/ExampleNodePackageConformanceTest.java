package com.example.ravenroot.nodes;

import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.testkit.api.NodeBehaviorContract;

/**
 * Runs the published node conformance contract against this package.
 *
 * <p>This is the whole test: one method, and every assertion is inherited. That is the point of the
 * TCK — a third-party author gets the same checks the built-in catalog is held to, without having to
 * know what they are or keep them in step as the SDK evolves.</p>
 *
 * <p>Note what this test does <em>not</em> need: no engine, no {@code GraphRunner}, no
 * {@code ravenroot-core} on the test classpath at all. If a future change makes it need one, the SDK
 * boundary has regressed.</p>
 */
class ExampleNodePackageConformanceTest extends NodeBehaviorContract {

    @Override
    protected NodePackage nodePackage() {
        return new ExampleNodePackage();
    }
}
