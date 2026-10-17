package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GitlabAuthoringRepositoryTest {
    private static final GraphAuthoringRepository.Actor ALICE = new GraphAuthoringRepository.Actor("tenant-a", "alice");
    private static final GraphAuthoringRepository.Actor BOB = new GraphAuthoringRepository.Actor("tenant-a", "bob");
    private static final GraphAuthoringRepository.Revision EMPTY = new GraphAuthoringRepository.Revision("absent", "absent", "absent");
    private static final String DRAFT = "draft/customers-a";

    @Test void fullLifecycleIncludesImmutableHistoryDiffRestoreDeleteDiscardAndMergeRequest() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            assertTrue(repository.list(ALICE, "").toCompletableFuture().join().items().isEmpty());
            var first = save(repository, "orders.graphml", "one", EMPTY, "first-save-key-000001");
            assertEquals(1, first.summary().releaseVersion());
            var second = save(repository, "orders.graphml", "two", first.summary().revision(), "second-save-key-00001");
            var history = repository.history(ALICE, "orders.graphml", "").toCompletableFuture().join();
            assertEquals(2, history.items().size());
            assertFalse(repository.diff(ALICE, "orders.graphml", commit(first), commit(second))
                    .toCompletableFuture().join().unifiedDiff().isEmpty());
            var restored = repository.restore(ALICE, mutation(first.summary().documentId(), commit(first),
                    second.summary().revision(), "restore-key-000000001")).toCompletableFuture().join();
            assertTrue(new String(restored.graphMl(), StandardCharsets.UTF_8).contains("one"));
            var mr = repository.proposeRelease(ALICE, mutation("orders.graphml", "", restored.summary().revision(),
                    "propose-key-000000001")).toCompletableFuture().join();
            assertFalse(mr.reused());
            assertTrue(repository.proposeRelease(ALICE, mutation("orders.graphml", "", restored.summary().revision(),
                    "propose-key-000000002")).toCompletableFuture().join().reused());
            api.merge();
            var released = repository.open(ALICE, "orders.graphml", true).toCompletableFuture().join();
            var edit = save(repository, "orders.graphml", "three", released.summary().revision(), "third-save-key-000001");
            var discarded = repository.discardDraft(ALICE, mutation("orders.graphml", "", edit.summary().revision(),
                    "discard-key-000000001")).toCompletableFuture().join();
            assertArrayEquals(released.graphMl(), discarded.graphMl());
            var deletion = mutation("orders.graphml", "", discarded.summary().revision(), "delete-key-0000000001");
            repository.deleteDraft(ALICE, deletion).toCompletableFuture().join();
            repository.deleteDraft(ALICE, deletion).toCompletableFuture().join();
            assertTrue(repository.list(ALICE, "").toCompletableFuture().join().items().getFirst().draftDeleted());
            assertEquals(1, api.mrs.size());
            assertTrue(api.rawProjectObserved);
        }
    }

    @Test void lostMutationAndMergeRequestResponsesRecoverAcrossFreshInstancesWithoutDuplicates() {
        var api = new Gitlab();
        var request = new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("one"), EMPTY, "uncertain-key-0000001");
        GraphAuthoringRepository.Document saved;
        try (var first = repository(api)) {
            api.loseCommit = true;
            saved = first.save(ALICE, request).toCompletableFuture().join();
        }
        try (var restarted = repository(api)) {
            assertArrayEquals(saved.graphMl(), restarted.save(ALICE, request).toCompletableFuture().join().graphMl());
            assertEquals(1, api.writes);
            api.loseMr = true;
            assertTrue(restarted.proposeRelease(ALICE, mutation("orders.graphml", "", saved.summary().revision(),
                    "uncertain-mr-key-0001")).toCompletableFuture().join().reused());
            assertEquals(1, api.mrs.size());
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> restarted.save(ALICE,
                    new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("different"), EMPTY,
                            "uncertain-key-0000001")).toCompletableFuture().join());
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> restarted.save(BOB, request).toCompletableFuture().join());
        }
    }

    @Test void unknownTenantCannotReadOrMutateAndAuthenticationFailuresAreClassified() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            assertFailure(GraphAuthoringException.Failure.UNAUTHORIZED, () -> repository.list(
                    new GraphAuthoringRepository.Actor("unmapped", "alice"), "").toCompletableFuture().join());
            api.unauthorized = true;
            assertFailure(GraphAuthoringException.Failure.UNAUTHORIZED, () -> repository.list(ALICE, "").toCompletableFuture().join());
            assertEquals(0, api.writes);
        }
    }

    @Test void staleDraftAndPublicationCannotOverwriteSource() {
        var api = new Gitlab();
        var publication = new java.util.concurrent.atomic.AtomicReference<>("absent");
        try (var repository = repository(api, (t,g,v,s,b) -> publication.get())) {
            var first = save(repository, "orders.graphml", "one", EMPTY, "stale-first-key-000001");
            api.merge();
            var released = repository.open(ALICE, "orders.graphml", true).toCompletableFuture().join();
            publication.set("published");
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> save(repository, "orders.graphml", "stale",
                    released.summary().revision(), "stale-second-key-00001"));
            assertEquals(1, api.writes);
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> save(repository, "orders.graphml", "stale",
                    first.summary().revision(), "stale-third-key-000001"));
        }
    }

    @Test void publicationChangeAfterAtomicCommitIsConditionallyCompensated() {
        var api = new Gitlab();
        var publication = new java.util.concurrent.atomic.AtomicReference<>("absent");
        try (var repository = repository(api, (t,g,v,s,b) -> publication.get())) {
            var first = save(repository, "orders.graphml", "one", EMPTY, "comp-first-key-000001");
            api.merge();
            var observed = repository.open(ALICE, "orders.graphml", false).toCompletableFuture().join();
            api.afterCommit = () -> publication.set("published");
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> save(repository, "orders.graphml", "bad",
                    observed.summary().revision(), "comp-second-key-00001"));
            assertArrayEquals(first.graphMl(), repository.open(ALICE, "orders.graphml", false).toCompletableFuture().join().graphMl());
        }
    }

    @Test void externalParentChangeIsCompensatedWithoutDiscardingOtherFiles() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            var first = save(repository, "orders.graphml", "one", EMPTY, "parent-first-key-00001");
            api.beforeCommit = () -> api.external("external.txt", "keep".getBytes(StandardCharsets.UTF_8));
            api.loseCommit = true;
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> save(repository, "orders.graphml", "bad",
                    first.summary().revision(), "parent-second-key-0001"));
            assertArrayEquals(first.graphMl(), repository.open(ALICE, "orders.graphml", false).toCompletableFuture().join().graphMl());
            assertArrayEquals("keep".getBytes(StandardCharsets.UTF_8), api.commits.get(api.refs.get(DRAFT)).files.get("external.txt"));
        }
    }

    @Test void concurrentFirstMarkerCreationAndCrossFileChangesAreAtomicAcrossInstances() throws Exception {
        var api = new Gitlab();
        // Ensure the lazy branch itself is outside the race being tested.
        api.refs.put(DRAFT, api.refs.get("main"));
        api.race = new java.util.concurrent.CyclicBarrier(2);
        try (var left = repository(api); var right = repository(api)) {
            var one = left.save(ALICE, new GraphAuthoringRepository.SaveRequest("one.graphml", graph("one"), EMPTY, "concurrent-key-000001"));
            var two = right.save(ALICE, new GraphAuthoringRepository.SaveRequest("two.graphml", graph("two"), EMPTY, "concurrent-key-000002"));
            int succeeded = 0;
            for (var result : List.of(one, two)) {
                try { result.toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS); succeeded++; }
                catch (java.util.concurrent.ExecutionException failure) { assertInstanceOf(GraphAuthoringException.class, failure.getCause()); }
            }
            assertEquals(1, succeeded);
            assertEquals(1, api.writes);
            api.race = new java.util.concurrent.CyclicBarrier(2);
            var three = left.save(ALICE, new GraphAuthoringRepository.SaveRequest("three.graphml", graph("three"), EMPTY, "concurrent-key-000003"));
            var four = right.save(ALICE, new GraphAuthoringRepository.SaveRequest("four.graphml", graph("four"), EMPTY, "concurrent-key-000004"));
            succeeded = 0;
            for (var result : List.of(three, four)) {
                try { result.toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS); succeeded++; }
                catch (java.util.concurrent.ExecutionException failure) { assertInstanceOf(GraphAuthoringException.class, failure.getCause()); }
            }
            assertEquals(1, succeeded);
            assertEquals(2, api.writes);
        }
    }

    @Test void concurrentMergeRequestCreationReliesOnProviderUniquenessAndReconciles() throws Exception {
        var api = new Gitlab();
        try (var left = repository(api); var right = repository(api)) {
            var first = save(left, "orders.graphml", "one", EMPTY, "mr-first-key-00000001");
            api.mrRace = new java.util.concurrent.CyclicBarrier(2);
            var request = mutation("orders.graphml", "", first.summary().revision(), "mr-race-key-000000001");
            var one = left.proposeRelease(ALICE, request);
            var two = right.proposeRelease(ALICE, request);
            assertEquals(one.toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS).url(),
                    two.toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS).url());
            assertEquals(1, api.mrs.size());
        }
    }

    @Test void symlinksAndInvalidHistoryRevisionsAreRejected() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            var first = save(repository, "orders.graphml", "one", EMPTY, "symlink-key-000000001");
            api.symlink = true;
            assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT, () -> repository.open(ALICE,
                    "orders.graphml", false).toCompletableFuture().join());
            assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT, () -> repository.diff(ALICE,
                    "../secret.graphml", commit(first), commit(first)).toCompletableFuture().join());
        }
    }

    @Test void moreThan1024PriorOperationsDoNotStopAuthoringOrRequireScanningReplayHistory() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            var first = save(repository, "orders.graphml", "one", EMPTY, "capacity-first-key-0001");
            synchronized (api) {
                String head = api.refs.get(DRAFT); var previous = api.commits.get(head);
                var files = new HashMap<>(previous.files); var modified = new HashMap<>(previous.modified);
                for (int i = 0; i < 2200; i++) {
                    String path = "graphs/customers/a/.ravenroot-authoring/requests/" + "%064x".formatted(i) + ".json";
                    files.put(path, "{}".getBytes(StandardCharsets.UTF_8)); modified.put(path, head);
                }
                api.commits.put(head, new Gitlab.Commit(previous.parent, previous.message, Map.copyOf(files), Map.copyOf(modified)));
            }
            var next = save(repository, "orders.graphml", "two", first.summary().revision(), "capacity-second-key-001");
            assertTrue(new String(next.graphMl(), StandardCharsets.UTF_8).contains("two"));
            var request = new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("two"), first.summary().revision(), "capacity-second-key-001");
            try (var restarted = repository(api)) {
                assertArrayEquals(next.graphMl(), restarted.save(ALICE, request).toCompletableFuture().join().graphMl());
            }
            assertEquals(2, api.writes);
        }
    }

    @Test void tenantsShareNeitherBranchesNorRequestIdentities() {
        var api = new Gitlab();
        var other = new GraphAuthoringRepository.Actor("tenant-b", "alice");
        var request = new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("one"), EMPTY, "tenant-shared-key-00001");
        try (var repository = repository(api)) {
            var first = repository.save(ALICE, request).toCompletableFuture().join();
            var second = repository.save(other, request).toCompletableFuture().join();
            assertNotEquals(first.summary().graphId(), second.summary().graphId());
            assertEquals(1, repository.list(ALICE, "").toCompletableFuture().join().items().size());
            assertEquals(1, repository.list(other, "").toCompletableFuture().join().items().size());
            assertEquals(2, api.writes);
        }
    }

    @Test void providerErrorsDoNotExposeResponseBodiesOrLowerLayerSecretMessages() {
        var api = new Gitlab(); api.failTransport = true;
        try (var repository = repository(api)) {
            Throwable thrown = assertThrows(Throwable.class, () -> repository.list(ALICE, "").toCompletableFuture().join());
            var output = new java.io.StringWriter(); thrown.printStackTrace(new java.io.PrintWriter(output));
            assertFalse(output.toString().contains("server-secret"));
            assertFalse(output.toString().contains("do not disclose"));
        }
    }

    @Test void duplicateIdentityCreatedBetweenPreflightAndParentCaptureCannotSlipThroughAbsentRevision() {
        var api = new Gitlab(); api.refs.put(DRAFT, api.refs.get("main"));
        api.external("other-tenant/keep.txt", "keep".getBytes(StandardCharsets.UTF_8));
        byte[] graph = GraphReleaseMetadata.assign(graph("shared"), "shared-graph", 1).graphMl();
        try (var first = repository(api); var second = repository(api)) {
            api.beforeParentCapture = () -> first.save(ALICE, new GraphAuthoringRepository.SaveRequest(
                    "first.graphml", graph, EMPTY, "identity-race-key-00001")).toCompletableFuture().join();
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> second.save(ALICE,
                    new GraphAuthoringRepository.SaveRequest("second.graphml", graph, EMPTY, "identity-race-key-00002"))
                    .toCompletableFuture().join());
            assertEquals(1, api.writes);
            assertEquals(List.of("first.graphml"), second.list(ALICE, "").toCompletableFuture().join()
                    .items().stream().map(GraphAuthoringRepository.DocumentSummary::documentId).toList());
        }
    }

    @Test void listingAndHistoryExposeBoundedContinuationPages() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            var first = save(repository, "one.graphml", "one", EMPTY, "pages-key-00000000001");
            save(repository, "two.graphml", "two", EMPTY, "pages-key-00000000002");
            save(repository, "three.graphml", "three", EMPTY, "pages-key-00000000003");
            var page = repository.list(ALICE, "").toCompletableFuture().join();
            assertEquals(2, page.items().size()); assertEquals("1", page.nextCursor());
            var next = repository.list(ALICE, page.nextCursor()).toCompletableFuture().join();
            assertEquals(1, next.items().size()); assertEquals("", next.nextCursor());
            first = repository.open(ALICE, "one.graphml", false).toCompletableFuture().join();
            var second = save(repository, "one.graphml", "edited", first.summary().revision(), "pages-key-00000000004");
            save(repository, "one.graphml", "third", second.summary().revision(), "pages-key-00000000005");
            var history = repository.history(ALICE, "one.graphml", "").toCompletableFuture().join();
            assertEquals(2, history.items().size()); assertEquals("1", history.nextCursor());
            var older = repository.history(ALICE, "one.graphml", history.nextCursor()).toCompletableFuture().join();
            assertEquals(1, older.items().size()); assertEquals("", older.nextCursor());
        }
    }

    @Test void oversizedMalformedAndUnboundedProviderResponsesFailExplicitly() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            api.oversizedResponse = true;
            assertFailure(GraphAuthoringException.Failure.LIMIT_EXCEEDED, () -> repository.list(ALICE, "").toCompletableFuture().join());
            api.oversizedResponse = false; api.malformedResponse = true;
            assertFailure(GraphAuthoringException.Failure.UNAVAILABLE, () -> repository.list(ALICE, "").toCompletableFuture().join());
            api.malformedResponse = false; api.infiniteTree = true;
            assertFailure(GraphAuthoringException.Failure.LIMIT_EXCEEDED, () -> repository.list(ALICE, "").toCompletableFuture().join());
            assertEquals(20, api.treeRequests);
            assertEquals(0, api.writes);
        }
    }

    @Test void retriesConsumeRemainingOutboundBudgetBeforeAttempt4097() throws Exception {
        var api = new Gitlab(); api.retryGetResponses = 99;
        var environment = environment(); environment.put("RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", "3");
        try (var repository = new GitlabAuthoringRepository(GraphAuthoringConfiguration.fromEnvironment(environment),
                ignored -> Optional.of(new SecretValue("server-secret".toCharArray())), (t,g,v,s,b) -> "absent",
                ignored -> { }, api, Clock.systemUTC())) {
            var field = GitlabAuthoringRepository.class.getDeclaredField("requestCount");
            field.setAccessible(true);
            @SuppressWarnings("unchecked") var counter = (ThreadLocal<Integer>) field.get(repository);
            api.beforeSend = () -> {
                // Model 4094 earlier successful sends in this operation. The transport is now
                // receiving attempt 4095; retain the production 4096 ceiling without an operator seam.
                assertEquals(1, counter.get());
                counter.set(4095);
            };
            assertFailure(GraphAuthoringException.Failure.LIMIT_EXCEEDED,
                    () -> repository.list(ALICE, "").toCompletableFuture().join());
            assertEquals(2, api.totalRequests); // attempts 4095 and 4096; 4097 never reaches transport
            assertEquals(0, api.postRequests);
            // A new operation gets its own budget; the previous exhausted counter must be removed.
            api.retryGetResponses = 0;
            assertTrue(repository.list(ALICE, "").toCompletableFuture().join().items().isEmpty());
        }
    }

    @Test void configuredGetRetryLimitDoesNotRetryPostMutations() {
        for (int limit : List.of(0, 1, 3)) {
            var api = new Gitlab(); api.retryGetResponses = 99;
            var environment = environment();
            environment.put("RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", Integer.toString(limit));
            try (var repository = new GitlabAuthoringRepository(GraphAuthoringConfiguration.fromEnvironment(environment),
                    ignored -> Optional.of(new SecretValue("server-secret".toCharArray())), (t,g,v,s,b) -> "absent",
                    ignored -> { }, api, Clock.systemUTC())) {
                assertFailure(GraphAuthoringException.Failure.UNAVAILABLE,
                        () -> repository.list(ALICE, "").toCompletableFuture().join());
                assertEquals(limit + 1, api.totalRequests);
                api.retryGetResponses = 0; api.retryPostResponses = true;
                assertFailure(GraphAuthoringException.Failure.UNAVAILABLE,
                        () -> save(repository, "orders.graphml", "one", EMPTY, "post-retry-key-000001"));
                assertEquals(1, api.postRequests);
                assertEquals(0, api.writes);
            }
        }
    }

    @Test void operationDeadlineBoundsTheTotalRequestChain() {
        var api = new Gitlab(); api.slowResponse = true;
        var environment = environment(); environment.put("RAVENROOT_GRAPH_AUTHORING_REQUEST_TIMEOUT_MILLIS", "5");
        environment.put("RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", "0");
        try (var repository = new GitlabAuthoringRepository(GraphAuthoringConfiguration.fromEnvironment(environment),
                ignored -> Optional.of(new SecretValue("server-secret".toCharArray())), (t,g,v,s,b) -> "absent",
                ignored -> { }, api, Clock.systemUTC())) {
            assertFailure(GraphAuthoringException.Failure.UNAVAILABLE, () -> repository.list(ALICE, "").toCompletableFuture().join());
            assertTrue(api.totalRequests <= 1);
            assertEquals(0, api.writes);
        }
    }

    @Test void releaseAdvancingAfterWriteIsRejectedEvenWhenTheDocumentWasAbsentFromRelease() {
        var api = new Gitlab();
        try (var repository = repository(api)) {
            var first = save(repository, "orders.graphml", "one", EMPTY, "release-race-key-00001");
            api.afterCommit = () -> {
                String parent = api.refs.get("main"); var previous = api.commits.get(parent);
                String id = Gitlab.sha(++api.sequence);
                api.commits.put(id, new Gitlab.Commit(parent, "External reviewed release", previous.files, previous.modified));
                api.refs.put("main", id);
            };
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> save(repository, "orders.graphml", "bad",
                    first.summary().revision(), "release-race-key-00002"));
            assertArrayEquals(first.graphMl(), repository.open(ALICE, "orders.graphml", false).toCompletableFuture().join().graphMl());
        }
    }

    private static String commit(GraphAuthoringRepository.Document doc) { return doc.summary().revision().draft().split(":")[0]; }
    private static GraphAuthoringRepository.MutationRequest mutation(String id, String source, GraphAuthoringRepository.Revision revision, String key) {
        return new GraphAuthoringRepository.MutationRequest(id, source, revision, key);
    }
    private static GraphAuthoringRepository.Document save(GitlabAuthoringRepository repository, String id, String label,
            GraphAuthoringRepository.Revision revision, String key) {
        return repository.save(ALICE, new GraphAuthoringRepository.SaveRequest(id, graph(label), revision, key)).toCompletableFuture().join();
    }
    private static GitlabAuthoringRepository repository(Gitlab api) { return repository(api, (t,g,v,s,b) -> "absent"); }
    private static GitlabAuthoringRepository repository(Gitlab api, PublicationEvidence publications) {
        return new GitlabAuthoringRepository(configuration(), ignored -> Optional.of(new SecretValue("server-secret".toCharArray())),
                publications, ignored -> { }, api, Clock.systemUTC());
    }
    static GraphAuthoringConfiguration configuration() { return GraphAuthoringConfiguration.fromEnvironment(environment()); }
    private static Map<String, String> environment() {
        return new HashMap<>(Map.of(
                "RAVENROOT_GRAPH_AUTHORING_MODE", "git", "RAVENROOT_GRAPH_AUTHORING_PROVIDER", "gitlab",
                "RAVENROOT_GRAPH_AUTHORING_GITLAB_PROJECT", "platform/nested/graphs",
                "RAVENROOT_GRAPH_AUTHORING_GITLAB_API_BASE", "https://gitlab.example.test/api/v4",
                "RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "PAT", "RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE", "git-authoring",
                "RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=customers/a,tenant-b=customers/b",
                "RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://artifacts.example.test/releases/",
                "RAVENROOT_GRAPH_AUTHORING_PAGE_SIZE", "2"));
    }
    private static byte[] graph(String label) {
        return ("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"><key id=\"label\" for=\"graph\" attr.name=\"label\" attr.type=\"string\"/>"
                + "<graph id=\"G\" edgedefault=\"directed\"><data key=\"label\">" + label + "</data></graph></graphml>").getBytes(StandardCharsets.UTF_8);
    }
    private static void assertFailure(GraphAuthoringException.Failure expected, Runnable action) {
        Throwable failure = assertThrows(Throwable.class, action::run);
        while (failure.getCause() != null && !(failure instanceof GraphAuthoringException)) failure = failure.getCause();
        assertInstanceOf(GraphAuthoringException.class, failure);
        assertEquals(expected, ((GraphAuthoringException) failure).failure());
    }

    private static final class Gitlab extends HttpClient {
        private static final PayloadLimits JSON = new PayloadLimits(16 * 1024 * 1024, 32, 10000, 100000, 8 * 1024 * 1024, 1024);
        final Map<String, String> refs = new HashMap<>();
        final Map<String, Commit> commits = new LinkedHashMap<>();
        final Map<String, byte[]> blobs = new HashMap<>();
        final List<Map<String, Object>> mrs = new ArrayList<>();
        int sequence = 10, writes;
        volatile boolean loseCommit, loseMr, unauthorized, rawProjectObserved, symlink, failTransport;
        volatile boolean oversizedResponse, malformedResponse, infiniteTree, slowResponse;
        volatile int treeRequests, totalRequests, postRequests, retryGetResponses;
        volatile boolean retryPostResponses;
        volatile Runnable beforeSend;
        volatile Runnable beforeCommit, afterCommit, beforeParentCapture;
        volatile int releaseRootReads;
        volatile java.util.concurrent.CyclicBarrier race, mrRace;
        Gitlab() { refs.put("main", sha(1)); commits.put(sha(1), new Commit("", "root", Map.of(), Map.of())); }
        synchronized void merge() { refs.put("main", refs.get(DRAFT)); }
        synchronized void external(String path, byte[] bytes) {
            String parent = refs.get(DRAFT); var previous = commits.get(parent);
            var files = new HashMap<>(previous.files); files.put(path, bytes);
            String id = sha(++sequence); var modified = new HashMap<>(previous.modified); modified.put(path, id);
            commits.put(id, new Commit(parent, "external", Map.copyOf(files), Map.copyOf(modified))); refs.put(DRAFT, id);
        }
        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            assertEquals("server-secret", request.headers().firstValue("PRIVATE-TOKEN").orElseThrow());
            totalRequests++;
            if (beforeSend != null) { var action = beforeSend; beforeSend = null; action.run(); }
            if (request.method().equals("POST")) postRequests++;
            if (request.method().equals("GET") && retryGetResponses-- > 0
                    || request.method().equals("POST") && retryPostResponses) {
                return (HttpResponse<T>) new FakeResponse(request, 503, new byte[0]);
            }
            if (failTransport) throw new IOException("server-secret provider failure");
            if (slowResponse) {
                try { Thread.sleep(20); } catch (InterruptedException interrupted) { throw new IOException(interrupted); }
            }
            if (oversizedResponse) return (HttpResponse<T>) new FakeResponse(request, 200, new byte[16 * 1024 * 1024]);
            if (malformedResponse) return (HttpResponse<T>) new FakeResponse(request, 200, "not JSON".getBytes(StandardCharsets.UTF_8));
            assertTrue(request.headers().firstValue("Authorization").isEmpty());
            String raw = request.uri().getRawPath();
            assertTrue(raw.startsWith("/api/v4/projects/platform%2Fnested%2Fgraphs/"), raw);
            rawProjectObserved = true;
            String endpoint = java.net.URLDecoder.decode(raw.substring("/api/v4/projects/platform%2Fnested%2Fgraphs".length()), StandardCharsets.UTF_8);
            if (request.method().equals("GET") && endpoint.equals("/repository/branches/" + DRAFT)
                    && releaseRootReads >= 3 && beforeParentCapture != null) {
                var action = beforeParentCapture; beforeParentCapture = null; action.run();
            }
            if (request.method().equals("POST") && endpoint.equals("/repository/commits") && race != null) {
                var barrier = race;
                try { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception failure) { throw new IOException(failure); }
                race = null;
            }
            if (request.method().equals("POST") && endpoint.equals("/merge_requests") && mrRace != null) {
                var barrier = mrRace;
                try { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception failure) { throw new IOException(failure); }
                mrRace = null;
            }
            byte[] bytes = request.bodyPublisher().isPresent() ? read(request.bodyPublisher().orElseThrow()) : new byte[0];
            Object body = bytes.length == 0 ? Map.of() : PayloadJson.read(bytes, JSON).toJava();
            Response result = dispatch(request.method(), endpoint, request.uri().getRawQuery(), body);
            if (request.method().equals("POST") && endpoint.equals("/repository/commits") && result.status == 201 && loseCommit) {
                loseCommit = false; throw new IOException("lost accepted commit response");
            }
            if (request.method().equals("POST") && endpoint.equals("/merge_requests") && result.status == 201 && loseMr) {
                loseMr = false; throw new IOException("lost accepted MR response");
            }
            return (HttpResponse<T>) new FakeResponse(request, result.status, result.body);
        }
        private synchronized Response dispatch(String method, String endpoint, String query, Object decoded) {
            if (unauthorized) return response(401, Map.of("error", "do not disclose"));
            Map<String, Object> body = map(decoded);
            if (method.equals("GET") && endpoint.startsWith("/repository/branches/")) {
                String head = refs.get(endpoint.substring("/repository/branches/".length()));
                return head == null ? response(404, Map.of()) : response(200, Map.of("commit", Map.of("id", head)));
            }
            if (method.equals("POST") && endpoint.equals("/repository/branches")) {
                if (refs.containsKey(body.get("branch"))) return response(400, Map.of());
                refs.put(str(body,"branch"), str(body,"ref")); return response(201, Map.of());
            }
            if (method.equals("GET") && endpoint.equals("/repository/tree")) {
                treeRequests++;
                if (infiniteTree) {
                    var page = new ArrayList<Object>();
                    for (int i = 0; i < 100; i++) page.add(Map.of("id", sha(i + 1), "name", "file" + i,
                            "path", "file" + i, "type", "blob", "mode", "100644"));
                    return response(200, page);
                }
                Commit commit = commits.get(param(query,"ref"));
                if (param(query, "ref").equals(refs.get("main")) && param(query, "path").isEmpty()) releaseRootReads++;
                String dir = param(query,"path"), prefix = dir.isEmpty() ? "" : dir + "/";
                var entries = new java.util.TreeMap<String, Object>();
                for (var entry : commit.files.entrySet()) {
                    if (!entry.getKey().startsWith(prefix)) continue;
                    String rest = entry.getKey().substring(prefix.length()); int slash = rest.indexOf('/');
                    String name = slash < 0 ? rest : rest.substring(0,slash);
                    String blob = digest(entry.getValue()); blobs.put(blob, entry.getValue());
                    entries.put(name, Map.of("name", name, "path", prefix + name, "type", slash < 0 ? "blob" : "tree",
                            "mode", slash < 0 ? symlink && name.endsWith(".graphml") ? "120000" : "100644" : "040000", "id", blob));
                }
                List<Object> values = List.copyOf(entries.values()); int size = Integer.parseInt(param(query,"per_page"));
                int start = Math.min(values.size(), (Integer.parseInt(param(query,"page"))-1)*size);
                return response(200, values.subList(start, Math.min(start+size,values.size())));
            }
            if (method.equals("GET") && endpoint.startsWith("/repository/blobs/")) {
                String blob = endpoint.substring("/repository/blobs/".length());
                return response(200, Map.of("sha",blob,"encoding","base64","content", Base64.getEncoder().encodeToString(blobs.get(blob))));
            }
            if (method.equals("GET") && endpoint.startsWith("/repository/files/")) {
                Commit commit = commits.get(param(query,"ref")); String path = endpoint.substring("/repository/files/".length());
                if (!commit.files.containsKey(path)) return response(404, Map.of());
                String blob = digest(commit.files.get(path)); blobs.put(blob, commit.files.get(path));
                return response(200, Map.of("blob_id",blob,"last_commit_id",commit.modified.get(path)));
            }
            if (method.equals("POST") && endpoint.equals("/repository/commits")) {
                if (beforeCommit != null) { var action = beforeCommit; beforeCommit = null; action.run(); }
                String branch = str(body,"branch"), parent = refs.get(branch); Commit previous = commits.get(parent);
                List<?> actions = (List<?>) body.get("actions");
                for (Object item : actions) {
                    Map<String,Object> action=map(item); String path=str(action,"file_path"), operation=str(action,"action");
                    if (operation.equals("create") ? previous.files.containsKey(path)
                            : !previous.files.containsKey(path) || !previous.modified.get(path).equals(action.get("last_commit_id"))) return response(400,Map.of());
                }
                String id=sha(++sequence); var files=new HashMap<>(previous.files); var modified=new HashMap<>(previous.modified);
                for (Object item : actions) {
                    var action=map(item); String path=str(action,"file_path");
                    if (str(action,"action").equals("delete")) { files.remove(path); modified.remove(path); }
                    else { files.put(path,Base64.getDecoder().decode(str(action,"content"))); modified.put(path,id); }
                }
                commits.put(id,new Commit(parent,str(body,"commit_message"),Map.copyOf(files),Map.copyOf(modified))); refs.put(branch,id); writes++;
                if (afterCommit != null) { var action=afterCommit; afterCommit=null; action.run(); }
                return response(201,Map.of("id",id,"parent_ids",List.of(parent)));
            }
            if (method.equals("GET") && endpoint.startsWith("/repository/commits/")) {
                String id=endpoint.substring("/repository/commits/".length());
                return response(200,Map.of("id",id,"parent_ids",List.of(commits.get(id).parent)));
            }
            if (method.equals("GET") && endpoint.equals("/repository/commits")) {
                String head=param(query,"ref_name"); if (refs.containsKey(head)) head=refs.get(head);
                String path=param(query,"path"); var history=new ArrayList<Object>();
                while (head!=null && !head.isBlank()) {
                    Commit current=commits.get(head); Commit previous=commits.get(current.parent);
                    if (!java.util.Arrays.equals(current.files.get(path),previous==null?null:previous.files.get(path)))
                        history.add(Map.of("id",head,"message",current.message,"author_name","Ravenroot","authored_date","2026-10-17T10:00:00Z"));
                    head=current.parent;
                }
                int size = Integer.parseInt(param(query,"per_page"));
                int start = Math.min(history.size(), (Integer.parseInt(param(query,"page")) - 1) * size);
                return response(200, history.subList(start, Math.min(start + size, history.size())));
            }
            if (method.equals("GET") && endpoint.equals("/merge_requests")) return response(200,mrs);
            if (method.equals("GET") && endpoint.startsWith("/merge_requests/")) return response(200,mrs.get(Integer.parseInt(endpoint.substring("/merge_requests/".length()))-1));
            if (method.equals("POST") && endpoint.equals("/merge_requests")) {
                if (!mrs.isEmpty()) return response(409,Map.of());
                Map<String,Object> item=Map.of("iid",1,"source_branch",str(body,"source_branch"),"target_branch",str(body,"target_branch"),
                        "description",str(body,"description"),"web_url","https://gitlab.example.test/platform/nested/graphs/-/merge_requests/1",
                        "diff_refs",Map.of("head_sha",refs.get(str(body,"source_branch")),"start_sha",refs.get(str(body,"target_branch"))));
                mrs.add(item); return response(201,item);
            }
            throw new AssertionError(method+" "+endpoint+"?"+query);
        }
        private static Response response(int status,Object value) { return new Response(status,PayloadJson.writeJava(value,JSON)); }
        @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>) value; }
        private static String str(Map<String,Object> value,String key) { return (String) value.get(key); }
        private static String param(String query,String key) {
            for(String pair:query.split("&")) { String[] parts=pair.split("=",2); if(parts[0].equals(key)) return parts.length<2?"":java.net.URLDecoder.decode(parts[1],StandardCharsets.UTF_8); }
            return "";
        }
        private static String sha(int value) { return "%040x".formatted(value); }
        private static String digest(byte[] bytes) {
            try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes)); }
            catch(Exception impossible) { throw new AssertionError(impossible); }
        }
        private static byte[] read(HttpRequest.BodyPublisher publisher) {
            var result = new java.io.ByteArrayOutputStream();
            var completed = new CompletableFuture<Void>();
            publisher.subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                public void onNext(ByteBuffer item) { byte[] bytes = new byte[item.remaining()]; item.get(bytes); result.writeBytes(bytes); }
                public void onError(Throwable failure) { completed.completeExceptionally(failure); }
                public void onComplete() { completed.complete(null); }
            });
            completed.join();
            return result.toByteArray();
        }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try { return CompletableFuture.completedFuture(send(request, handler)); }
            catch (IOException failure) { return CompletableFuture.failedFuture(failure); }
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                          HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }

        private record Commit(String parent,String message,Map<String,byte[]> files,Map<String,String> modified) { }
        private record Response(int status,byte[] body) { }
    }
    private record FakeResponse(HttpRequest request, int statusCode, byte[] body)
            implements HttpResponse<byte[]> {
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
