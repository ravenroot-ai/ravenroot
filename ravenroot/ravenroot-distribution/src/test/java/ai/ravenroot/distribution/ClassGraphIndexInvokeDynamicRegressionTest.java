package ai.ravenroot.distribution;

import ai.ravenroot.distribution.ClassGraphIndex.MethodRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassGraphIndex#registerCallPathFrom} must
 * follow a call reached through {@code invokedynamic} (a lambda or a method reference), not only
 * through an ordinary call instruction. Before this fix, {@code
 * ClassGraphIndex.CallGraphVisitor} overrode only {@code visitMethodInsn}, so a target reachable
 * exclusively via {@code invokedynamic} was invisible to the BFS in {@code registerCallPathFrom} -
 * exactly the shape of the shutdown-hook lambda already present in {@code RavenrootServerMain#main}
 * that the regression analysis used to fault-inject a silent pass in P3. See {@link InvokeDynamicCallGraphFixture}
 * for the two shapes this asserts against.
 */
class ClassGraphIndexInvokeDynamicRegressionTest {

    private static final String FIXTURE_OWNER = "ai/ravenroot/distribution/InvokeDynamicCallGraphFixture";
    private static final Map<String, String> MARKER_TARGET = Map.of(FIXTURE_OWNER, "markerMethod");

    @Test
    void registerCallPathFromFollowsLambdaInvokedynamicEdge() throws IOException {
        ClassGraphIndex index = ClassGraphIndex.scanJavaClassPath(Set.of());
        var path = index.registerCallPathFrom(
                new MethodRef(FIXTURE_OWNER, "viaLambda", "(Ljava/util/Optional;)V"), MARKER_TARGET);
        assertTrue(path.isPresent(), "registerCallPathFrom did not find InvokeDynamicCallGraphFixture"
                + "#markerMethod reached through a lambda (Optional.ifPresent(v -> markerMethod())). "
                + "This is the exact invokedynamic edge the fault-injection regression exploited in "
                + "RavenrootServerMain's shutdown-hook lambda: a call reached only via invokedynamic "
                + "was invisible to a call-graph visitor that only overrides visitMethodInsn.");
    }

    @Test
    void registerCallPathFromFollowsMethodReferenceInvokedynamicEdge() throws IOException {
        ClassGraphIndex index = ClassGraphIndex.scanJavaClassPath(Set.of());
        var path = index.registerCallPathFrom(
                new MethodRef(FIXTURE_OWNER, "viaMethodReference", "(Ljava/util/Optional;)V"), MARKER_TARGET);
        assertTrue(path.isPresent(), "registerCallPathFrom did not find InvokeDynamicCallGraphFixture"
                + "#markerMethod reached through a method reference "
                + "(Optional.ifPresent(Fixture::consumeAndMark)). Unlike a lambda, a method reference's "
                + "invokedynamic bootstrap Handle points directly at an existing method with no "
                + "synthetic wrapper - a distinct shape from the lambda case above and worth its own "
                + "assertion.");
    }
}
