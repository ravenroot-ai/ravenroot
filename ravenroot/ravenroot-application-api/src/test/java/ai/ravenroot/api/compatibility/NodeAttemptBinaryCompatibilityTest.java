package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.application.NodeAttempt;
import ai.ravenroot.api.application.NodeAttemptCompletion;
import ai.ravenroot.api.application.NodeAttemptStatus;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NodeAttempt}'s canonical constructor has grown twice, and neither growth was ever proved.
 *
 * <p>{@code parkCause} was added at PERS-04 and {@code withheldThroughDelivery} by the recovery
 * delivery-limit change; both added a compatibility constructor at the previous arity, and both
 * stated the guarantee in prose. Adding a record component changes the canonical constructor's
 * descriptor, which is a <em>binary</em> break for a caller already compiled against the older class
 * file rather than only a source break for one being recompiled — so prose and a compiling call site
 * are the wrong instrument, exactly as {@link BinaryCompatibility}'s own Javadoc says of the two
 * earlier occasions it was made and not proved.</p>
 *
 * <p>The negative control is the part that makes the rest mean anything: a shape that has never
 * existed must be reported as a break by both mechanisms. Without it, "every published shape still
 * links" would be indistinguishable from a harness that cannot detect anything.</p>
 */
class NodeAttemptBinaryCompatibilityTest {

    private static final String ID = "java.util.UUID.randomUUID()";
    private static final String STATUS = "ai.ravenroot.api.application.NodeAttemptStatus.SCHEDULED";
    private static final String NO_COMPLETION =
            "(ai.ravenroot.api.application.NodeAttemptCompletion) null";
    private static final String NO_CAUSE = "(java.lang.String) null";

    /** Every published shape, oldest first. The comment on each is the change that made it historical. */
    private static List<BinaryCompatibility.ConstructorShape> publishedShapes() {
        return List.of(
                // The shape a caller uses to schedule an attempt; completion and cause are derived.
                shape("3-argument, status only",
                        List.<Class<?>>of(UUID.class, int.class, NodeAttemptStatus.class),
                        List.of(ID, "1", STATUS)),
                // The pre-PERS-04 shape: a terminal completion, before parked attempts carried a cause.
                shape("4-argument, completion without a park cause",
                        List.<Class<?>>of(UUID.class, int.class, NodeAttemptStatus.class,
                                NodeAttemptCompletion.class),
                        List.of(ID, "1", STATUS, NO_COMPLETION)),
                // Historical as of the recovery delivery-limit change, which appended
                // withheldThroughDelivery. This is the shape that change made compatibility-only.
                shape("5-argument, before recovery recorded withheld deliveries",
                        List.<Class<?>>of(UUID.class, int.class, NodeAttemptStatus.class,
                                NodeAttemptCompletion.class, String.class),
                        List.of(ID, "1", STATUS, NO_COMPLETION, NO_CAUSE)));
    }

    /** A shape that has never been published. Both mechanisms must report a break for it. */
    private static BinaryCompatibility.ConstructorShape neverPublished() {
        return shape("4-argument, cause without completion, never published",
                List.<Class<?>>of(UUID.class, int.class, NodeAttemptStatus.class, String.class),
                List.of(ID, "1", STATUS, NO_CAUSE));
    }

    private static BinaryCompatibility.ConstructorShape shape(String description, List<Class<?>> types,
                                                              List<String> arguments) {
        return new BinaryCompatibility.ConstructorShape(description, types, arguments);
    }

    @TestFactory
    Stream<DynamicTest> everyPublishedShapeStillLinksAgainstTheCurrentClass() {
        return publishedShapes().stream().map(shape -> DynamicTest.dynamicTest(shape.description(), () ->
                assertTrue(BinaryCompatibility.linksAgainstCurrent(NodeAttempt.class, shape),
                        "a caller compiled against the " + shape.description() + " shape no longer "
                                + "links: adding a record component changed the descriptor it holds")));
    }

    @Test
    void everyPublishedConstructorDescriptorIsStillPresentInTheClassFile() {
        List<String> descriptors = BinaryCompatibility.declaredConstructorDescriptors(NodeAttempt.class);
        for (BinaryCompatibility.ConstructorShape shape : publishedShapes()) {
            assertTrue(descriptors.contains(shape.descriptor()),
                    () -> "the " + shape.description() + " shape is absent from the shipped class file: "
                            + descriptors);
        }
        assertTrue(descriptors.contains(BinaryCompatibility.descriptorOf(UUID.class, int.class,
                        NodeAttemptStatus.class, NodeAttemptCompletion.class, String.class, int.class)),
                "the current canonical constructor must be present too, or this test is measuring "
                        + "a class that no longer has the component the compatibility shapes default");
    }

    @Test
    void theHarnessReportsABreakForAShapeThatNeverExisted() throws Exception {
        BinaryCompatibility.ConstructorShape never = neverPublished();
        assertFalse(BinaryCompatibility.declaredConstructorDescriptors(NodeAttempt.class)
                        .contains(never.descriptor()),
                "the control shape must not exist, or it is not a control");
        assertFalse(BinaryCompatibility.linksAgainstCurrent(NodeAttempt.class, never),
                "an instrument that reports success for a shape that never existed measures nothing");
    }

    @Test
    void theCompatibilityShapeDefaultsTheWithheldMarkToZero() {
        var attempt = new NodeAttempt(UUID.randomUUID(), 1, NodeAttemptStatus.RUNNING, null, null);

        assertEquals(0, attempt.withheldThroughDelivery(),
                "an attempt written before recovery recorded withheld deliveries was never withheld "
                        + "by a mechanism that did not exist");
        assertEquals(4, attempt.decidedDeliveries(4),
                "so the subtraction the delivery limit feeds is a no-op for it");
    }
}
