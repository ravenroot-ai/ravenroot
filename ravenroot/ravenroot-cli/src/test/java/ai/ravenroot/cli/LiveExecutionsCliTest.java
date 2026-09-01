package ai.ravenroot.cli;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Embedded-only. {@link CliBackendContract#liveDoesNotReportATerminalExecution} proves the shape
 * of {@code live()} on both transports but, by construction (its fixture has no {@code BEHAVIOR} node),
 * cannot prove the key property: that a <b>stalled</b> execution -- one still
 * genuinely inside a node -- appears in {@code ravenroot live}'s output. That needs a custom
 * {@code BehaviorRegistry} the shared contract's generic {@code @BeforeEach} does not expose a seam
 * for, so it lives here instead, driven entirely through {@link RavenrootCli#run(String...)} -- the
 * same text interface an operator types -- rather than through {@link CliBackend} directly.
 *
 * <p>Modelled on {@code LiveExecutionsHttpTest}, one layer up: a latch the hanging node itself
 * counts down proves the execution is genuinely mid-node when {@code live} reads it, never a timing
 * assumption. The one thing this class adds beyond that HTTP-level proof is a CLI-specific property:
 * the identifier {@code cancel} is given here comes <em>only</em> from parsing
 * {@code live}'s output -- {@code run}'s own printed {@code traversal-id=} line is deliberately never
 * read -- so a green run is itself the evidence that an operator does not have to have recorded
 * anything in advance.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LiveExecutionsCliTest {

    @TempDir
    Path workingDirectory;

    private static final Pattern TRAVERSAL_ID_LINE = Pattern.compile("traversal-id=(\\S+)");

    /** Same bounded-sleep shape as {@code LiveExecutionsHttpTest#hangingBehaviors}: released on its own
     * well inside teardown's shutdown bound, never dependent on interruption to unblock. */
    private static BehaviorRegistry hangingBehaviors(CountDownLatch reached) {
        return BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults())
                .register("hang", message -> {
                    reached.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return CompletableFuture.completedFuture(NodeResult.continueWith(message.payload()));
                });
    }

    private static final String HANG_GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="live-executions-cli-test" edgedefault="directed">
                <node id="start"><data key="kind">START</data></node>
                <node id="hang"><data key="kind">BEHAVIOR</data><data key="behavior">hang</data></node>
                <node id="end"><data key="kind">END</data></node>
                <edge id="e1" source="start" target="hang"><data key="outcome">continue</data></edge>
                <edge id="e2" source="hang" target="end"><data key="outcome">continue</data></edge>
              </graph>
            </graphml>
            """;

    /**
     * Mutation proof in both directions, using changes that compile and exercise the assertions below.
     *
     * <p>First direction: replace {@code EmbeddedBackend#live}'s body with a hardcoded
     * {@code return List.of();}. {@code CliBackend#live} is abstract -- unlike
     * {@code RavenrootApplication#liveExecutions}, which has an interface default, this seam has none,
     * so "fall back to a default" is not available here; hardcoding the empty list is the executable
     * equivalent, and it still satisfies the abstract method so the build stays green. Run that way,
     * the first assertion below reds with exactly the message it prints: a listing that names nothing
     * while the hang node is demonstrably still running.</p>
     *
     * <p>Second direction: in {@code DefaultRavenrootApplication#cancelTraversal}, change
     * {@code ActiveExecution active = activeExecutions.remove(traversalId);} to a bare
     * {@code activeExecutions.get(traversalId)} -- i.e. stop removing the entry on cancel. The method
     * still returns {@code true} (CANCELLED still prints; that removal is not what decides the
     * outcome), but the traversal stays in {@code activeExecutions}, so the final assertion below reds:
     * {@code assertFalse}, "cancelled traversal ... is still reported live", expected {@code false} but
     * was {@code true}. This is deliberately a narrower claim than "cancel stops the runtime": this
     * fixture is one hop, not a loop, so it says nothing about hop rejection (that is
     * {@code RunawayLoopCancellationTest}'s claim, not this test's) -- only that the listing and the
     * outcome the CLI printed cannot silently disagree with each other.</p>
     */
    @Test
    void aStalledExecutionIsListedByLiveAndCancellableUsingOnlyTheIdentifierLiveGave() throws Exception {
        var hangReached = new CountDownLatch(1);
        Path graphFile = workingDirectory.resolve("hang.graphml");
        Files.writeString(graphFile, HANG_GRAPH, StandardCharsets.UTF_8);

        try (var engine = new PekkoExecutionEngine("live-executions-cli-test-" + UUID.randomUUID())) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    hangingBehaviors(hangReached));
            var authorized = new AuthorizedRavenrootApplication(application,
                    new DefaultAuthorizationService(event -> { }), event -> { }, true);
            var context = new RequestContext("live-cli-test-request", "live-cli-test-caller",
                    PrincipalType.USER, "urn:ravenroot:live-cli-test", "local", Set.of(Role.PLATFORM_ADMIN),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));

            // 'run's own output is deliberately never read: this test's entire point is that an
            // operator who kept nothing from it can still reach the traversal through 'live'.
            var runOutput = new ByteArrayOutputStream();
            assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(runOutput), System.err)
                    .run("run", graphFile.toString(), "payload"));
            assertTrue(hangReached.await(5, TimeUnit.SECONDS), "the hang node never started");

            // The traversal is genuinely still inside its node right
            // now (the latch fired, and the node sleeps for seconds yet), so 'live' must still name it.
            var liveOutput = new ByteArrayOutputStream();
            assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(liveOutput), System.err)
                    .run("live"));
            String liveListing = liveOutput.toString(StandardCharsets.UTF_8);
            Matcher matcher = TRAVERSAL_ID_LINE.matcher(liveListing);
            if (!matcher.find()) {
                fail("no traversal-id= line in 'live' output while the hang node is demonstrably still "
                        + "running: " + liveListing);
            }
            String traversalId = matcher.group(1);

            // Cancel using exactly that identifier -- never the one 'run' printed above.
            var cancelOutput = new ByteArrayOutputStream();
            assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(cancelOutput), System.err)
                    .run("cancel", traversalId));
            String cancelPrinted = cancelOutput.toString(StandardCharsets.UTF_8);
            assertTrue(cancelPrinted.contains("outcome=CANCELLED"), cancelPrinted);
            assertTrue(cancelPrinted.contains("traversal-id=" + traversalId), cancelPrinted);
            Matcher noteMatcher = Pattern.compile("note=(.*)").matcher(cancelPrinted);
            assertTrue(noteMatcher.find() && !noteMatcher.group(1).isBlank(),
                    "the caveat note must not be blank: " + cancelPrinted);

            // The listing and cancel read the same bookkeeping: once cancelled, 'live' must stop
            // naming it -- immediately, not eventually, since removal from the map is synchronous with
            // the cancel call returning.
            var afterCancel = new ByteArrayOutputStream();
            assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(afterCancel), System.err)
                    .run("live"));
            assertFalse(afterCancel.toString(StandardCharsets.UTF_8).contains("traversal-id=" + traversalId),
                    () -> "cancelled traversal " + traversalId + " is still reported live: "
                            + afterCancel.toString(StandardCharsets.UTF_8));
        }
    }
}
