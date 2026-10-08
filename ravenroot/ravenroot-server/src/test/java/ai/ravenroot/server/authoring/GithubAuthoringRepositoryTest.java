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

class GithubAuthoringRepositoryTest {
    private static final GraphAuthoringRepository.Actor ALICE =
            new GraphAuthoringRepository.Actor("tenant-a", "alice");
    private static final GraphAuthoringRepository.Actor BOB =
            new GraphAuthoringRepository.Actor("tenant-a", "bob");
    private static final GraphAuthoringRepository.Revision EMPTY =
            new GraphAuthoringRepository.Revision("absent", "absent", "absent");

    @Test void pinnedTreeReadsRejectCurrentAndHistoricalCrossTenantSymlinks() {
        var github = new ScriptedGithub();
        try (var repository = repository(github, (tenant, graph, version) -> "absent")) {
            var first = repository.save(ALICE, new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("one"),
                    EMPTY, "symlink-test-key-000000001")).toCompletableFuture().join();
            String historicalCommit = commit(first.summary().revision().draft());
            var second = repository.save(ALICE, new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("two"),
                    first.summary().revision(), "symlink-test-key-000000002")).toCompletableFuture().join();
            String documentPath = configuration().tenantDirectory("tenant-a") + "/orders.graphml";

            github.markSymlink(historicalCommit, documentPath, "../other-tenant/private.graphml");
            assertFalse(repository.history(ALICE, "orders.graphml", "").toCompletableFuture().join().items().isEmpty());
            assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT, () -> repository.restore(ALICE,
                    new GraphAuthoringRepository.MutationRequest("orders.graphml", historicalCommit,
                            second.summary().revision(), "symlink-test-key-000000003")).toCompletableFuture().join());

            github.markSymlink(commit(second.summary().revision().draft()), documentPath,
                    "../other-tenant/private.graphml");
            assertFailure(GraphAuthoringException.Failure.INVALID_DOCUMENT,
                    () -> repository.open(ALICE, "orders.graphml", false).toCompletableFuture().join());
        }
    }

    @Test void gitBlobReadsSupportDocumentsLargerThanContentsApiInlineLimit() {
        var github = new ScriptedGithub();
        try (var repository = repository(github, (tenant, graph, version) -> "absent")) {
            byte[] large = graph("x".repeat(1024 * 1024 + 4096));
            var saved = repository.save(ALICE, new GraphAuthoringRepository.SaveRequest("large.graphml", large,
                    EMPTY, "large-blob-test-key-0000001")).toCompletableFuture().join();
            var opened = repository.open(ALICE, "large.graphml", false).toCompletableFuture().join();
            assertArrayEquals(saved.graphMl(), opened.graphMl());
            assertTrue(opened.graphMl().length > 1024 * 1024);
        }
    }

    @Test void failedAttemptWithSameKeyRetriesAndTwoAuthorsCannotOverwrite() {
        var github = new ScriptedGithub();
        try (var repository = repository(github, (tenant, graph, version) -> "absent")) {
            github.failOnce("/git/blobs");
            var request = new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("one"), EMPTY,
                    "request-key-000000000001");
            assertFailure(GraphAuthoringException.Failure.UNAVAILABLE, () -> repository.save(ALICE, request).toCompletableFuture().join());

            var saved = repository.save(ALICE, request).toCompletableFuture().join();
            assertEquals(1, saved.summary().releaseVersion());
            assertTrue(new String(saved.graphMl(), StandardCharsets.UTF_8).contains("one"));

            var stale = new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("two"), EMPTY,
                    "request-key-000000000002");
            assertFailure(GraphAuthoringException.Failure.CONFLICT, () -> repository.save(BOB, stale).toCompletableFuture().join());
            assertTrue(new String(repository.open(ALICE, "orders.graphml", false)
                    .toCompletableFuture().join().graphMl(), StandardCharsets.UTF_8).contains("one"));
        }
    }

    @Test void unknownRefOutcomeIsReconciledAfterServerRestart() {
        var github = new ScriptedGithub();
        var request = new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("one"), EMPTY,
                "request-key-000000000003");
        github.loseNextPatchResponse();
        try (var first = repository(github, (tenant, graph, version) -> "absent")) {
            assertTrue(new String(first.save(ALICE, request).toCompletableFuture().join().graphMl(),
                    StandardCharsets.UTF_8).contains("one"));
        }
        try (var restarted = repository(github, (tenant, graph, version) -> "absent")) {
            var replay = restarted.save(ALICE, request).toCompletableFuture().join();
            assertEquals(1, replay.summary().releaseVersion());
            assertEquals(1, github.authoredCommitCount());
        }
    }

    @Test void publicationChangeBeforeRefAdvanceRefusesVersionTransition() {
        var github = new ScriptedGithub();
        var publication = new MutablePublication();
        github.beforeNextPatch(() -> publication.value = "published-concurrently");
        try (var repository = repository(github, publication)) {
            var request = new GraphAuthoringRepository.SaveRequest("orders.graphml", graph("one"), EMPTY,
                    "request-key-000000000004");
            assertFailure(GraphAuthoringException.Failure.CONFLICT,
                    () -> repository.save(ALICE, request).toCompletableFuture().join());
            assertEquals("absent", repository.list(ALICE, "").toCompletableFuture().join()
                    .items().stream().findFirst().map(item -> item.revision().draft()).orElse("absent"));
            assertEquals(2, github.authoredCommitCount(),
                    "the accepted stale commit is cancelled by a second fast-forward commit");
        }
    }

    @Test void completeRemoteLifecyclePreservesVersionsHistoryAndReleasedBytes() {
        var github = new ScriptedGithub();
        try (var firstDevice = repository(github, (tenant, graph, version) -> "absent")) {
            var created = firstDevice.save(ALICE, new GraphAuthoringRepository.SaveRequest(
                    "orders.graphml", graph("one"), EMPTY, "lifecycle-key-000000001"))
                    .toCompletableFuture().join();
            assertEquals(1, created.summary().releaseVersion());
            long commitsAfterCreate = github.authoredCommitCount();

            var noOp = firstDevice.save(ALICE, new GraphAuthoringRepository.SaveRequest(
                    "orders.graphml", created.graphMl(), created.summary().revision(),
                    "lifecycle-key-000000002")).toCompletableFuture().join();
            assertEquals(created.summary().revision(), noOp.summary().revision());
            assertEquals(commitsAfterCreate, github.authoredCommitCount());
        }

        try (var secondDevice = repository(github, (tenant, graph, version) -> "absent")) {
            var listed = secondDevice.list(ALICE, "").toCompletableFuture().join();
            assertEquals(List.of("orders.graphml"), listed.items().stream()
                    .map(GraphAuthoringRepository.DocumentSummary::documentId).toList());
            var releasedCandidate = secondDevice.open(ALICE, "orders.graphml", false)
                    .toCompletableFuture().join();
            assertTrue(new String(releasedCandidate.graphMl(), StandardCharsets.UTF_8).contains("one"));
            String versionOneCommit = commit(releasedCandidate.summary().revision().draft());

            github.mergeDraftToMain();
            var released = secondDevice.open(ALICE, "orders.graphml", true).toCompletableFuture().join();
            assertEquals(1, released.summary().releaseVersion());
            assertTrue(released.summary().released());

            var changed = secondDevice.save(ALICE, new GraphAuthoringRepository.SaveRequest(
                    "orders.graphml", graph("two"), released.summary().revision(),
                    "lifecycle-key-000000003")).toCompletableFuture().join();
            assertEquals(2, changed.summary().releaseVersion(), "first post-release edit increments once");
            var changedAgain = secondDevice.save(ALICE, new GraphAuthoringRepository.SaveRequest(
                    "orders.graphml", graph("three"), changed.summary().revision(),
                    "lifecycle-key-000000004")).toCompletableFuture().join();
            assertEquals(2, changedAgain.summary().releaseVersion(), "later draft saves retain the version");
            var reviewedWhileDraftExists = secondDevice.open(ALICE, "orders.graphml", true)
                    .toCompletableFuture().join();
            assertEquals(1, reviewedWhileDraftExists.summary().releaseVersion(),
                    "released-source identity comes from released bytes, not the newer draft");
            assertTrue(reviewedWhileDraftExists.summary().draftDeleted(),
                    "a released-source view is immutable even while its corresponding draft exists");
            assertEquals(2, secondDevice.open(ALICE, "orders.graphml", false).toCompletableFuture().join()
                    .summary().releaseVersion());

            var history = secondDevice.history(ALICE, "orders.graphml", "").toCompletableFuture().join();
            assertTrue(history.items().size() >= 3);
            String latest = commit(changedAgain.summary().revision().draft());
            var difference = secondDevice.diff(ALICE, "orders.graphml", versionOneCommit, latest)
                    .toCompletableFuture().join();
            assertTrue(difference.unifiedDiff().contains("three"));

            var restored = secondDevice.restore(ALICE, new GraphAuthoringRepository.MutationRequest(
                    "orders.graphml", versionOneCommit, changedAgain.summary().revision(),
                    "lifecycle-key-000000005")).toCompletableFuture().join();
            assertEquals(2, restored.summary().releaseVersion());
            assertTrue(new String(restored.graphMl(), StandardCharsets.UTF_8).contains("one"));

            var discarded = secondDevice.discardDraft(ALICE, new GraphAuthoringRepository.MutationRequest(
                    "orders.graphml", "", restored.summary().revision(),
                    "lifecycle-key-000000006")).toCompletableFuture().join();
            assertEquals(1, discarded.summary().releaseVersion(), "discard restores the reviewed version metadata");
            assertArrayEquals(released.graphMl(), discarded.graphMl());

            secondDevice.deleteDraft(ALICE, new GraphAuthoringRepository.MutationRequest(
                    "orders.graphml", "", discarded.summary().revision(),
                    "lifecycle-key-000000007")).toCompletableFuture().join();
            var deleted = secondDevice.list(ALICE, "").toCompletableFuture().join().items().getFirst();
            assertTrue(deleted.draftDeleted());
            assertArrayEquals(released.graphMl(), secondDevice.open(ALICE, "orders.graphml", true)
                    .toCompletableFuture().join().graphMl(), "draft operations never alter reviewed source");

            var proposal = secondDevice.proposeRelease(ALICE, new GraphAuthoringRepository.MutationRequest(
                    "orders.graphml", "", deleted.revision(), "lifecycle-key-000000008"))
                    .toCompletableFuture().join();
            assertFalse(proposal.reused());
            assertTrue(proposal.url().endsWith("/pull/1"));
        }
    }

    @Test void lostReleaseProposalResponseReconcilesOnlyItsFingerprint() {
        var github = new ScriptedGithub();
        try (var repository = repository(github, (tenant, graph, version) -> "absent")) {
            var saved = repository.save(ALICE, new GraphAuthoringRepository.SaveRequest(
                    "orders.graphml", graph("one"), EMPTY, "release-flow-key-000001"))
                    .toCompletableFuture().join();
            github.loseNextPullResponse();
            var request = new GraphAuthoringRepository.MutationRequest("orders.graphml", "",
                    saved.summary().revision(), "release-flow-key-000002");
            var proposal = repository.proposeRelease(ALICE, request).toCompletableFuture().join();
            assertTrue(proposal.reused());
            assertEquals(1, github.pullCount());
        }
    }

    private static String commit(String revision) { return revision.substring(0, revision.indexOf(':')); }

    private static GithubAuthoringRepository repository(ScriptedGithub github, PublicationEvidence publications) {
        return new GithubAuthoringRepository(configuration(), ignored ->
                Optional.of(new SecretValue("pat".toCharArray())), publications, ignored -> { },
                github, Clock.systemUTC());
    }

    private static GraphAuthoringConfiguration configuration() {
        var environment = new HashMap<String, String>();
        environment.put("RAVENROOT_GRAPH_AUTHORING_MODE", "GIT");
        environment.put("RAVENROOT_GRAPH_AUTHORING_PROVIDER", "GITHUB");
        environment.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_OWNER", "platform");
        environment.put("RAVENROOT_GRAPH_AUTHORING_REPOSITORY_NAME", "graphs");
        environment.put("RAVENROOT_GRAPH_AUTHORING_GITHUB_API_BASE", "https://github.example.test/api/v3");
        environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_MODE", "PAT");
        environment.put("RAVENROOT_GRAPH_AUTHORING_CREDENTIAL_REFERENCE", "git-authoring");
        environment.put("RAVENROOT_GRAPH_AUTHORING_TENANT_NAMESPACES", "tenant-a=customers/a");
        environment.put("RAVENROOT_GRAPH_ARTIFACT_BASE_URL", "https://artifacts.example.test/releases/");
        environment.put("RAVENROOT_GRAPH_AUTHORING_RETRY_LIMIT", "0");
        return GraphAuthoringConfiguration.fromEnvironment(environment);
    }

    private static byte[] graph(String label) {
        return ("<?xml version=\"1.0\"?><graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">"
                + "<key id=\"label\" for=\"graph\" attr.name=\"label\" attr.type=\"string\"/>"
                + "<graph id=\"G\" edgedefault=\"directed\"><data key=\"label\">" + label
                + "</data></graph></graphml>").getBytes(StandardCharsets.UTF_8);
    }

    private static void assertFailure(GraphAuthoringException.Failure expected, Runnable action) {
        Throwable failure = assertThrows(Throwable.class, action::run);
        while (failure.getCause() != null && !(failure instanceof GraphAuthoringException)) failure = failure.getCause();
        assertInstanceOf(GraphAuthoringException.class, failure);
        assertEquals(expected, ((GraphAuthoringException) failure).failure());
    }

    private static final class MutablePublication implements PublicationEvidence {
        private volatile String value = "absent";
        @Override public String token(String tenantId, String graphId, long releaseVersion) { return value; }
    }

    private static final class ScriptedGithub extends HttpClient {
        private static final PayloadLimits JSON = new PayloadLimits(8 * 1024 * 1024, 24, 10_000,
                100_000, 4 * 1024 * 1024, 512);
        private final AtomicInteger sequence = new AtomicInteger(10);
        private final Map<String, String> refs = new HashMap<>();
        private final Map<String, Commit> commits = new LinkedHashMap<>();
        private final Map<String, Map<String, String>> modes = new HashMap<>();
        private final Map<String, byte[]> blobs = new HashMap<>();
        private final Map<String, Delta> trees = new HashMap<>();
        private final List<Pull> pulls = new ArrayList<>();
        private String failPath;
        private boolean losePatch;
        private boolean losePull;
        private Runnable beforePatch;

        ScriptedGithub() {
            String root = sha(1);
            refs.put("main", root);
            commits.put(root, new Commit("", "root", Map.of()));
            modes.put(root, Map.of());
        }

        void failOnce(String path) { failPath = path; }
        void loseNextPatchResponse() { losePatch = true; }
        void loseNextPullResponse() { losePull = true; }
        void beforeNextPatch(Runnable action) { beforePatch = action; }
        long authoredCommitCount() { return commits.values().stream().filter(value -> value.message.contains("Ravenroot-Authoring-Request")).count(); }
        int pullCount() { return pulls.size(); }
        void mergeDraftToMain() { refs.put("main", refs.get("draft/customers-a")); }
        void markSymlink(String commitId, String path, String target) {
            Commit commit = commits.get(commitId);
            var files = new HashMap<>(commit.files);
            files.put(path, target.getBytes(StandardCharsets.UTF_8));
            commits.put(commitId, new Commit(commit.parent, commit.message, Map.copyOf(files)));
            var commitModes = new HashMap<>(modes.getOrDefault(commitId, Map.of()));
            commitModes.put(path, "120000");
            modes.put(commitId, Map.copyOf(commitModes));
        }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> ignored)
                throws IOException {
            String path = request.uri().getRawPath();
            if (failPath != null && path.endsWith(failPath)) {
                failPath = null;
                throw new IOException("scripted pre-mutation failure");
            }
            byte[] requestBytes = request.bodyPublisher().isPresent()
                    ? read(request.bodyPublisher().orElseThrow()) : new byte[0];
            Object body = requestBytes.length == 0 ? Map.of() : decode(requestBytes);
            Response response = dispatch(request.method(), path, request.uri().getRawQuery(), body);
            if ("PATCH".equals(request.method()) && losePatch) {
                losePatch = false;
                throw new IOException("scripted lost response after commit");
            }
            if ("POST".equals(request.method()) && path.endsWith("/pulls") && losePull) {
                losePull = false;
                throw new IOException("scripted lost response after pull creation");
            }
            return (HttpResponse<T>) new FakeResponse(request, response.status, response.body);
        }

        private Response dispatch(String method, String path, String query, Object decoded) {
            String api = "/api/v3/repos/platform/graphs";
            if ("GET".equals(method) && path.startsWith(api + "/git/ref/heads/")) {
                String branch = path.substring((api + "/git/ref/heads/").length());
                String value = refs.get(branch);
                return value == null ? response(404, Map.of()) : response(200, Map.of("object", Map.of("sha", value)));
            }
            if ("POST".equals(method) && path.equals(api + "/git/refs")) {
                Map<String, Object> body = map(decoded);
                refs.put(string(body, "ref").substring("refs/heads/".length()), string(body, "sha"));
                return response(201, Map.of("ok", true));
            }
            if ("GET".equals(method) && path.startsWith(api + "/contents/")) {
                String document = path.substring((api + "/contents/").length());
                String ref = parameter(query, "ref");
                String commitId = refs.getOrDefault(ref, ref);
                Commit commit = commits.get(commitId);
                byte[] value = commit == null ? null : commit.files.get(document);
                if (value != null) return response(200, Map.of(
                        "content", Base64.getEncoder().encodeToString(value), "sha", digest(value)));
                if (commit == null) return response(404, Map.of());
                String prefix = document.endsWith("/") ? document : document + "/";
                var children = commit.files.keySet().stream()
                        .filter(name -> name.startsWith(prefix) && !name.substring(prefix.length()).contains("/"))
                        .map(name -> Map.of("type", (Object) "file", "name", name.substring(prefix.length())))
                        .toList();
                return children.isEmpty() ? response(404, Map.of()) : response(200, children);
            }
            if ("GET".equals(method) && path.startsWith(api + "/git/commits/")) {
                String id = path.substring((api + "/git/commits/").length());
                if (!commits.containsKey(id)) return response(404, Map.of());
                Commit commit = commits.get(id);
                return response(200, Map.of("tree", Map.of("sha", "tree-" + id),
                        "parents", commit.parent.isBlank() ? List.of() : List.of(Map.of("sha", commit.parent))));
            }
            if ("GET".equals(method) && path.startsWith(api + "/git/trees/")) {
                String id = java.net.URLDecoder.decode(path.substring((api + "/git/trees/").length()), StandardCharsets.UTF_8);
                if (!id.startsWith("tree-") || id.length() < 45) return response(404, Map.of());
                String commitId = id.substring(5, 45);
                Commit commit = commits.get(commitId);
                if (commit == null) return response(404, Map.of());
                String prefix = id.length() == 45 ? "" : new String(java.util.HexFormat.of()
                        .parseHex(id.substring(46)), StandardCharsets.UTF_8);
                var names = new java.util.TreeMap<String, Map<String, Object>>();
                for (var file : commit.files.entrySet()) {
                    if (!file.getKey().startsWith(prefix)) continue;
                    String remaining = file.getKey().substring(prefix.length());
                    int slash = remaining.indexOf('/');
                    String name = slash < 0 ? remaining : remaining.substring(0, slash);
                    if (name.isEmpty()) continue;
                    if (slash >= 0) {
                        String childPrefix = prefix + name + "/";
                        String child = "tree-" + commitId + "-" + java.util.HexFormat.of()
                                .formatHex(childPrefix.getBytes(StandardCharsets.UTF_8));
                        names.put(name, Map.of("path", name, "mode", "040000", "type", "tree", "sha", child));
                    } else {
                        String mode = modes.getOrDefault(commitId, Map.of()).getOrDefault(file.getKey(), "100644");
                        String type = "160000".equals(mode) ? "commit" : "blob";
                        String blob = digest(file.getValue());
                        blobs.put(blob, file.getValue());
                        names.put(name, Map.of("path", name, "mode", mode, "type", type, "sha", blob));
                    }
                }
                return response(200, Map.of("truncated", false, "tree", List.copyOf(names.values())));
            }
            if ("GET".equals(method) && path.startsWith(api + "/git/blobs/")) {
                String id = path.substring((api + "/git/blobs/").length());
                byte[] value = blobs.get(id);
                return value == null ? response(404, Map.of()) : response(200, Map.of(
                        "sha", id, "encoding", "base64", "content", Base64.getEncoder().encodeToString(value)));
            }
            if ("POST".equals(method) && path.equals(api + "/git/blobs")) {
                Map<String, Object> body = map(decoded);
                byte[] value = Base64.getDecoder().decode(string(body, "content"));
                String id = sha(sequence.incrementAndGet());
                blobs.put(id, value);
                return response(201, Map.of("sha", id));
            }
            if ("POST".equals(method) && path.equals(api + "/git/trees")) {
                Map<String, Object> body = map(decoded);
                Map<String, Object> entry = map(((List<?>) body.get("tree")).getFirst());
                String id = sha(sequence.incrementAndGet());
                Object blob = entry.get("sha");
                trees.put(id, new Delta(string(entry, "path"), blob == null ? null : blobs.get(blob.toString())));
                return response(201, Map.of("sha", id));
            }
            if ("POST".equals(method) && path.equals(api + "/git/commits")) {
                Map<String, Object> body = map(decoded);
                String parent = String.valueOf(((List<?>) body.get("parents")).getFirst());
                String tree = string(body, "tree");
                Map<String, byte[]> files;
                if (tree.startsWith("tree-") && commits.containsKey(tree.substring(5))) {
                    files = new HashMap<>(commits.get(tree.substring(5)).files);
                } else {
                    files = new HashMap<>(commits.get(parent).files);
                    Delta delta = trees.get(tree);
                    if (delta.value == null) files.remove(delta.path); else files.put(delta.path, delta.value);
                }
                String id = sha(sequence.incrementAndGet());
                commits.put(id, new Commit(parent, string(body, "message"), Map.copyOf(files)));
                var nextModes = new HashMap<>(modes.getOrDefault(parent, Map.of()));
                Delta delta = trees.get(tree);
                if (delta != null) {
                    if (delta.value == null) nextModes.remove(delta.path); else nextModes.put(delta.path, "100644");
                }
                modes.put(id, Map.copyOf(nextModes));
                return response(201, Map.of("sha", id));
            }
            if ("PATCH".equals(method) && path.startsWith(api + "/git/refs/heads/")) {
                if (beforePatch != null) {
                    Runnable action = beforePatch; beforePatch = null; action.run();
                }
                String branch = path.substring((api + "/git/refs/heads/").length());
                String target = string(map(decoded), "sha");
                Commit commit = commits.get(target);
                if (commit == null || !commit.parent.equals(refs.get(branch))) return response(422, Map.of());
                refs.put(branch, target);
                return response(200, Map.of("object", Map.of("sha", target)));
            }
            if ("GET".equals(method) && path.equals(api + "/commits")) {
                String head = refs.get(parameter(query, "sha"));
                List<Object> history = new ArrayList<>();
                while (head != null && !head.isBlank()) {
                    Commit commit = commits.get(head);
                    history.add(Map.of("sha", head, "commit", Map.of("message", commit.message,
                            "author", Map.of("name", "Ravenroot", "date", "2026-10-08T00:00:00Z"))));
                    head = commit.parent;
                }
                return response(200, history);
            }
            if ("GET".equals(method) && path.equals(api + "/pulls")) {
                String head = parameter(query, "head");
                int colon = head.indexOf(':');
                String headBranch = colon < 0 ? head : head.substring(colon + 1);
                String baseBranch = parameter(query, "base");
                return response(200, pulls.stream()
                        .filter(pull -> pull.headBranch.equals(headBranch) && pull.baseBranch.equals(baseBranch))
                        .map(pull -> pullResponse(pull, refs)).toList());
            }
            if ("POST".equals(method) && path.equals(api + "/pulls")) {
                Map<String, Object> body = map(decoded);
                var pull = new Pull("https://github.example.test/platform/graphs/pull/" + (pulls.size() + 1),
                        string(body, "head"), string(body, "base"), string(body, "body"));
                pulls.add(pull);
                return response(201, pullResponse(pull, refs));
            }
            throw new AssertionError(method + " " + path + (query == null ? "" : "?" + query));
        }

        private static Response response(int status, Object value) {
            return new Response(status, PayloadJson.writeJava(value, JSON));
        }
        private static Map<String, Object> map(Object value) {
            return (Map<String, Object>) value;
        }
        private static String string(Map<String, Object> value, String key) { return String.valueOf(value.get(key)); }
        private static Object decode(byte[] bytes) { return PayloadJson.read(bytes, JSON).toJava(); }
        private static String parameter(String query, String name) {
            if (query == null) return "";
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair[0].equals(name)) return java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
            return "";
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
        private static String sha(int value) { return "%040x".formatted(value); }
        private static String digest(byte[] value) {
            try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(value)); }
            catch (Exception impossible) { throw new AssertionError(impossible); }
        }
        private static Map<String, Object> pullResponse(Pull pull, Map<String, String> refs) {
            return Map.of("html_url", pull.url, "body", pull.body,
                    "head", Map.of("sha", refs.get(pull.headBranch)),
                    "base", Map.of("sha", refs.get(pull.baseBranch)));
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

        private record Commit(String parent, String message, Map<String, byte[]> files) { }
        private record Delta(String path, byte[] value) { }
        private record Pull(String url, String headBranch, String baseBranch, String body) { }
        private record Response(int status, byte[] body) { }
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
