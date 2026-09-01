package ai.ravenroot.api.compatibility;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every constructor shape {@link ExecutionEvent} has ever published, proved still binary compatible
 * when catalog identity was added.
 *
 * <h2>What was here before, and why it was not enough</h2>
 * <p>{@code ExecutionEventProcessingDurationTest} asserts the earlier argument list still compiles.
 * That is a statement about {@code javac}, not about linkage: it passes identically whether the
 * compatibility constructor exists or the canonical one merely happens to accept those arguments.
 * The binary claim — {@code ExecutionEvent}'s own Javadoc, "a binary break as well as a source one
 * for any caller built against the older class file" — was prose. This file is that claim measured.
 *
 * <h2>The shapes are written out here on purpose</h2>
 * <p>Deriving them from the current class would make the test agree with whatever the class happens
 * to declare, which is the shape of an assertion that cannot fail. Written out, they are a contract:
 * deleting any compatibility constructor turns its own case red, and each case names the change that
 * introduced it so a future author can see what they would be breaking.
 *
 * <h2>Instrument validated before use</h2>
 * <p>{@link #reportsABreakForAShapeThatNeverExisted()} and
 * {@link #javapNeverReportsADescriptorForAShapeThatNeverExisted()} drive a 13-argument shape that has
 * never been published, and require both mechanisms to report a break. Without them, "every published
 * shape links" would be indistinguishable from a harness that cannot detect anything — and the harness
 * was run against these controls first, before it was pointed at any shape it was meant to bless.
 * <b>Deliberately not a count here</b>: {@link #publishedShapes()} is the one place the number of
 * shapes is allowed to live, because it is the one place a new compatibility constructor updates it
 * automatically. A numeral repeated in this class comment would need a second edit every time a shape
 * is added, and the shape most likely to skip that edit is exactly the one adding a numbered
 * constructor to a numbered comment right below it.
 */
class ExecutionEventBinaryCompatibilityTest {
    /** Valid for the compact constructor: NODE_STARTED with absent durations passes every check. */
    private static final List<String> IDENTITY_ARGUMENTS = List.of(
            "1L", "java.time.Instant.EPOCH", "\"tenant\"", "\"request\"", "\"engine\"", "\"graph\"");
    private static final String PROCESS = "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000001\")";
    private static final String TRAVERSAL = "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000002\")";
    private static final String INVOCATION = "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000003\")";
    private static final String ATTEMPT = "java.util.UUID.fromString(\"00000000-0000-0000-0000-000000000004\")";
    private static final String TYPE = "ai.ravenroot.api.application.ExecutionEventType.NODE_STARTED";
    private static final List<String> TAIL_ARGUMENTS = List.of("\"node\"", "0", "false", "\"detail\"");

    /**
     * Every published shape, oldest first. The comment on each is the change that made it historical.
     */
    private static List<BinaryCompatibility.ConstructorShape> publishedShapes() {
        return List.of(
                // The earlier implementation event shape, for adapters producing an event outside a node invocation.
                shape("12-argument, outside a node invocation",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, ExecutionEventType.class, String.class, int.class, boolean.class,
                                String.class),
                        concat(IDENTITY_ARGUMENTS, List.of(TRAVERSAL, TYPE), TAIL_ARGUMENTS)),
                // The earlier implementation shape with invocation identity but no attemptId.
                shape("14-argument, attempt1 event shape",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, ExecutionEventType.class, String.class,
                                int.class, boolean.class, String.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, TYPE), TAIL_ARGUMENTS)),
                // Canonical before PLAT-01 added joinWaitDuration.
                shape("15-argument, canonical before PLAT-01's joinWaitDuration",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS)),
                // Canonical before processingDuration.
                shape("16-argument, canonical before processingDuration",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS, List.of("(java.time.Duration) null"))),
                // Canonical before nodeCatalogKey.
                //
                // CORRECTION TO S1'S CLAIM, recorded because the mutation run disproved it. S1
                // reported that a freshly demoted arity has no in-module source caller and is
                // therefore the harness's case. It is the opposite: deleting this constructor is a
                // COMPILE error, because ExecutionEventProcessingDurationTest was written against
                // the shape that was canonical at the time and still calls it. The same was true of
                // The 16-argument shape with processingDuration.
                //
                // What the harness actually protects is arities that have AGED OUT of in-module use
                // -- PLAT-01's 15-argument shape, whose deletion compiles clean and is caught here
                // and nowhere else. A demoted arity starts compiler-protected and stops being so
                // when the last in-module caller is rewritten, which is a silent transition nobody
                // gets a warning for. That is the case for keeping every shape listed here.
                shape("17-argument, canonical before nodeCatalogKey",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class,
                                Duration.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS,
                                List.of("(java.time.Duration) null", "(java.time.Duration) null"))),
                // Canonical before deploymentId/workloadId (ADR 0021 D5).
                shape("18-argument, canonical before deploymentId/workloadId",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class,
                                Duration.class, String.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS,
                                List.of("(java.time.Duration) null", "(java.time.Duration) null",
                                        "(java.lang.String) null"))),
                // Canonical before inFlightArrivals.
                shape("20-argument, canonical before inFlightArrivals",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class,
                                Duration.class, String.class, String.class, String.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS,
                                List.of("(java.time.Duration) null", "(java.time.Duration) null",
                                        "(java.lang.String) null", "(java.lang.String) null",
                                        "(java.lang.String) null"))),
                // Canonical before publicReason.
                shape("21-argument, canonical before publicReason",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class,
                                Duration.class, String.class, String.class, String.class, int.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS,
                                List.of("(java.time.Duration) null", "(java.time.Duration) null",
                                        "(java.lang.String) null", "(java.lang.String) null",
                                        "(java.lang.String) null", "0"))),
                // Canonical with the strict publicReason classifier and without an owner diagnostic.
                shape("22-argument, canonical with publicReason",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class,
                                Duration.class, String.class, String.class, String.class, int.class, String.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS,
                                List.of("(java.time.Duration) null", "(java.time.Duration) null",
                                        "(java.lang.String) null", "(java.lang.String) null",
                                        "(java.lang.String) null", "0", "(java.lang.String) null"))),
                // Canonical before authoritative edge traversal identity.
                shape("24-argument, canonical with author diagnostics",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class,
                                Duration.class, String.class, String.class, String.class, int.class, String.class,
                                ai.ravenroot.api.application.RuntimeActivityData.TextProjection.class,
                                ai.ravenroot.api.application.RuntimeActivityData.OutputProjection.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS,
                                List.of("(java.time.Duration) null", "(java.time.Duration) null",
                                        "(java.lang.String) null", "(java.lang.String) null",
                                        "(java.lang.String) null", "0", "(java.lang.String) null",
                                        "(ai.ravenroot.api.application.RuntimeActivityData.TextProjection) null",
                                        "(ai.ravenroot.api.application.RuntimeActivityData.OutputProjection) null"))),
                // Current canonical: edge identity is absent on every event except EDGE_TRAVERSED.
                shape("25-argument, current canonical with edge identity",
                        List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                                UUID.class, UUID.class, UUID.class, UUID.class, ExecutionEventType.class,
                                String.class, int.class, boolean.class, String.class, Duration.class,
                                Duration.class, String.class, String.class, String.class, int.class, String.class,
                                ai.ravenroot.api.application.RuntimeActivityData.TextProjection.class,
                                ai.ravenroot.api.application.RuntimeActivityData.OutputProjection.class,
                                String.class),
                        concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, INVOCATION, ATTEMPT, TYPE),
                                TAIL_ARGUMENTS,
                                List.of("(java.time.Duration) null", "(java.time.Duration) null",
                                        "(java.lang.String) null", "(java.lang.String) null",
                                        "(java.lang.String) null", "0", "(java.lang.String) null",
                                        "(ai.ravenroot.api.application.RuntimeActivityData.TextProjection) null",
                                        "(ai.ravenroot.api.application.RuntimeActivityData.OutputProjection) null",
                                        "(java.lang.String) null"))));
    }

    /** A shape that has never been published. Both mechanisms must report a break for it. */
    private static BinaryCompatibility.ConstructorShape neverPublished() {
        return shape("13-argument, never published",
                List.of(long.class, Instant.class, String.class, String.class, String.class, String.class,
                        UUID.class, UUID.class, ExecutionEventType.class, String.class, int.class, boolean.class,
                        String.class),
                concat(IDENTITY_ARGUMENTS, List.of(PROCESS, TRAVERSAL, TYPE), TAIL_ARGUMENTS));
    }

    @TestFactory
    Stream<DynamicTest> everyPublishedShapeStillLinksAgainstTheCurrentClass() {
        return publishedShapes().stream().map(shape -> DynamicTest.dynamicTest(shape.description(), () ->
                assertTrue(BinaryCompatibility.linksAgainstCurrent(ExecutionEvent.class, shape),
                        "a caller compiled against the " + shape.description() + " shape no longer links: "
                                + "the descriptor " + shape.descriptor() + " is gone from the class file, so "
                                + "every downstream artifact already built against it fails with "
                                + "NoSuchMethodError at run time, not at compile time")));
    }

    @TestFactory
    Stream<DynamicTest> javapReportsADescriptorForEveryPublishedShape() {
        List<String> descriptors = BinaryCompatibility.declaredConstructorDescriptors(ExecutionEvent.class);
        return publishedShapes().stream().map(shape -> DynamicTest.dynamicTest(shape.description(), () ->
                assertTrue(descriptors.contains(shape.descriptor()),
                        "the compiled class file declares no constructor with descriptor "
                                + shape.descriptor() + " for the " + shape.description() + " shape. javap "
                                + "reported: " + descriptors)));
    }

    /**
     * The control that makes the two factories above mean something. A harness that reports success
     * for a shape that was never published would report success for anything.
     */
    @Test
    void reportsABreakForAShapeThatNeverExisted() throws Exception {
        assertFalse(BinaryCompatibility.linksAgainstCurrent(ExecutionEvent.class, neverPublished()),
                "the linkage check passed for a 13-argument shape ExecutionEvent has never published, so "
                        + "it cannot distinguish a present constructor from an absent one and every case "
                        + "above is vacuous");
    }

    @Test
    void javapNeverReportsADescriptorForAShapeThatNeverExisted() {
        assertFalse(BinaryCompatibility.declaredConstructorDescriptors(ExecutionEvent.class)
                        .contains(neverPublished().descriptor()),
                "javap reported a descriptor for a shape that was never published, so the parse is "
                        + "matching something other than constructors");
    }

    /** The parse must find constructors at all; an empty list would make every containment check pass. */
    @Test
    void javapFindsEveryConstructorTheClassDeclares() {
        List<String> descriptors = BinaryCompatibility.declaredConstructorDescriptors(ExecutionEvent.class);
        assertTrue(descriptors.size() >= publishedShapes().size(),
                "javap found " + descriptors.size() + " constructors but " + publishedShapes().size()
                        + " shapes are published; the parse is dropping declarations: " + descriptors);
    }

    private static BinaryCompatibility.ConstructorShape shape(String description, List<Class<?>> types,
                                                              List<String> arguments) {
        return new BinaryCompatibility.ConstructorShape(description, types, arguments);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        return Stream.of(parts).flatMap(List::stream).toList();
    }
}
