package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.core.graph.GraphDefinition;
import ai.ravenroot.core.graph.GraphEdge;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ExecutionEvent#detail()} actually carries, asserted rather than described.
 *
 * <h2>Why this test asserts the leak instead of asserting its absence</h2>
 * <p>{@code detail} is bounded but not redacted. A redactor at construction has nothing to work
 * with: the value is flat text by then, and no lexical rule separates a payload fragment a failure
 * message quoted from the diagnostic wording around it. Such a matcher would catch a sentinel shaped
 * like {@code password=...} and miss {@code cannot parse "4111 1111 1111 1111" as a date}, while
 * making the next reader believe the channel was closed.</p>
 *
 * <p>So the test that guards this cannot be "the secret does not survive" — that assertion would be
 * false, and writing it would mean building the false assurance into the test suite. It is the
 * inverse: <strong>the secret does survive into {@code detail}, and is dropped at the boundaries
 * that egress.</strong> Any construction-time redactor must first establish a structural distinction
 * between quoted payload content and diagnostics; a lexical matcher cannot do so.</p>
 *
 * <p>The complementary halves of this proof live where the egress happens. The
 * {@code assistant-projection.test.js} test in {@code ravenroot-ui} pins that the assistant's
 * runtime-event projection drops {@code detail} before anything reaches a model provider, and
 * {@code MailExecutionEventSanitizationTest} in {@code ravenroot-extensions/ravenroot-mail} pins the
 * only redaction that does work — at the site that holds the secret and knows which substring it is.
 * That one is per-behavior and binds the mail behavior alone; neither it nor
 * {@code TelemetryBridge} provides a global boundary.</p>
 */
class ExecutionEventDetailIsNotRedactedTest {

    /** Shaped like a credential to exercise the boundary between payload content and diagnostics. */
    private static final String SECRET = "password=hunter2-swordfish-sentinel";

    private final JoinTestEngine engine = new JoinTestEngine();

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    /**
     * The channel, proven end to end through a real traversal rather than by calling the monitor
     * directly: a behavior throws, the message quotes a secret-looking value, and that value is on
     * the event any consumer of the runtime stream receives.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void theDeepestCauseMessageReachesDetailVerbatim() throws Exception {
        List<ExecutionEvent> events = runFailing(new IllegalStateException(
                "SMTP authentication rejected for " + SECRET));

        ExecutionEvent failure = events.stream()
                .filter(event -> event.type() == ExecutionEventType.NODE_FAILED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the fixture never failed a node, so this test "
                        + "proves nothing: " + events.stream().map(ExecutionEvent::type).toList()));

        assertTrue(failure.detail().contains(SECRET),
                "detail no longer carries the cause message verbatim. A construction-time matcher "
                        + "cannot tell a quoted payload from a diagnostic, and a partial matcher "
                        + "cannot provide reliable redaction; adding one "
                        + "restores exactly the false assurance this test prevents. Actual: "
                        + failure.detail());
    }

    /**
     * The nesting matters: {@code ExecutionMonitor} unwraps to the <em>deepest</em> cause, so wrapping
     * a hostile message in a benign one does not contain it. A reader who assumed the outer wrapper
     * was what surfaced would draw the wrong conclusion about what a consumer sees.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unwrapsToTheDeepestCauseSoAWrapperDoesNotContainTheLeak() throws Exception {
        List<ExecutionEvent> events = runFailing(new IllegalStateException("node failed",
                new IllegalArgumentException("upstream rejected " + SECRET)));

        assertTrue(events.stream().anyMatch(event -> event.detail().contains(SECRET)),
                "a wrapped cause must still surface: the monitor walks to the deepest cause, so the "
                        + "outer message is not the one a consumer reads");
    }

    /** The bound applies to the real path, not only to a directly constructed record. */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void boundsARunawayCauseMessageOnTheRealFailurePath() throws Exception {
        List<ExecutionEvent> events = runFailing(
                new IllegalStateException("x".repeat(ExecutionEvent.MAX_DETAIL_LENGTH * 8)));

        ExecutionEvent failure = events.stream()
                .filter(event -> event.detail().length() > 100)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no long-detail event was published at all"));

        assertEquals(ExecutionEvent.MAX_DETAIL_LENGTH, failure.detail().length(),
                "a behavior that throws a megabyte-long message must not put a megabyte on every "
                        + "consumer of the event stream");
        assertTrue(failure.detail().endsWith(ExecutionEvent.DETAIL_TRUNCATION_MARKER),
                "the cut must stay visible on the real path too, not only in the unit test");
    }

    /**
     * The durable side of the egress boundary, pinned structurally. {@link DurableExecutionEvent} is
     * the shape {@code /v1/events} serves from the journal, and it has no {@code detail} component at
     * all — the leak is confined to the in-process ring. A component added there would extend an
     * unredacted channel into durable storage, which is a different and much longer-lived decision
     * than the in-process detail contract.
     */
    @Test
    void theDurableEventShapeCarriesNoDetailComponentAtAll() {
        List<String> components = Arrays.stream(DurableExecutionEvent.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertFalse(components.contains("detail"),
                "DurableExecutionEvent gained a detail component. That puts an unredacted, "
                        + "payload-bearing string into the durable journal and onto every consumer that "
                        + "replays it, which the detail contract does not authorize: " + components);
    }

    /** Runs a one-node graph whose behavior throws {@code failure}, and returns what was published. */
    private List<ExecutionEvent> runFailing(RuntimeException failure) throws Exception {
        var monitor = new ExecutionMonitor();
        var registry = new BehaviorRegistry().register("exploding",
                message -> CompletableFuture.failedFuture(failure));

        try (var runner = new GraphRunner(GraphManager.from(graph()), engine, registry, monitor)) {
            try {
                runner.execute(TestIdentities.TENANT_A, "payload").toCompletableFuture()
                        .get(20, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expected) {
                // The traversal is supposed to fail; the events it produced on the way are the subject.
            }
        }
        return monitor.eventsAfter(0);
    }

    private static GraphDefinition graph() {
        return new GraphDefinition(
                List.of(GraphNode.start("start"), GraphNode.behavior("probe", "exploding"),
                        GraphNode.error("error"), GraphNode.end("end")),
                List.of(GraphEdge.to("start", "probe"), GraphEdge.to("probe", "end")));
    }
}
