package ai.ravenroot.server;

import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The examples shipped with the product are a new user's first contact with it. Someone
 * opens Ravenroot, clicks the bundled example, and either gets a generic invalid-request refusal
 * (ravenroot-minimal.graphml declared yEd-style {@code name}/{@code start}/{@code end} keys instead
 * of the server's canonical {@code kind} key) or a silent, empty success (a behavior name absent
 * from the catalog runs as a no-op pass-through). No downstream architectural work recovers
 * that first impression.
 *
 * <h2>The corpus is discovered, never hand-listed</h2>
 * {@link #discoverCorpus()} scans {@code ravenroot-ui/public/examples/*.graphml} and the server's
 * publicable test corpus at test-run time. A hardcoded file list is the exact defect
 * this test prevents from recurring: a corrected example regresses the first time someone
 * adds a sibling and the check never looks at it. The one path that cannot be discovered this way is
 * {@code ravenroot-ui/test/fixtures/new-graph-template.graphml} -- the editor's blank-workflow
 * template is not "in the examples directory" at all, which is exactly why it is easy to leave
 * uncovered, so it is added explicitly with that reasoning stated at the point it is added, not left
 * to be inferred.
 *
 * <h2>Two independent checks, because one check catches only one of the two original defects</h2>
 * <ol>
 *   <li>{@link #shippedExamplesNameOnlyCatalogBehaviors()} -- a STATIC, pre-execution check that
 *       every {@code BEHAVIOR} node's behavior name is in {@link BehaviorRegistry#standard()}'s
 *       catalog. This is the only check in this class that can catch the "silent empty success"
 *       defect: an unknown behavior name does not fail admission or execution at all, it
 *       degrades to a no-op pass-through and the run still reports {@code COMPLETED}. Relying on
 *       execution here would make this check red for the wrong document and green for the one that
 *       actually matters -- {@link #ravenrootMinimalProducesObservableOutput()}'s own Javadoc records
 *       the run that proves this empirically.</li>
 *   <li>{@link #shippedExamplesAreAdmittedForExecution()} -- each corpus member is submitted to a
 *       real, in-process server exactly as a browser would, and the submission itself (not merely
 *       {@code /inspect}) must be admitted (202). This is the only check that can catch the "generic
 *       invalid-request refusal" defect: a node missing a required property, or missing the
 *       conditional {@code recovery.repeatable} a state-changing {@code http-request} node needs, is
 *       accepted by {@code /v1/graphs/inspect} and refused only at {@code POST /v1/executions} (see
 *       the agent-cycle fixture by bisection). Neither check substitutes for the other; each is a control for a distinct one of
 *       the two defects; mutation evidence shows each check failing for its own reason and passing
 *       for the other's.</li>
 * </ol>
 *
 * <p>Whether a corpus member is additionally asserted to reach {@code COMPLETED} is derived from the
 * catalog, not hardcoded either: {@link #expectsCompletion(byte[])} accepts a member only if every
 * behavior node it uses declares nothing but {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT} capabilities.
 * {@code http-request} declares {@code network}; {@link ai.ravenroot.core.security.OutboundHttpPolicy
 * #disabled()} is this environment's default and deliberately denies every host, so a corpus member
 * using it is asserted only as far as admission -- that denial is SEC-10 working as designed, not a
 * defect this test detects. A new example is classified automatically by what it
 * actually uses, not by a second list a future editor has to remember to update alongside the first.
 *
 * <p>That automatic classification has one silent edge, guarded by
 * {@link #theAgentCycleExampleCompletesAndSendsItsReply()}: it is silent in both directions.
 * Adding a {@code network} behavior to an existing example does not fail anything, it quietly drops
 * that example out of the completion check. The filter itself is correct; the guide's agent-cycle
 * document remains self-contained, and a named test fails if it becomes excluded.
 *
 * <p>The named test closes that silence for one file. For the corpus as a whole,
 * {@link #completionFilterExcludesExactlyTheDocumentedFiles()} asserts the exact set of members
 * {@link #expectsCompletion(byte[])} currently excludes against a hand-maintained constant, so any
 * corpus member -- not just the one already pinned by name -- that starts declaring a capability
 * outside {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT} makes that assertion fail with the offending file
 * named in the message, instead of quietly shrinking the set of dynamic tests
 * {@link #selfContainedShippedExamplesReachCompletion()} generates. The filter is still not removed:
 * it stays the mechanism, while the exact-set assertion makes its effect visible.
 *
 * <p>{@link #ravenrootMinimalProducesObservableOutput()} verifies that a shipped
 * graph actually produces something: it runs {@code ravenroot-minimal.graphml} to completion and
 * asserts a non-empty result, the same shape as
 * {@code RavenrootServerTest#readsAnExecutionResultByIdAndReportsBothItsPayloadAndItsDefaultedNodes}.
 *
 * <h2>ravenroot-sample.graphml is deliberately excluded</h2>
 * {@code ravenroot-sample/src/main/resources/ravenroot-sample.graphml} names {@code uppercase-text},
 * which is not in {@link BehaviorRegistry#standard()}'s catalog either -- but
 * {@code EmbeddedSample.java} registers that behavior itself, on a fresh {@link BehaviorRegistry}
 * that is never {@code standard()}, and the graph is never submitted to a running server's HTTP API.
 * It demonstrates SDK-side custom behavior registration, a different and legitimate contract from
 * "shipped example a browser user opens." Folding it into this corpus would force rewriting a
 * correct extensibility demonstration to satisfy a check aimed at a different failure mode, so it is
 * out of scope by directory (it is not under {@code ravenroot-ui/public/examples} or
 * the public example corpus) rather than by an exclusion list.
 */
class ShippedExampleCorpusTest {

    /**
     * Capabilities a behavior may declare and still be expected to run to completion under
     * {@code BehaviorEnvironment.safeDefaults()} with no operator configuration. Anything else
     * (network, an external model/agent provider, a credential reference, a sandboxed program
     * runtime) is denied or absent by default on purpose (SEC-10 among others), so a corpus member
     * that uses it is only asserted as far as {@code /v1/executions} admitting the submission.
     */
    private static final Set<String> SAFE_UNDER_DEFAULT_ENVIRONMENT = Set.of("deterministic", "cel", "side-effect");

    /**
     * The corpus members {@link #expectsCompletion(byte[])} currently excludes from
     * {@link #selfContainedShippedExamplesReachCompletion()}, by display path. Empty today, because
     * nothing shipped declares a capability outside {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT} -- see
     * {@link #completionFilterExcludesExactlyTheDocumentedFiles()} for why this is asserted rather
     * than left implicit, and why growing the corpus with another self-contained example never
     * requires touching this set. Updating it after a real capability change requires an explicit
     * reason, the same discipline this class applies to {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT}.
     */
    private static final Set<String> EXPECTED_EXCLUDED_FROM_COMPLETION_CHECK = Set.of();

    @TestFactory
    Stream<DynamicTest> shippedExamplesNameOnlyCatalogBehaviors() {
        Set<String> catalog = BehaviorRegistry.standard().descriptors().stream()
                .map(NodeTypeDescriptor::behavior)
                .collect(Collectors.toUnmodifiableSet());
        return discoverCorpus().stream().map(path -> DynamicTest.dynamicTest(display(path), () -> {
            byte[] source = Files.readAllBytes(path);
            try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
                for (var node : manager.definition().nodes()) {
                    if (node.kind() == NodeKind.BEHAVIOR) {
                        assertTrue(catalog.contains(node.behavior()),
                                () -> display(path) + ": node '" + node.id() + "' names behavior '"
                                        + node.behavior() + "', which BehaviorRegistry.standard() does "
                                        + "not register. Registered: " + catalog);
                    }
                }
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> shippedExamplesAreAdmittedForExecution() {
        return discoverCorpus().stream().map(path -> DynamicTest.dynamicTest(display(path), () -> {
            byte[] source = Files.readAllBytes(path);
            try (var engine = new PekkoExecutionEngine("shipped-example-corpus-" + safeName(path));
                 var server = new RavenrootServer(
                         new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                         new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                         new DisabledLoopbackAuthenticator())) {
                server.start();
                var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                var response = client.send(HttpRequest.newBuilder(
                                URI.create("http://localhost:" + server.port() + "/v1/executions?payload=hello"))
                        .header("Content-Type", "application/graphml+xml")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(source)).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, response.statusCode(),
                        () -> display(path) + " was refused at submission, not merely at /inspect. A "
                                + "required property, or a conditional one such as recovery.repeatable "
                                + "on a state-changing http-request node, is likely missing: " + response.body());
            }
        }));
    }

    /**
     * Corpus members whose behaviors are all {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT} are asserted to
     * actually reach {@code COMPLETED}, not merely be admitted -- admission alone would also be true
 * of a graph degraded by silent pass-through, so this closes the gap the catalog check
     * (a static, pre-execution assertion) cannot: that a self-contained example really runs.
     */
    @TestFactory
    Stream<DynamicTest> selfContainedShippedExamplesReachCompletion() {
        return discoverCorpus().stream()
                .filter(path -> expectsCompletion(readQuietly(path)))
                .map(path -> DynamicTest.dynamicTest(display(path), () -> {
                    byte[] source = Files.readAllBytes(path);
                    try (var engine = new PekkoExecutionEngine("shipped-example-completion-" + safeName(path));
                         var server = new RavenrootServer(
                                 new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                                 new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                                 new DisabledLoopbackAuthenticator())) {
                        server.start();
                        String body = runToSettled(server, source, "hello");
                        assertEquals("COMPLETED", jsonString(body, "status"),
                                () -> display(path) + " is classified self-contained but did not complete: "
                                        + body);
                        // COMPLETED alone does not prove any behavior ran -- a
                        // TEST_PASSTHROUGH submission also settles COMPLETED with every node
                        // bypassed. This is the assertion that would catch runToSettled() silently
                        // losing its mode=run: a behavior node reported here as bypassed was never
                        // constructed at all.
                        String bypassed = jsonArray(body, "bypassedNodes");
                        for (String behaviorId : behaviorNodeIds(source)) {
                            String quotedId = "\"" + behaviorId + "\"";
                            assertFalse(bypassed.contains(quotedId),
                                    () -> display(path) + "'s behavior node '" + behaviorId
                                            + "' was bypassed, not executed -- COMPLETED alone would "
                                            + "stay green under TEST_PASSTHROUGH: " + body);
                        }
                    }
                }));
    }

    /**
     * A shipped graph must run to completion and report a non-empty result,
     * not merely a graph that is admitted.
     *
     * <h2>Mutation evidence, recorded here because it justifies why this class has two checks</h2>
     * Submitting {@code ravenroot-minimal.graphml} with its {@code template} node's behavior changed
     * to {@code uppercase-text} (absent from the catalog) still returns {@code 202} and still settles
     * {@code COMPLETED} here -- {@link #shippedExamplesNameOnlyCatalogBehaviors()} is the only check
     * that reds for that mutation. Submitting content with
     * (yEd {@code name}/{@code start}/{@code end}-boolean keys, graph id {@code compatibility}) fails
     * this method and {@link #shippedExamplesAreAdmittedForExecution()} with {@code 400
     * INVALID_REQUEST} -- the literal historical symptom -- while
     * {@link #shippedExamplesNameOnlyCatalogBehaviors()} fails earlier still, on
     * {@code GraphManager.readGraphMl} itself ("a graph must contain exactly one start/end node"),
     * because the canonical reader does not know the legacy boolean keys at all.
     */
    @Test
    void ravenrootMinimalProducesObservableOutput() throws Exception {
        Path path = repoRoot().resolve("ravenroot/ravenroot-ui/public/examples/ravenroot-minimal.graphml");
        byte[] source = Files.readAllBytes(path);
        // Deliberately absent from the template's own text ("Hello, {{payload}}! Ravenroot received
        // your request."). Asserting on "Ravenroot" passes because the template's
        // fixed text already contains that word regardless of whether {{payload}} interpolation
        // ran at all -- deleting the placeholder from the template entirely left the assertion
        // green. A token the template cannot produce on its own is the only way to assert the
        // result actually reflects the submitted input, not merely that it is non-empty.
        String distinctiveToken = "RAVENROOT-332-PROBE-6f2a9c";
        try (var engine = new PekkoExecutionEngine("shipped-example-minimal-output");
             var server = new RavenrootServer(
                     new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                     new DisabledLoopbackAuthenticator())) {
            server.start();
            String body = runToSettled(server, source, distinctiveToken);
            assertEquals("COMPLETED", jsonString(body, "status"), () -> body);
            assertTrue(body.contains("\"payload\":"), () -> "no payload at all: " + body);
            String payload = jsonString(body, "payload");
            assertFalse(payload == null || payload.isBlank(),
                    () -> "the shipped example produced an empty result: " + body);
            assertTrue(payload.contains(distinctiveToken),
                    () -> "the result does not reflect the submitted payload (expected to contain '"
                            + distinctiveToken + "'): " + body);
            // Without this, the test above stays green under TEST_PASSTHROUGH too --
            // a passthrough payload is returned unchanged, so it trivially "contains" whatever token
            // was submitted, and COMPLETED alone does not distinguish "the template behavior ran"
            // from "the template behavior was never constructed." 'greet' is this graph's only
            // BEHAVIOR node; if it shows up bypassed, nothing above actually exercised interpolation.
            assertFalse(jsonArray(body, "bypassedNodes").contains("\"greet\""),
                    () -> "'greet' was bypassed, not executed -- the payload assertions above hold "
                            + "trivially in that case, since a bypassed node never runs its behavior: "
                            + body);
        }
    }

    /**
     * The getting-started guide's own example, pinned by name because the capability filter
     * above is <em>silent</em> and this is the one document whose exclusion by it was the defect.
     *
     * <h2>Why a named test, in a class whose rule is "discovered, never hand-listed"</h2>
     * That rule is about corpus <em>membership</em>: nobody should have to remember to add a file.
     * It is not a rule against asserting a specific claim about a specific document --
     * {@link #ravenrootMinimalProducesObservableOutput()} is the same pattern. And here the named
     * form is not a preference, it is the only form that works. {@link #expectsCompletion(byte[])}
     * decides membership in {@link #selfContainedShippedExamplesReachCompletion()} from what a
     * document uses, so a regression that re-introduces a {@code network} behavior does not turn
     * that factory red: it removes this file from it, and the coverage disappears without a single
     * failing test. That is precisely how an example that could never complete shipped and stayed
     * -- it used {@code http-request} against {@code api.github.com} and {@code api.telegram.org},
     * both denied by {@link ai.ravenroot.core.security.OutboundHttpPolicy#disabled()}, which is the
     * default of the very deploy the guide describes. {@code assertTrue(expectsCompletion(...))}
     * below converts that silent exclusion into a loud one.
     *
     * <h2>Why {@code visitedNodes}, and not {@code COMPLETED} plus {@code bypassedNodes}</h2>
     * The obvious repair -- declaring {@code joinPolicy=any} on {@code compose} and leaving the
     * network calls alone -- was run here on the document with only that one property
     * added, {@code mode=run}, payload {@code hello}, against this class's own in-process server.
     * The observed result from that run was:
     * <pre>
     * {"status":"COMPLETED","degraded":false,"handledFailure":true,
     *  "visitedNodes":["classify","entry","query","normalize"],
     *  "defaultedNodes":[],"bypassedNodes":[],"handledFailureNodes":["query"]}
     * </pre>
     * Four nodes of eight; {@code compose}, {@code respond}, {@code record} and {@code finish}
     * never ran, and the response carries no {@code payload} field at all -- the reply the example
     * exists to send was never built. Nothing in
     * {@link #selfContainedShippedExamplesReachCompletion()} catches that, and neither would a
     * {@code bypassedNodes} assertion: that list is empty here, because a node never reached is not
     * a node that was bypassed. Only naming the nodes that must have run distinguishes "completed"
     * from "completed having done its job".
     */
    @Test
    void theAgentCycleExampleCompletesAndSendsItsReply() throws Exception {
        Path path = repoRoot().resolve(
                "ravenroot/ravenroot-server/src/test/resources/shipped-examples/agent-cycle.graphml");
        byte[] source = Files.readAllBytes(path);
        assertTrue(expectsCompletion(source),
                () -> display(path) + " is the example the getting-started guide is built around, and "
                        + "it just stopped being classified self-contained -- some behavior it uses now "
                        + "declares a capability outside " + SAFE_UNDER_DEFAULT_ENVIRONMENT + ". That "
                        + "does not make selfContainedShippedExamplesReachCompletion() fail, it makes it "
                        + "stop covering this file. Either keep the "
                        + "example self-contained, or delete this test knowingly.");
        // As in ravenrootMinimalProducesObservableOutput: a token the graph cannot produce on its
        // own, so the final payload proves the submitted input flowed through query's transform
        // and compose's {{payload}} interpolation rather than merely being echoed.
        //
        // If this ever fails while the pasted body visibly contains the token, look at the example's
        // template and CEL expression before looking at the runtime: jsonString() stops at the first
        // '"' it meets, so a quote inside the composed reply truncates the field it reads rather than
        // the field the server sent. That is a limit of the reader below, documented on it, and it
        // can make a failure misleading.
        String distinctiveToken = "RAVENROOT-434-PROBE-b7c1e4";
        try (var engine = new PekkoExecutionEngine("shipped-example-agent-cycle");
             var server = new RavenrootServer(
                     new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null,
                     new DisabledLoopbackAuthenticator())) {
            server.start();
            String body = runToSettled(server, source, distinctiveToken);
            assertEquals("COMPLETED", jsonString(body, "status"), () -> body);
            String visited = jsonArray(body, "visitedNodes");
            // compose builds the reply, respond emits it, record records it, finish terminates.
            // 'resume' and 'error' are deliberately absent: the decision takes the other branch
            // and nothing fails, so the graph has ten nodes and this run visits eight.
            for (String required : List.of("compose", "respond", "record", "finish")) {
                assertTrue(visited.contains("\"" + required + "\""),
                        () -> "'" + required + "' never ran, so the example completed without doing "
                                + "the work it exists to demonstrate: " + body);
            }
            assertEquals("", jsonArray(body, "bypassedNodes"),
                    () -> "nodes were traversed without their behavior being constructed, so this ran "
                            + "under TEST_PASSTHROUGH rather than mode=run: " + body);
            String payload = jsonString(body, "payload");
            assertTrue(payload != null && payload.contains(distinctiveToken),
                    () -> "the reply does not reflect the submitted payload (expected to contain '"
                            + distinctiveToken + "'), so the transform and template chain did not "
                            + "actually carry it: " + body);
        }
    }

    /**
     * {@link #expectsCompletion(byte[])} decides, silently in both directions, which corpus
     * members {@link #selfContainedShippedExamplesReachCompletion()} covers. A named test pins the
     * agent-cycle file
     * ({@link #theAgentCycleExampleCompletesAndSendsItsReply()}), which is complementary and
     * stays -- but the other three corpus members (as of writing: {@code ravenroot-minimal.graphml},
     * {@code ravenroot-programmable.graphml}, {@code new-graph-template.graphml}) had no test that
     * would notice if a future edit gave one of them a {@code network} (or otherwise unsafe)
     * capability: {@code selfContainedShippedExamplesReachCompletion()} would just stop generating a
     * dynamic test for that file, and the build would stay green with one test fewer that nobody
     * counts. Reintroducing a network
     * capability, remove nothing else, and the suite is green with a smaller corpus under completion
     * coverage.
     *
     * <h2>Why assert the exact excluded set</h2>
     * <ol>
     *   <li><b>Just print the excluded files</b> would make a new exclusion visible
     *       in build output for someone who goes looking, but nothing turns red -- CI stays green and
     *       the information is easy to miss exactly like today. Rejected: it satisfies "the filter
     *       says what it excludes" only for a reader already suspicious enough to check.</li>
     *   <li><b>Cover the excluded members some other way</b> would mean a second
     *       execution-based check for whatever a network-declaring example CAN be asserted on without
     *       a live destination. But {@link #shippedExamplesAreAdmittedForExecution()} already asserts
     *       admission for every corpus member regardless of capability, and asserting more than that
     *       for a denied-by-default host is re-testing {@code OutboundHttpPolicy#disabled()} (SEC-10
     *       working as designed), which is not the behavior under test. Rejected as the
     *       highest cost of the three for coverage this class already has.</li>
     *   <li><b>Assert the excluded set</b>: compares the members
     *       {@code expectsCompletion} actually rejects today against
     *       {@link #EXPECTED_EXCLUDED_FROM_COMPLETION_CHECK}. A corpus member that starts declaring
     *       an unsafe capability changes the actual set without anyone touching the expected one, so
     *       this fails -- and {@code assertEquals} on two sets prints the mismatch, which is the
     *       "says what it excludes" part for free: the failure message names exactly the files that
     *       moved. This is strictly stronger than a bare count:
     *       a count could go from 0 to 1 for any file interchangeably and still pass if a different
     *       file left the corpus at the same time; a set cannot.</li>
     * </ol>
     *
     * <h2>Why this does not need touching when a new, self-contained example ships</h2>
     * {@link #EXPECTED_EXCLUDED_FROM_COMPLETION_CHECK} names files {@code expectsCompletion} rejects,
     * not files the corpus contains. {@link #discoverCorpus()} gaining another
     * {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT}-only example changes nothing this test compares against
     * -- the excluded set stays exactly what it was. The set is a hand-written, hand-updated constant
     * on purpose, the same shape as {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT} a few lines above it: it
     * is meant to go stale the moment reality disagrees with it, and updating it is a deliberate act
     * with a reason, not a total silently recomputed at build time. A
     * fixed total of dynamic tests would not have this property -- it grows with every new example
     * regardless of capability, so it would need hand-updating on every unrelated corpus addition and
     * would stop meaning anything specific to this defect. Asserting the excluded *set* rather than
     * the corpus *count* is what keeps this test silent exactly when it should be.
     */
    @Test
    void completionFilterExcludesExactlyTheDocumentedFiles() {
        Set<String> actuallyExcluded = discoverCorpus().stream()
                .filter(path -> !expectsCompletion(readQuietly(path)))
                .map(ShippedExampleCorpusTest::display)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(EXPECTED_EXCLUDED_FROM_COMPLETION_CHECK, actuallyExcluded,
                () -> "selfContainedShippedExamplesReachCompletion() now silently covers a different "
                        + "set of corpus members than EXPECTED_EXCLUDED_FROM_COMPLETION_CHECK records. "
                        + "If a corpus member started declaring a capability outside "
                        + SAFE_UNDER_DEFAULT_ENVIRONMENT + " on purpose, update that constant to match "
                        + "and record the reason explicitly -- do not "
                        + "just let this test start passing again with the new set unexamined. "
                        + "If instead this file stopped parsing or names an unregistered behavior, the "
                        + "red here is secondary: repair the file -- shippedExamplesNameOnlyCatalogBehaviors "
                        + "names it -- rather than recording it here as an accepted exclusion, which would "
                        + "turn a transient defect into a permanent silent exclusion.");
    }

    /** Fails loudly if directory discovery ever silently finds nothing, e.g. from a moved directory. */
    @Test
    void corpusDiscoveryFindsTheExamplesThisTestWasWrittenToCover() {
        List<Path> corpus = discoverCorpus();
        assertTrue(corpus.size() >= 3,
                () -> "expected at least the two public/examples GraphML files plus the pinned "
                        + "new-graph-template.graphml; found only: " + corpus);
        assertTrue(corpus.stream().anyMatch(path -> path.toString().contains("public/examples")),
                () -> "directory scan found nothing under ravenroot-ui/public/examples: " + corpus);
    }

    /**
     * Runs {@code source} against {@code server}, polling {@code GET /v1/executions/{id}} until the
     * status leaves {@code RUNNING}, and returns the final response body. Shared by every test in
     * this class that needs a settled result rather than just an admission decision.
     *
     * <p>{@code mode=run} is load-bearing. This route's {@code mode} parameter defaults to
     * {@code test}, which is {@code ExecutionPolicy.TEST_PASSTHROUGH}: every node, registered
     * behavior or not, is delivered {@code NodeCommand.PASSTHROUGH} and its behavior is never
     * constructed. A submission without this parameter would settle {@code COMPLETED} and echo the
     * payload verbatim regardless of whether any behavior in the graph actually ran, which would make
     * every caller of this helper measure the bypass path while believing it measured execution.</p>
     */
    private static String runToSettled(RavenrootServer server, byte[] source, String payload) throws Exception {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var submitted = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/v1/executions?mode=run&payload=" + payload))
                .header("Content-Type", "application/graphml+xml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(source)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, submitted.statusCode(), submitted.body());
        String executionId = jsonString(submitted.body(), "executionId");

        String resultUri = "http://localhost:" + server.port() + "/v1/executions/" + executionId;
        HttpResponse<String> result = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            result = client.send(HttpRequest.newBuilder(URI.create(resultUri)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> observed = result;
            assertEquals(200, observed.statusCode(), () -> observed.body());
            if (!result.body().contains("\"status\":\"RUNNING\"")) {
                break;
            }
            Thread.sleep(500);
        }
        HttpResponse<String> settled = result;
        assertFalse(settled.body().contains("\"status\":\"RUNNING\""),
                () -> "never left RUNNING: " + settled.body());
        return settled.body();
    }

    /**
     * True if every {@code BEHAVIOR} node in {@code source} declares only
     * {@link #SAFE_UNDER_DEFAULT_ENVIRONMENT} capabilities. Parse failures classify as false: a
     * document that cannot even be read is a matter for the other checks in this class, not for this
     * one to guess about.
     */
    private static boolean expectsCompletion(byte[] source) {
        if (source == null) {
            return false;
        }
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            var registry = BehaviorRegistry.standard();
            for (var node : manager.definition().nodes()) {
                if (node.kind() != NodeKind.BEHAVIOR) {
                    continue;
                }
                Optional<NodeTypeDescriptor> descriptor = registry.descriptor(node.behavior());
                if (descriptor.isEmpty() || !SAFE_UNDER_DEFAULT_ENVIRONMENT.containsAll(descriptor.get().capabilities())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException parseFailure) {
            return false;
        }
    }

    private static byte[] readQuietly(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            return null;
        }
    }

    private static String safeName(Path path) {
        return path.getFileName().toString().replaceAll("[^a-zA-Z0-9]", "-");
    }

    private static String display(Path path) {
        return repoRoot().relativize(path).toString();
    }

    /**
     * Scans the two directories a shipped example can actually live in, plus the one path that is
     * shipped-example-shaped but structurally cannot be discovered by directory scan: the editor's
     * new-graph template snapshot, which lives in {@code test/fixtures} deliberately (see
     * {@code new-graph-template.test.js}), not in an "examples" directory a glob would find.
     */
    private static List<Path> discoverCorpus() {
        Path root = repoRoot();
        var corpus = new ArrayList<Path>();
        corpus.addAll(graphmlFilesIn(root.resolve("ravenroot/ravenroot-ui/public/examples")));
        corpus.addAll(graphmlFilesIn(
                root.resolve("ravenroot/ravenroot-server/src/test/resources/shipped-examples")));
        corpus.add(root.resolve("ravenroot/ravenroot-ui/test/fixtures/new-graph-template.graphml"));
        return List.copyOf(corpus);
    }

    private static List<Path> graphmlFilesIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Expected a directory of shipped examples at " + directory);
        }
        try (var entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".graphml"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot list " + directory, exception);
        }
    }

    /**
     * This module lives at {@code <repo>/ravenroot/ravenroot-server}; corpus members live outside
     * any single module ({@code ravenroot-ui/public}, {@code docs/}), so this test reads the SAME
     * files on disk that ship with the product rather than a copy staged into this module's
     * resources, because a copy can drift from what actually ships.
     */
    private static Path repoRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int hop = 0; hop < 6 && candidate != null; hop++) {
            if (Files.exists(candidate.resolve("AGENTS.md")) && Files.isDirectory(candidate.resolve("ravenroot"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate the repository root above " + System.getProperty("user.dir"));
    }

    private static String jsonString(String body, String field) {
        String marker = "\"" + field + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /**
     * Raw text of a JSON string-array field, e.g. {@code visitedNodes} or {@code bypassedNodes} --
     * good enough for a substring containment check, not a general JSON reader.
     */
    private static String jsonArray(String body, String field) {
        String marker = "\"" + field + "\":[";
        int start = body.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = body.indexOf(']', start);
        return body.substring(start, end);
    }

    /** The ids of {@code source}'s {@code BEHAVIOR} nodes -- the ones a real execution must construct. */
    private static Set<String> behaviorNodeIds(byte[] source) {
        try (var manager = GraphManager.readGraphMl(new ByteArrayInputStream(source))) {
            return manager.definition().nodes().stream()
                    .filter(node -> node.kind() == NodeKind.BEHAVIOR)
                    .map(node -> node.id())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
