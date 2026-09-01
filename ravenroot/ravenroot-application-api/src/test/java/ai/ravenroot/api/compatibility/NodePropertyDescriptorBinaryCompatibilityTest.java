package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.PropertyCondition;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every constructor shape {@link NodePropertyDescriptor} has published, proved still binary
 * compatible when conditional components are added.
 *
 * <h2>This retro-validates a claim that was never proved</h2>
 * <p>The record already grew once: CORE-07 added {@code adapterBinding} and left a 7-argument
 * compatibility overload behind. That overload's compatibility was asserted in prose and backed by
 * nothing — exactly the state {@link BinaryCompatibility}'s own Javadoc describes as a statement
 * about {@code javac} rather than about linkage. Conditional properties add two more components, so rather than
 * repeating the situation the harness is pointed at the older shape as well.
 *
 * <p>Proving the instrument on a case whose answer is already known, before believing it on the case
 * it was brought in for, is why the harness exists at all.
 */
class NodePropertyDescriptorBinaryCompatibilityTest {
    private static final List<String> HEAD = List.of("\"name\"", "\"Display\"",
            "ai.ravenroot.api.catalog.NodePropertyType.STRING", "true", "\"description\"", "\"\"",
            "java.util.List.<String>of()");

    private static List<BinaryCompatibility.ConstructorShape> publishedShapes() {
        return List.of(
                // Pre-CORE-07: before adapterBinding.
                new BinaryCompatibility.ConstructorShape("7-argument, before CORE-07's adapterBinding",
                        List.of(String.class, String.class, NodePropertyType.class, boolean.class,
                                String.class, String.class, List.class),
                        HEAD),
                // Canonical from CORE-07 until conditional properties.
                new BinaryCompatibility.ConstructorShape("8-argument, canonical before conditions",
                        List.of(String.class, String.class, NodePropertyType.class, boolean.class,
                                String.class, String.class, List.class, boolean.class),
                        concat(HEAD, List.of("false"))),
                // Current canonical.
                new BinaryCompatibility.ConstructorShape("10-argument, current canonical",
                        List.of(String.class, String.class, NodePropertyType.class, boolean.class,
                                String.class, String.class, List.class, boolean.class,
                                PropertyCondition.class, PropertyCondition.class),
                        concat(HEAD, List.of("false", "(ai.ravenroot.api.catalog.PropertyCondition) null",
                                "(ai.ravenroot.api.catalog.PropertyCondition) null"))));
    }

    /** A shape never published. Both mechanisms must report a break, or every case above is vacuous. */
    private static BinaryCompatibility.ConstructorShape neverPublished() {
        return new BinaryCompatibility.ConstructorShape("9-argument, never published",
                List.of(String.class, String.class, NodePropertyType.class, boolean.class, String.class,
                        String.class, List.class, boolean.class, PropertyCondition.class),
                concat(HEAD, List.of("false", "(ai.ravenroot.api.catalog.PropertyCondition) null")));
    }

    @TestFactory
    Stream<DynamicTest> everyPublishedShapeStillLinks() {
        return publishedShapes().stream().map(shape -> DynamicTest.dynamicTest(shape.description(), () ->
                assertTrue(BinaryCompatibility.linksAgainstCurrent(NodePropertyDescriptor.class, shape),
                        "a caller compiled against the " + shape.description() + " shape no longer links: "
                                + shape.descriptor() + " is gone, so every SDK node package already built "
                                + "against it fails with NoSuchMethodError at run time")));
    }

    @TestFactory
    Stream<DynamicTest> javapReportsEveryPublishedShape() {
        List<String> descriptors =
                BinaryCompatibility.declaredConstructorDescriptors(NodePropertyDescriptor.class);
        return publishedShapes().stream().map(shape -> DynamicTest.dynamicTest(shape.description(), () ->
                assertTrue(descriptors.contains(shape.descriptor()),
                        "the class file declares no constructor " + shape.descriptor() + ". javap: "
                                + descriptors)));
    }

    @Test
    void reportsABreakForAShapeThatNeverExisted() throws Exception {
        assertFalse(BinaryCompatibility.linksAgainstCurrent(NodePropertyDescriptor.class, neverPublished()),
                "the linkage check passed for a 9-argument shape that was never published, so it cannot "
                        + "tell a present constructor from an absent one and every case above is vacuous");
    }

    @Test
    void javapNeverReportsAShapeThatNeverExisted() {
        assertFalse(BinaryCompatibility.declaredConstructorDescriptors(NodePropertyDescriptor.class)
                .contains(neverPublished().descriptor()));
    }

    private static List<String> concat(List<String> head, List<String> tail) {
        return Stream.concat(head.stream(), tail.stream()).toList();
    }
}
