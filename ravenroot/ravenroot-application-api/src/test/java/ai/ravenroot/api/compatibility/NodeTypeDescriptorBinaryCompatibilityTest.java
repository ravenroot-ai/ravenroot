package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeRuntimeConcurrency;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every constructor shape {@link NodeTypeDescriptor} has published, proved still binary compatible
 * when runtime-nature components were added.
 *
 * <h2>Why this test did not exist before and does now</h2>
 * <p>{@link NodeTypeDescriptor} is the type every SDK node package and plugin bundle constructs, and
 * until runtime-nature support it had never grown a component — so its canonical constructor had never
 * been at risk and nothing measured it. Adding two components makes the old 8-argument shape a compatibility overload
 * whose survival now has to be proved rather than asserted. That is the same situation
 * {@code NodePropertyDescriptorBinaryCompatibilityTest} documents for its own record, and the same
 * harness answers it.
 *
 * <p>The risk is concrete rather than theoretical: an out-of-tree node package compiled against the
 * 8-argument descriptor records that descriptor in its constant pool, and a build that dropped the
 * overload would fail it with {@link NoSuchMethodError} at load, not at compile.
 */
class NodeTypeDescriptorBinaryCompatibilityTest {

    private static final List<String> HEAD = List.of("\"behavior\"", "\"Display\"", "\"Category\"",
            "\"description\"", "\"actor\"", "false", "java.util.List.of()", "java.util.Set.<String>of()");

    private static List<BinaryCompatibility.ConstructorShape> publishedShapes() {
        return List.of(
                // Everything published before runtime natures -- the shape every existing node package uses.
                new BinaryCompatibility.ConstructorShape("8-argument, canonical before runtime natures",
                        List.of(String.class, String.class, String.class, String.class, String.class,
                                boolean.class, List.class, Set.class),
                        HEAD),
                new BinaryCompatibility.ConstructorShape("10-argument, canonical before commands",
                        List.of(String.class, String.class, String.class, String.class, String.class,
                                boolean.class, List.class, Set.class, NodeRuntimeNature.class, Set.class),
                        concat(HEAD, List.of("(ai.ravenroot.api.catalog.NodeRuntimeNature) null",
                                "java.util.Set.<ai.ravenroot.api.catalog.NodeRuntimeNature>of()"))),
                new BinaryCompatibility.ConstructorShape("11-argument, canonical before outcomes",
                        List.of(String.class, String.class, String.class, String.class, String.class,
                                boolean.class, List.class, Set.class, NodeRuntimeNature.class, Set.class,
                                Set.class),
                        concat(HEAD, List.of("(ai.ravenroot.api.catalog.NodeRuntimeNature) null",
                                "java.util.Set.<ai.ravenroot.api.catalog.NodeRuntimeNature>of()",
                                "java.util.Set.<String>of()"))),
                new BinaryCompatibility.ConstructorShape("12-argument, canonical before concurrency",
                        List.of(String.class, String.class, String.class, String.class, String.class,
                                boolean.class, List.class, Set.class, NodeRuntimeNature.class, Set.class,
                                Set.class, List.class),
                        concat(HEAD, List.of("(ai.ravenroot.api.catalog.NodeRuntimeNature) null",
                                "java.util.Set.<ai.ravenroot.api.catalog.NodeRuntimeNature>of()",
                                "java.util.Set.<String>of()",
                                "java.util.List.<ai.ravenroot.api.catalog.NodeOutcomeDescriptor>of()"))),
                new BinaryCompatibility.ConstructorShape("13-argument, current canonical",
                        List.of(String.class, String.class, String.class, String.class, String.class,
                                boolean.class, List.class, Set.class, NodeRuntimeNature.class, Set.class,
                                Set.class, List.class, NodeRuntimeConcurrency.class),
                        concat(HEAD, List.of("(ai.ravenroot.api.catalog.NodeRuntimeNature) null",
                                "java.util.Set.<ai.ravenroot.api.catalog.NodeRuntimeNature>of()",
                                "java.util.Set.<String>of()",
                                "java.util.List.<ai.ravenroot.api.catalog.NodeOutcomeDescriptor>of()",
                                "ai.ravenroot.api.catalog.NodeRuntimeConcurrency.DEFAULT"))));
    }

    /** A shape never published. Both mechanisms must report a break, or every case above is vacuous. */
    private static BinaryCompatibility.ConstructorShape neverPublished() {
        return new BinaryCompatibility.ConstructorShape("9-argument, never published",
                List.of(String.class, String.class, String.class, String.class, String.class,
                        boolean.class, List.class, Set.class, NodeRuntimeNature.class),
                concat(HEAD, List.of("(ai.ravenroot.api.catalog.NodeRuntimeNature) null")));
    }

    @TestFactory
    Stream<DynamicTest> everyPublishedShapeStillLinks() {
        return publishedShapes().stream().map(shape -> DynamicTest.dynamicTest(shape.description(), () ->
                assertTrue(BinaryCompatibility.linksAgainstCurrent(NodeTypeDescriptor.class, shape),
                        "a caller compiled against the " + shape.description() + " shape no longer links: "
                                + shape.descriptor() + " is gone, so every SDK node package and plugin "
                                + "bundle already built against it fails with NoSuchMethodError at run time")));
    }

    @TestFactory
    Stream<DynamicTest> javapReportsEveryPublishedShape() {
        List<String> descriptors =
                BinaryCompatibility.declaredConstructorDescriptors(NodeTypeDescriptor.class);
        return publishedShapes().stream().map(shape -> DynamicTest.dynamicTest(shape.description(), () ->
                assertTrue(descriptors.contains(shape.descriptor()),
                        "the class file declares no constructor " + shape.descriptor() + ". javap: "
                                + descriptors)));
    }

    @Test
    void reportsABreakForAShapeThatNeverExisted() throws Exception {
        assertFalse(BinaryCompatibility.linksAgainstCurrent(NodeTypeDescriptor.class, neverPublished()),
                "the linkage check passed for a 9-argument shape that was never published, so it cannot "
                        + "tell a present constructor from an absent one and every case above is vacuous");
    }

    @Test
    void javapNeverReportsAShapeThatNeverExisted() {
        assertFalse(BinaryCompatibility.declaredConstructorDescriptors(NodeTypeDescriptor.class)
                .contains(neverPublished().descriptor()));
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        return Stream.concat(head.stream(), tail.stream()).toList();
    }
}
