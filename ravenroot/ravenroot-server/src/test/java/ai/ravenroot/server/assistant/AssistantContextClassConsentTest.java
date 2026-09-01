package ai.ravenroot.server.assistant;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationDecision;
import ai.ravenroot.api.security.AuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.server.assistant.provider.AssistantProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Denying one class of context stops that context leaving.</b>
 *
 * <h2>Why every assertion here is about the composed payload rather than about the register</h2>
 * <p>Consent must be recorded before the first submission and actually read by the composer. A test
 * that asserts {@code consentedClasses} returned the set it was told to return
 * proves the register remembers; it proves nothing about whether anything reads it. So the subject of
 * every assertion below is {@code ScriptedProviderView#received()} — the {@link AssistantProvider.Request}
 * objects the composer actually handed the provider — plus the authorization actions the deployment
 * was asked for, which is where the read would have happened if it happened at all.</p>
 *
 * <h2>The canary is real catalog data, not a planted string</h2>
 * <p>{@link #deniedClassData} reads the node-type catalog through the same authorized application the
 * assistant uses and takes a behavior name out of it. Asserting on a string this deployment genuinely
 * publishes is what makes {@link #aDeniedContextClassNeverReachesTheProvider} a leak test rather than
 * a spelling test, and {@link #aGrantedContextClassDoesReachTheProvider} is the guard that keeps it
 * from passing vacuously: if the granted run did not put the canary on the wire, the denied run's
 * absence of it would mean nothing.</p>
 */
class AssistantContextClassConsentTest {

    /**
     * <b>The class the author refused does not reach the provider, in either of the two ways it
     * could.</b>
     *
     * <p>Three assertions, because there are three separate places the refusal has to hold and only
     * the third is about data that has already been read:</p>
     * <ol>
     *   <li>the refused class is not offered — no {@code ToolSpec} for it in any composed request, so a
     *       well-behaved model never asks;</li>
     *   <li>the refused class is not served — the scripted provider asks for it <em>anyway</em>, by
     *       name, exactly as a model that hallucinated a tool name would, and the read is still
     *       refused. Without this the design would rest on the model's good behaviour;</li>
     *   <li>the refused class was never read — {@code CATALOG_READ} is never even authorized, so the
     *       catalog was not fetched and then filtered out of the payload. Data that was read and
     *       dropped is a redaction; data that was never read is a denial, and only the second survives
     *       a later change to how payloads are assembled.</li>
     * </ol>
     *
     * <p><b>Mutation proof.</b> Make {@code AssistantService#send} build
     * {@code AssistantInternalContext} without the granted set and this test
     * reds on all three: the spec reappears, the catalog JSON with the canary appears in the composed
     * tool result, and {@code CATALOG_READ} is asked for.</p>
     */
    @Test
    void aDeniedContextClassNeverReachesTheProvider() {
        try (var engine = new PekkoExecutionEngine("assistant-consent-denied")) {
            var asked = ConcurrentHashMap.<AuthorizationAction>newKeySet();
            var application = authorizedApplication(engine, asked);
            RequestContext author = author("author-who-refused-the-catalog");
            String canary = deniedClassData(application, author);
            asked.clear();

            var provider = new AssistantHarness.ScriptedProviderView()
                    .callingTool("ravenroot_node_types")
                    .answering("I cannot see the catalog.");
            var service = provider.service(granting(author.subject(),
                    EnumSet.complementOf(EnumSet.of(AssistantContextClass.NODE_TYPES))));

            service.send(author, application, turn("which node types does this deployment offer?"));

            assertFalse(composedPayload(provider).contains(canary),
                    () -> "the refused class's data reached the provider: the composed payload carries "
                            + "the node behavior '" + canary + "'");
            assertFalse(offeredToolNames(provider).contains("ravenroot_node_types"),
                    () -> "a refused context class must not be offered to the model, but the composed "
                            + "request declared it: " + offeredToolNames(provider));
            assertFalse(asked.contains(AuthorizationAction.CATALOG_READ),
                    "the refused class was read and then filtered, not refused: the deployment was "
                            + "asked to authorize CATALOG_READ during the turn");
        }
    }

    /**
     * <b>The vacuity guard: granted, the same class does reach the provider.</b>
     *
     * <p>Identical to the test above in every respect except the consent set. Without it, a composer
     * that silently sent no tools at all — or a canary that never appears on the wire under any
     * circumstances — would satisfy the denial test perfectly.</p>
     */
    @Test
    void aGrantedContextClassDoesReachTheProvider() {
        try (var engine = new PekkoExecutionEngine("assistant-consent-granted")) {
            var asked = ConcurrentHashMap.<AuthorizationAction>newKeySet();
            var application = authorizedApplication(engine, asked);
            RequestContext author = author("author-who-granted-the-catalog");
            String canary = deniedClassData(application, author);
            asked.clear();

            var provider = new AssistantHarness.ScriptedProviderView()
                    .callingTool("ravenroot_node_types")
                    .answering("here is the catalog.");
            var service = provider.service(granting(author.subject(),
                    EnumSet.allOf(AssistantContextClass.class)));

            service.send(author, application, turn("which node types does this deployment offer?"));

            assertTrue(offeredToolNames(provider).contains("ravenroot_node_types"),
                    "a granted context class must be offered to the model");
            assertTrue(composedPayload(provider).contains(canary),
                    () -> "the granted class's data must actually reach the provider, or the denial "
                            + "test proves nothing: expected the node behavior '" + canary + "'");
            assertTrue(asked.contains(AuthorizationAction.CATALOG_READ),
                    "a granted read still passes the author's own authorization");
        }
    }

    /**
     * <b>Consent is per subject: one author's grant is not another's.</b>
     *
     * <p>The register here grants the catalog to one subject only. The <em>other</em> subject drives
     * the turn, and the composer must ask for that subject's grant rather than for whatever grant the
     * register happens to hold. A composer that read consent for the wrong subject — or that read "has
     * anyone consented?" — passes every other test in this class and fails this one.</p>
     */
    @Test
    void oneAuthorsConsentIsNotAnothers() {
        try (var engine = new PekkoExecutionEngine("assistant-consent-subject")) {
            var asked = ConcurrentHashMap.<AuthorizationAction>newKeySet();
            var application = authorizedApplication(engine, asked);
            RequestContext other = author("author-who-never-consented");
            String canary = deniedClassData(application, other);
            asked.clear();

            var provider = new AssistantHarness.ScriptedProviderView()
                    .callingTool("ravenroot_node_types")
                    .answering("I cannot see the catalog.");
            var service = provider.service(
                    granting("a-different-author", EnumSet.allOf(AssistantContextClass.class)));

            service.send(other, application, turn("which node types does this deployment offer?"));

            assertFalse(composedPayload(provider).contains(canary),
                    "another author's consent sent this author's deployment context to the provider");
            assertTrue(offeredToolNames(provider).isEmpty(),
                    () -> "an author who consented to nothing must be offered nothing: "
                            + offeredToolNames(provider));
            assertFalse(asked.contains(AuthorizationAction.CATALOG_READ),
                    "the catalog was read on behalf of an author who never consented to sending it");
        }
    }

    /**
     * <b>The register is read before <em>every</em> request, not once per turn.</b>
     *
     * <h2>Why this test exists separately from all the others</h2>
     * <p>Because without it the property is not falsifiable. Moving the {@code consentedClasses} call
     * out of the loop in {@code AssistantService#send} — one read per turn instead of one per request,
     * which is the obvious "optimisation" a later reader makes to avoid a database hit per iteration —
     * leaves every other test in this class and in {@code SqliteAssistantConsentStoreTest} green. The
     * behaviour would be wrong and nothing would say so. A turn spans several requests, so a
     * once-per-turn read keeps sending a class for the rest of the turn after the author has withdrawn
     * it.</p>
     *
     * <h2>How it pins the property</h2>
     * <p>The register answers <em>everything</em> the first time it is asked and <em>nothing</em>
     * afterwards, so the two requests of one turn must disagree. The count assertion is the direct
     * statement of the rule — one consent read per composed request — and the payload assertion is
     * what that count is worth: the second request offers no tools at all.</p>
     *
     * <p><b>Mutation proof.</b> Hoist {@code var granted = consentedClasses(context);} above the
     * {@code for} loop and this test reds twice: the register is asked once instead of twice, and the
     * second request still carries {@code ravenroot_node_types}. Verified by performing that exact
     * edit, not by reasoning about it.</p>
     */
    @Test
    void theRegisterIsReadBeforeEveryRequestNotOncePerTurn() {
        try (var engine = new PekkoExecutionEngine("assistant-consent-per-request")) {
            var asked = ConcurrentHashMap.<AuthorizationAction>newKeySet();
            var application = authorizedApplication(engine, asked);
            RequestContext author = author("author-who-revokes-mid-turn");

            var reads = new AtomicInteger();
            AssistantConsentStore revokedAfterTheFirstRead = (subject, provider) ->
                    reads.getAndIncrement() == 0
                            ? EnumSet.allOf(AssistantContextClass.class)
                            : Set.of();

            var provider = new AssistantHarness.ScriptedProviderView()
                    .callingTool("ravenroot_node_types")
                    .answering("the catalog is no longer available to me.");
            provider.service(revokedAfterTheFirstRead)
                    .send(author, application, turn("which node types does this deployment offer?"));

            var requests = provider.received();
            assertEquals(2, requests.size(),
                    "this turn must span two requests, or there is no 'next request' to assert about");
            assertEquals(requests.size(), reads.get(),
                    () -> "the register must be read once per composed request, not once per turn: "
                            + requests.size() + " requests but " + reads.get() + " consent reads");
            assertTrue(requests.get(0).tools().stream()
                            .anyMatch(spec -> spec.name().equals("ravenroot_node_types")),
                    "the first request must carry the class, or the revocation has nothing to remove");
            assertEquals(List.of(), requests.get(requests.size() - 1).tools(),
                    "a class withdrawn mid-turn must stop being offered on the next request of the "
                            + "same turn, not on the next turn");
        }
    }

    /**
     * <b>The join: the durable register is the thing the composer reads.</b>
     *
     * <p>Every other test in this class hands the composer a lambda, and
     * {@code SqliteAssistantConsentStoreTest} proves the file remembers. Both can be true while the
     * two are never the same object — a production store that nothing consults is the "decorative"
     * failure in its last hiding place. Here the classes are written to disk, the store is reopened,
     * and the reopened instance is what governs the wire.</p>
     *
     * <p><b>Mutation proof.</b> Have {@code AssistantService#consentedClasses} return
     * {@code EnumSet.allOf} unconditionally — the shape that makes every register decorative — and this
     * test reds on the withheld class reappearing in the offered tools.</p>
     */
    @Test
    void theDurableRegisterIsWhatTheComposerReads(@TempDir Path registerDirectory) {
        try (var engine = new PekkoExecutionEngine("assistant-consent-durable")) {
            var asked = ConcurrentHashMap.<AuthorizationAction>newKeySet();
            var application = authorizedApplication(engine, asked);
            RequestContext author = author("author-with-a-recorded-choice");

            try (var register = SqliteAssistantConsentStore.openUnder(registerDirectory)) {
                register.recordConsent(author.subject(), "anthropic",
                        EnumSet.complementOf(EnumSet.of(AssistantContextClass.EXECUTION_EVENTS)));
            }

            try (var reopened = SqliteAssistantConsentStore.openUnder(registerDirectory)) {
                var provider = new AssistantHarness.ScriptedProviderView().answering("hello");
                provider.service(reopened).send(author, application, turn("what is running?"));

                assertTrue(offeredToolNames(provider).contains("ravenroot_status"),
                        () -> "a class recorded on disk must be offered: " + offeredToolNames(provider));
                assertFalse(offeredToolNames(provider).contains("ravenroot_execution_events"),
                        () -> "the class withheld on disk reached the wire: "
                                + offeredToolNames(provider));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** A register that grants {@code classes} to exactly one subject, and nothing to anyone else. */
    private static AssistantConsentStore granting(String subject, Set<AssistantContextClass> classes) {
        return (candidate, provider) -> subject.equals(candidate) ? Set.copyOf(classes) : Set.of();
    }

    /**
     * A behavior name this deployment's catalog genuinely publishes, read under the author's own
     * authorization so the canary cannot be a string the catalog never emits.
     */
    private static String deniedClassData(AuthorizedRavenrootApplication application,
                                          RequestContext context) {
        var catalog = application.nodeTypes(context);
        assertFalse(catalog.isEmpty(),
                "this deployment publishes no node types, so there is no catalog data to leak and "
                        + "these tests would pass vacuously");
        return catalog.get(0).behavior();
    }

    /** Every tool name the composer declared to the provider, across every request in the turn. */
    private static List<String> offeredToolNames(AssistantHarness.ScriptedProviderView provider) {
        return provider.received().stream()
                .flatMap(request -> request.tools().stream())
                .map(AssistantProvider.ToolSpec::name)
                .distinct()
                .toList();
    }

    /**
     * Everything the composer put on the wire this turn, as one string to grep.
     *
     * <p>Deliberately the whole {@link AssistantProvider.Request} — system text, every message, every
     * content block and every tool result — rather than a chosen field. A leak that moved from the
     * tool result into, say, the system prompt would still be a leak, and a grep scoped to one field
     * would not see it.</p>
     */
    private static String composedPayload(AssistantHarness.ScriptedProviderView provider) {
        return provider.received().stream().map(String::valueOf).collect(Collectors.joining("\n"));
    }

    private static AssistantTurn turn(String prompt) {
        return new AssistantTurn(prompt, null, List.of());
    }

    private static RequestContext author(String subject) {
        return new RequestContext(UUID.randomUUID().toString(), subject, PrincipalType.USER,
                "urn:ravenroot:test", "tenant-consent", Set.of(Role.PLATFORM_ADMIN),
                Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * An application that allows everything and records what it was asked to allow.
     *
     * <p>Allowing everything is the point: these tests must fail because <em>consent</em> refused the
     * class, never because authorization did. The recorded set is then unambiguous — an action absent
     * from it was never attempted.</p>
     */
    private static AuthorizedRavenrootApplication authorizedApplication(
            PekkoExecutionEngine engine, Set<AuthorizationAction> asked) {
        AuthorizationService recording = (context, action, resource) -> {
            asked.add(action);
            return new AuthorizationDecision(true, "test");
        };
        return new AuthorizedRavenrootApplication(
                new DefaultRavenrootApplication(engine, new ExecutionMonitor()), recording,
                event -> { }, false);
    }
}
