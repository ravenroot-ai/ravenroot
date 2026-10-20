package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;
import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.core.security.OutboundHttpPolicy;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** GitHub Contents/PR API implementation of the neutral authoring archive. */
public final class GithubAuthoringRepository implements GraphAuthoringRepository {
    private static final Set<Capability> CAPABILITIES = Set.of(Capability.values());
    private static final Pattern DOCUMENT_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,155}\\.graphml");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{15,127}");
    private static final PayloadLimits JSON_LIMITS =
            new PayloadLimits(8 * 1024 * 1024, 24, 10_000, 100_000, 4 * 1024 * 1024, 512);
    private static final int IDEMPOTENCY_LIMIT = 1024;
    private static final long IDEMPOTENCY_TTL_SECONDS = 15 * 60;

    private final GraphAuthoringConfiguration configuration;
    private AuthoringIndexReader indexReader;
    private final ReadObjects objects = new ReadObjects();
    private final ReadResults<String> immutableCommits =
            new ReadResults<>(tree -> 128L + tree.length() * 2L, 2048, 1024 * 1024L);
    private final ThreadLocal<Boolean> immutableReads = ThreadLocal.withInitial(() -> false);
    private final ReadResults<List<GitEntry>> immutableTrees =
            new ReadResults<>(
                    entries ->
                            entries.stream()
                                    .mapToLong(
                                            entry ->
                                                    192L
                                                            + entry.name().length() * 2L
                                                            + entry.sha().length() * 2L)
                                    .sum(),
                    2048,
                    64 * 1024 * 1024L);

    private final ConditionalProviderReads conditionalReads = new ConditionalProviderReads();
    private final ReadResults<Difference> differences =
            new ReadResults<>(value -> 128L + value.unifiedDiff().length() * 2L);
    private final ReadResults<Page<HistoryEntry>> historyPages =
            new ReadResults<>(
                    value ->
                            128L
                                    + value.items().stream()
                                            .mapToLong(
                                                    entry ->
                                                            128L
                                                                    + entry.summary().length() * 2L
                                                                    + entry.author().length() * 2L)
                                            .sum());
    private final ThreadLocal<Map<String, DirectorySnapshot>> snapshotDirectories =
            ThreadLocal.withInitial(HashMap::new);
    private final ThreadLocal<long[]> snapshotBytes = ThreadLocal.withInitial(() -> new long[1]);
    private final ConcurrentHashMap<String, LongAdder> operationDurations =
            new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> snapshotCaching = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Map<String, Response>> snapshotObjects =
            ThreadLocal.withInitial(HashMap::new);
    private final Semaphore admissionPermits = new Semaphore(128, true);
    private final Semaphore operationPermits = new Semaphore(64, true);
    private final LongAdder providerCalls = new LongAdder();
    private final LongAdder operationNanos = new LongAdder();

    /** Configures the owned projection before the adapter is published by its composition root. */
    void useReadIndex(GraphAuthoringReadIndex index) {
        this.indexReader.close();
        this.indexReader = new AuthoringIndexReader(index, configuration);
    }

    /** Supported coarse diagnostics; no repository, tenant, subject or credential metric labels. */
    public Map<String, Long> readStatistics() {
        var statistics = new LinkedHashMap<String, Long>();
        statistics.put("hits", objects.hits.sum());
        statistics.put("misses", objects.misses.sum());
        statistics.put("evictions", objects.evictions.sum());
        statistics.put("bypasses", objects.bypasses.sum());
        statistics.put("retainedBytes", objects.retainedBytes());
        statistics.put("treeRetainedBytes", immutableTrees.retainedBytes());
        statistics.put("historyRetainedBytes", historyPages.retainedBytes());
        statistics.put("diffRetainedBytes", differences.retainedBytes());
        statistics.put("commitRetainedBytes", immutableCommits.retainedBytes());
        statistics.put("inFlight", (long) objects.inFlight());
        statistics.put("providerCalls", providerCalls.sum());
        statistics.put("admittedOperations", 128L - admissionPermits.availablePermits());
        statistics.put("operationNanos", operationNanos.sum());
        statistics.put("synchronizations", indexReader.synchronizations.sum());
        statistics.put("synchronizationNanos", indexReader.synchronizationNanos.sum());
        statistics.put(
                "synchronizationLagMillis",
                indexReader.lastValidatedMillis == 0
                        ? -1
                        : Math.max(
                                0, System.currentTimeMillis() - indexReader.lastValidatedMillis));
        operationDurations.forEach(
                (operation, duration) -> statistics.put(operation + "Nanos", duration.sum()));
        return Map.copyOf(statistics);
    }

    private final PublicationEvidence publications;
    private final HttpClient client;
    private final GithubTokenSource tokens;
    private final OutboundHttpPolicy network;
    private final Clock clock;
    private final Consumer<byte[]> admission;
    private final ExecutorService executor;
    private final int providerResponseLimit;
    private final PayloadLimits providerJsonLimits;
    private final ConcurrentHashMap<IdempotencyScope, IdempotencyEntry> idempotency =
            new ConcurrentHashMap<>();

    public GithubAuthoringRepository(
            GraphAuthoringConfiguration configuration,
            CredentialResolver credentials,
            PublicationEvidence publications) {
        this(configuration, credentials, publications, ignored -> {});
    }

    public GithubAuthoringRepository(
            GraphAuthoringConfiguration configuration,
            CredentialResolver credentials,
            PublicationEvidence publications,
            Consumer<byte[]> admission) {
        this(
                configuration,
                credentials,
                publications,
                admission,
                HttpClient.newBuilder()
                        .connectTimeout(configuration.requestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Clock.systemUTC());
    }

    GithubAuthoringRepository(
            GraphAuthoringConfiguration configuration,
            CredentialResolver credentials,
            PublicationEvidence publications,
            Consumer<byte[]> admission,
            HttpClient client,
            Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.indexReader =
                new AuthoringIndexReader(new VolatileGraphAuthoringReadIndex(), configuration);
        if (configuration.mode() != GraphAuthoringConfiguration.Mode.GIT
                || configuration.provider() != GraphAuthoringConfiguration.Provider.GITHUB) {
            throw new IllegalArgumentException(
                    "GitHub repository requires Git authoring configuration");
        }
        this.publications = Objects.requireNonNull(publications, "publications");
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.tokens =
                new GithubTokenSource(
                        configuration,
                        Objects.requireNonNull(credentials, "credentials"),
                        client,
                        clock);
        this.providerResponseLimit =
                Math.toIntExact(
                        Math.addExact(
                                4096L,
                                Math.multiplyExact(
                                        (configuration.maxDocumentBytes() + 2L) / 3L, 4L)));
        this.providerJsonLimits =
                new PayloadLimits(
                        providerResponseLimit, 24, 10_000, 100_000, providerResponseLimit, 512);
        this.network =
                new OutboundHttpPolicy(
                        Set.of(configuration.apiBase().getHost()),
                        configuration.requestTimeout(),
                        Set.of(port(configuration.apiBase())),
                        providerResponseLimit,
                        configuration.maxDocumentBytes() * 2L);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletionStage<Page<DocumentSummary>> list(Actor actor, String cursor) {
        return async("list", () -> listSync(actor, cursor));
    }

    @Override
    public CompletionStage<Document> open(Actor actor, String documentId, boolean releasedSource) {
        return async("open", () -> openSync(actor, documentId, releasedSource));
    }

    @Override
    public CompletionStage<Document> save(Actor actor, SaveRequest request) {
        return async("save", () -> saveSync(actor, request, "save"));
    }

    @Override
    public CompletionStage<Page<HistoryEntry>> history(
            Actor actor, String documentId, String cursor) {
        return async("history", () -> historySync(actor, documentId, cursor));
    }

    @Override
    public CompletionStage<Difference> diff(
            Actor actor, String documentId, String fromRevision, String toRevision) {
        return async(
                "diff",
                () -> {
                    validateDocumentId(documentId);
                    documentPath(actor, documentId);
                    validateRevision(fromRevision);
                    validateRevision(toRevision);
                    return differences.get(
                            List.of(actor.tenantId(), documentId, fromRevision, toRevision),
                            () -> diffSync(actor, documentId, fromRevision, toRevision));
                });
    }

    @Override
    public CompletionStage<Document> restore(Actor actor, MutationRequest request) {
        return async(
                "restore",
                () -> {
                    requireMutation(actor, request, "restore", request.sourceRevision());
                    FileSnapshot historical =
                            file(actor, request.documentId(), request.sourceRevision(), true);
                    if (historical == null)
                        throw failure(GraphAuthoringException.Failure.NOT_FOUND);
                    return saveSync(
                            actor,
                            new SaveRequest(
                                    request.documentId(),
                                    historical.bytes(),
                                    request.expected(),
                                    request.idempotencyKey()),
                            "restore");
                });
    }

    @Override
    public CompletionStage<Void> deleteDraft(Actor actor, MutationRequest request) {
        return async(
                "delete",
                () -> {
                    deleteSync(actor, request);
                    return null;
                });
    }

    @Override
    public CompletionStage<Document> discardDraft(Actor actor, MutationRequest request) {
        return async(
                "discard",
                () -> {
                    requireMutation(actor, request, "discard", "");
                    State state = state(actor, request.documentId());
                    requireExpected(request.expected(), state.revision());
                    if (state.release() == null)
                        throw failure(GraphAuthoringException.Failure.NOT_FOUND);
                    return saveSync(
                            actor,
                            new SaveRequest(
                                    request.documentId(),
                                    state.release().bytes(),
                                    request.expected(),
                                    request.idempotencyKey()),
                            "discard",
                            true);
                });
    }

    @Override
    public CompletionStage<ReleaseProposal> proposeRelease(Actor actor, MutationRequest request) {
        return async("proposeRelease", () -> proposeReleaseSync(actor, request));
    }

    @Override
    public void close() {
        try {
            executor.close();
        } finally {
            indexReader.close();
        }
    }

    private Page<DocumentSummary> listSync(Actor actor, String cursor) {
        Objects.requireNonNull(actor, "actor");
        // Mutable refs authorize every request, including continuation and unchanged index hits.
        String draft = ref(draftBranch(actor), true),
                release = ref(configuration.releaseBranch(), true);
        snapshotCaching.set(true);
        return indexReader.list(
                actor,
                cursor,
                draft,
                release,
                publications,
                () -> indexedDocuments(actor, draft),
                () -> indexedDocuments(actor, release));
    }

    private Map<String, AuthoringIndexReader.Blob> indexedDocuments(Actor actor, String commit) {
        if (commit == null) return Map.of();
        DirectorySnapshot directory =
                directory(commit, true, configuration.tenantDirectory(actor.tenantId()));
        if (directory == null) return Map.of();
        var documents = new TreeMap<String, AuthoringIndexReader.Blob>();
        for (GitEntry entry : directory.entries()) {
            if (!DOCUMENT_ID.matcher(entry.name()).matches()) continue;
            if (!entry.regularBlob())
                throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
            var blob =
                    new AuthoringIndexReader.Blob(
                            entry.sha(),
                            () -> {
                                return readBlob(entry.sha(), configuration.maxDocumentBytes());
                            });
            if (documents.put(entry.name(), blob) != null) throw unavailable();
        }
        return Map.copyOf(documents);
    }

    private List<String> documentsAt(Actor actor, String branch) {
        DirectorySnapshot directory =
                directory(branch, false, configuration.tenantDirectory(actor.tenantId()));
        if (directory == null) return List.of();
        var documents = new ArrayList<String>();
        for (GitEntry entry : directory.entries()) {
            if (!DOCUMENT_ID.matcher(entry.name()).matches()) continue;
            if (!entry.regularBlob())
                throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
            documents.add(entry.name());
        }
        return List.copyOf(documents);
    }

    private void requireUniqueGraphId(Actor actor, String documentId, String graphId) {
        var documents = new HashSet<String>();
        documents.addAll(documentsAt(actor, draftBranch(actor)));
        documents.addAll(documentsAt(actor, configuration.releaseBranch()));
        for (String other : documents) {
            if (!other.equals(documentId)
                    && graphId.equals(state(actor, other).summary().graphId())) {
                throw failure(GraphAuthoringException.Failure.CONFLICT);
            }
        }
    }

    private Document openSync(Actor actor, String documentId, boolean releasedSource) {
        validateDocumentId(documentId);
        State state = state(actor, documentId);
        FileSnapshot source = releasedSource ? state.release() : state.draft();
        if (source == null) throw failure(GraphAuthoringException.Failure.NOT_FOUND);
        if (!releasedSource) return new Document(state.summary(), source.bytes());
        GraphReleaseMetadata.Metadata metadata = GraphReleaseMetadata.read(source.bytes());
        if (metadata == null) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        boolean published = state.summary().published();
        var summary =
                new DocumentSummary(
                        documentId,
                        documentId,
                        metadata.graphId(),
                        metadata.releaseVersion(),
                        state.revision(),
                        true,
                        published,
                        true,
                        metadata.graphId(),
                        metadata.releaseVersion());
        return new Document(summary, source.bytes());
    }

    private Document saveSync(Actor actor, SaveRequest request, String operation) {
        return saveSync(actor, request, operation, false);
    }

    private Document saveSync(
            Actor actor,
            SaveRequest request,
            String operation,
            boolean preserveSubmittedReleaseMetadata) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        validateDocumentId(request.documentId());
        if (request.graphMl().length > configuration.maxDocumentBytes()) {
            throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        }
        admission.accept(request.graphMl());
        String fingerprint =
                fingerprint(
                        operation,
                        request.documentId(),
                        request.expected().toString(),
                        request.graphMl());
        IdempotencyEntry prior =
                beginIdempotency(actor, request.idempotencyKey(), operation, fingerprint);
        if (prior != null && prior.result() instanceof Document result) return result;

        boolean draftBranchExisted = ref(draftBranch(actor), true) != null;
        if (draftBranchExisted && committed(actor, request.documentId(), fingerprint)) {
            State recovered = state(actor, request.documentId());
            if (recovered.draft() == null) throw unavailable();
            GraphReleaseMetadata.Rewritten recoveredBytes =
                    preserveSubmittedReleaseMetadata
                            ? preservedGraphMl(request.graphMl())
                            : acceptedGraphMl(request.graphMl(), recovered);
            if (!MessageDigest.isEqual(recovered.draft().bytes(), recoveredBytes.graphMl()))
                throw unavailable();
            Document result = new Document(recovered.summary(), recovered.draft().bytes());
            completeIdempotency(actor, request.idempotencyKey(), operation, fingerprint, result);
            return result;
        }
        State initial = state(actor, request.documentId());
        requireExpected(request.expected(), initial.revision());

        ensureDraftBranch(actor);
        State state = state(actor, request.documentId());
        if (draftBranchExisted) {
            requireExpected(request.expected(), state.revision());
        } else if (!initial.revision().release().equals(state.revision().release())
                || !initial.revision().publication().equals(state.revision().publication())) {
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
        GraphReleaseMetadata.Rewritten accepted =
                preserveSubmittedReleaseMetadata
                        ? preservedGraphMl(request.graphMl())
                        : acceptedGraphMl(request.graphMl(), state);
        requireUniqueGraphId(actor, request.documentId(), accepted.metadata().graphId());
        if (accepted.graphMl().length > configuration.maxDocumentBytes()) {
            throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        }
        if (state.draft() != null
                && MessageDigest.isEqual(state.draft().bytes(), accepted.graphMl())) {
            Document unchanged = new Document(state.summary(), state.draft().bytes());
            completeIdempotency(actor, request.idempotencyKey(), operation, fingerprint, unchanged);
            return unchanged;
        }
        try {
            commitFile(
                    actor,
                    request.documentId(),
                    accepted.graphMl(),
                    state.revision(),
                    commitMessage(operation, actor, request.idempotencyKey(), fingerprint));
        } catch (GraphAuthoringException failure) {
            if (failure.failure() != GraphAuthoringException.Failure.UNAVAILABLE) throw failure;
            if (!committed(actor, request.documentId(), fingerprint)) throw failure;
        }
        State stored = state(actor, request.documentId());
        if (stored.draft() == null
                || !MessageDigest.isEqual(stored.draft().bytes(), accepted.graphMl()))
            throw unavailable();
        Document result = new Document(stored.summary(), stored.draft().bytes());
        completeIdempotency(actor, request.idempotencyKey(), operation, fingerprint, result);
        return result;
    }

    private static GraphReleaseMetadata.Rewritten preservedGraphMl(byte[] graphMl) {
        GraphReleaseMetadata.Metadata metadata = GraphReleaseMetadata.read(graphMl);
        if (metadata == null) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        return new GraphReleaseMetadata.Rewritten(metadata, graphMl);
    }

    private void deleteSync(Actor actor, MutationRequest request) {
        requireMutation(actor, request, "delete", "");
        String fingerprint =
                fingerprint(
                        "delete", request.documentId(), request.expected().toString(), new byte[0]);
        IdempotencyEntry prior =
                beginIdempotency(actor, request.idempotencyKey(), "delete", fingerprint);
        if (prior != null && prior.result() != null) return;
        if (committed(actor, request.documentId(), fingerprint)) {
            if (state(actor, request.documentId()).draft() != null) throw unavailable();
            completeIdempotency(
                    actor, request.idempotencyKey(), "delete", fingerprint, Boolean.TRUE);
            return;
        }
        State state = state(actor, request.documentId());
        requireExpected(request.expected(), state.revision());
        if (state.draft() == null) throw failure(GraphAuthoringException.Failure.NOT_FOUND);
        try {
            commitFile(
                    actor,
                    request.documentId(),
                    null,
                    state.revision(),
                    commitMessage("delete", actor, request.idempotencyKey(), fingerprint));
        } catch (GraphAuthoringException failure) {
            if (failure.failure() != GraphAuthoringException.Failure.UNAVAILABLE
                    || !committed(actor, request.documentId(), fingerprint)
                    || state(actor, request.documentId()).draft() != null) throw failure;
        }
        completeIdempotency(actor, request.idempotencyKey(), "delete", fingerprint, Boolean.TRUE);
    }

    private Page<HistoryEntry> historySync(Actor actor, String documentId, String cursor) {
        validateDocumentId(documentId);
        String head = ref(draftBranch(actor), true);
        if (head == null) head = ref(configuration.releaseBranch(), false);
        final String pinned = head;
        return historyPages.get(
                List.of(actor.tenantId(), documentId, pinned, Integer.toString(page(cursor))),
                () -> historyAt(actor, documentId, cursor, pinned));
    }

    private Page<HistoryEntry> historyAt(
            Actor actor, String documentId, String cursor, String head) {
        int page = page(cursor) + 1;
        Response response =
                request(
                        "GET",
                        "/repos/"
                                + repository()
                                + "/commits?sha="
                                + query(head)
                                + "&path="
                                + query(documentPath(actor, documentId))
                                + "&per_page="
                                + configuration.pageSize()
                                + "&page="
                                + page,
                        null,
                        Set.of(200));
        Object decoded = json(response.body());
        if (!(decoded instanceof List<?> values)) throw unavailable();
        var entries = new ArrayList<HistoryEntry>();
        for (Object value : values) {
            Map<String, Object> item = object(value);
            Map<String, Object> commit = object(item.get("commit"));
            Map<String, Object> author = object(commit.get("author"));
            entries.add(
                    new HistoryEntry(
                            string(item, "sha"),
                            firstLine(string(commit, "message")),
                            string(author, "name"),
                            Instant.parse(string(author, "date"))));
        }
        return new Page<>(
                entries, values.size() == configuration.pageSize() ? Integer.toString(page) : "");
    }

    private Difference diffSync(Actor actor, String documentId, String from, String to) {
        validateDocumentId(documentId);
        FileSnapshot before = file(actor, documentId, validateRevision(from), true);
        FileSnapshot after = file(actor, documentId, validateRevision(to), true);
        if (before == null || after == null)
            throw failure(GraphAuthoringException.Failure.NOT_FOUND);
        String left = new String(before.bytes(), StandardCharsets.UTF_8);
        String right = new String(after.bytes(), StandardCharsets.UTF_8);
        String diff =
                left.equals(right)
                        ? ""
                        : "--- " + from + "\n+++ " + to + "\n-" + left + "\n+" + right;
        boolean truncated = diff.length() > 64 * 1024;
        if (truncated) diff = diff.substring(0, 64 * 1024);
        return new Difference(from, to, diff, truncated);
    }

    private ReleaseProposal proposeReleaseSync(Actor actor, MutationRequest request) {
        requireMutation(actor, request, "release", "");
        String fingerprint =
                fingerprint(
                        "release",
                        request.documentId(),
                        request.expected().toString(),
                        new byte[0]);
        IdempotencyEntry prior =
                beginIdempotency(actor, request.idempotencyKey(), "release", fingerprint);
        if (prior != null && prior.result() instanceof ReleaseProposal proposal) return proposal;
        State state = state(actor, request.documentId());
        requireExpected(request.expected(), state.revision());
        String head = ref(draftBranch(actor), false);
        String base = ref(configuration.releaseBranch(), false);
        requireExpected(request.expected(), state(actor, request.documentId()).revision());
        if (head.equals(base)) throw failure(GraphAuthoringException.Failure.CONFLICT);
        String queryPath =
                "/repos/"
                        + repository()
                        + "/pulls?state=open&head="
                        + query(configuration.repositoryOwner() + ":" + draftBranch(actor))
                        + "&base="
                        + query(configuration.releaseBranch())
                        + "&per_page=20";
        String marker = "Ravenroot-Authoring-Request: " + fingerprint;
        Response existing = request("GET", queryPath, null, Set.of(200));
        Object decoded = json(existing.body());
        Map<String, Object> matching = matchingPull(decoded, head, base, null);
        if (matching != null) {
            requireProposalState(actor, request.documentId(), request.expected(), head, base);
            Map<String, Object> pull = matching;
            ReleaseProposal proposal =
                    new ReleaseProposal(string(pull, "html_url"), head, base, true);
            completeIdempotency(actor, request.idempotencyKey(), "release", fingerprint, proposal);
            return proposal;
        }
        var body =
                Map.of(
                        "title",
                        "Release graph changes for " + actor.tenantId(),
                        "head",
                        draftBranch(actor),
                        "base",
                        configuration.releaseBranch(),
                        "body",
                        "Reviewed graph-source changes for the configured tenant namespace. "
                                + "This does not authorize a Ravenroot product release.\n\n"
                                + marker);
        try {
            Response created =
                    request(
                            "POST",
                            "/repos/" + repository() + "/pulls",
                            jsonBytes(body),
                            Set.of(201));
            Map<String, Object> pull = object(json(created.body()));
            requirePullState(pull, head, base);
            if (!string(pull, "body").contains(marker)) throw unavailable();
            requireProposalState(actor, request.documentId(), request.expected(), head, base);
            ReleaseProposal proposal =
                    new ReleaseProposal(string(pull, "html_url"), head, base, false);
            completeIdempotency(actor, request.idempotencyKey(), "release", fingerprint, proposal);
            return proposal;
        } catch (GraphAuthoringException failure) {
            if (failure.failure() != GraphAuthoringException.Failure.UNAVAILABLE) throw failure;
            Response reconciled = request("GET", queryPath, null, Set.of(200));
            Map<String, Object> pull = matchingPull(json(reconciled.body()), head, base, marker);
            if (pull == null) throw failure;
            requireProposalState(actor, request.documentId(), request.expected(), head, base);
            ReleaseProposal proposal =
                    new ReleaseProposal(string(pull, "html_url"), head, base, true);
            completeIdempotency(actor, request.idempotencyKey(), "release", fingerprint, proposal);
            return proposal;
        }
    }

    private void requireProposalState(
            Actor actor, String documentId, Revision expected, String head, String base) {
        requireExpected(expected, state(actor, documentId).revision());
        if (!head.equals(ref(draftBranch(actor), false))
                || !base.equals(ref(configuration.releaseBranch(), false))) {
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
    }

    private static Map<String, Object> matchingPull(
            Object decoded, String head, String base, String marker) {
        if (!(decoded instanceof List<?> values)) throw unavailable();
        for (Object value : values) {
            Map<String, Object> pull = object(value);
            if (head.equals(string(object(pull.get("head")), "sha"))
                    && base.equals(string(object(pull.get("base")), "sha"))
                    && (marker == null || string(pull, "body").contains(marker))) return pull;
        }
        return null;
    }

    private GraphReleaseMetadata.Rewritten acceptedGraphMl(byte[] submitted, State state) {
        GraphReleaseMetadata.Metadata submittedMetadata = GraphReleaseMetadata.read(submitted);
        GraphReleaseMetadata.Metadata draftMetadata =
                state.draft() == null ? null : GraphReleaseMetadata.read(state.draft().bytes());
        GraphReleaseMetadata.Metadata releaseMetadata =
                state.release() == null ? null : GraphReleaseMetadata.read(state.release().bytes());
        String graphId =
                draftMetadata != null
                        ? draftMetadata.graphId()
                        : releaseMetadata != null
                                ? releaseMetadata.graphId()
                                : submittedMetadata != null
                                        ? submittedMetadata.graphId()
                                        : "g"
                                                + java.util
                                                        .UUID
                                                        .randomUUID()
                                                        .toString()
                                                        .replace("-", "");
        if (GraphDefinitionIdentityReserved.isReserved(graphId))
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        if (submittedMetadata != null && !graphId.equals(submittedMetadata.graphId()))
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        long version;
        if (draftMetadata != null) {
            version = draftMetadata.releaseVersion();
        } else if (releaseMetadata != null) {
            version = releaseMetadata.releaseVersion();
        } else {
            version = submittedMetadata == null ? 1 : submittedMetadata.releaseVersion();
        }
        if (releaseMetadata != null && state.summary().published()) {
            boolean matchesPublishedRelease =
                    equivalent(
                            submitted,
                            state.release().bytes(),
                            graphId,
                            releaseMetadata.releaseVersion());
            version =
                    matchesPublishedRelease
                            ? releaseMetadata.releaseVersion()
                            : Math.max(version, Math.addExact(releaseMetadata.releaseVersion(), 1));
        }
        if (releaseMetadata != null && version < releaseMetadata.releaseVersion())
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        return GraphReleaseMetadata.assign(submitted, graphId, version);
    }

    private static boolean equivalent(byte[] left, byte[] right, String graphId, long version) {
        return MessageDigest.isEqual(
                GraphReleaseMetadata.assign(left, graphId, version).graphMl(),
                GraphReleaseMetadata.assign(right, graphId, version).graphMl());
    }

    private State state(Actor actor, String documentId) {
        validateDocumentId(documentId);
        FileSnapshot draft = file(actor, documentId, draftBranch(actor), false);
        FileSnapshot release = file(actor, documentId, configuration.releaseBranch(), false);
        GraphReleaseMetadata.Metadata draftMetadata =
                draft == null ? null : GraphReleaseMetadata.read(draft.bytes());
        GraphReleaseMetadata.Metadata releaseMetadata =
                release == null ? null : GraphReleaseMetadata.read(release.bytes());
        if ((draft != null && draftMetadata == null)
                || (release != null && releaseMetadata == null)
                || (draftMetadata != null
                        && releaseMetadata != null
                        && !draftMetadata.graphId().equals(releaseMetadata.graphId()))) {
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        }
        GraphReleaseMetadata.Metadata metadata =
                draftMetadata != null ? draftMetadata : releaseMetadata;
        String publication =
                releaseMetadata == null
                        ? "absent"
                        : publications.token(
                                actor.tenantId(),
                                releaseMetadata.graphId(),
                                releaseMetadata.releaseVersion(),
                                release.commitSha(),
                                release.bytes());
        Revision revision = new Revision(token(draft), token(release), publication);
        if (metadata == null) {
            metadata = new GraphReleaseMetadata.Metadata("unassigned", 1);
        }
        boolean released = releaseMetadata != null;
        boolean published = !"absent".equals(publication);
        DocumentSummary summary =
                new DocumentSummary(
                        documentId,
                        documentId,
                        metadata.graphId(),
                        metadata.releaseVersion(),
                        revision,
                        released,
                        published,
                        draft == null && released,
                        released ? releaseMetadata.graphId() : "",
                        released ? releaseMetadata.releaseVersion() : 0);
        return new State(draft, release, summary);
    }

    private byte[] readBlob(String identity, int limit) {
        byte[] bytes =
                objects.blob(
                        identity,
                        indexReader.index(),
                        indexReader.repository(),
                        () -> {
                            Response response =
                                    request(
                                            "GET",
                                            "/repos/"
                                                    + repository()
                                                    + "/git/blobs/"
                                                    + path(identity),
                                            null,
                                            Set.of(200));
                            Map<String, Object> body = object(json(response.body()));
                            if (!identity.equals(string(body, "sha"))
                                    || !"base64".equals(string(body, "encoding")))
                                throw unavailable();
                            byte[] decoded;
                            try {
                                decoded =
                                        Base64.getDecoder()
                                                .decode(string(body, "content").replace("\n", ""));
                            } catch (IllegalArgumentException invalid) {
                                throw unavailable();
                            }
                            if (decoded.length > limit)
                                throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
                            return decoded;
                        });
        if (bytes.length > limit) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        return bytes;
    }

    private FileSnapshot file(Actor actor, String documentId, String ref, boolean exactCommit) {
        String fullPath = documentPath(actor, documentId);
        int separator = fullPath.lastIndexOf('/');
        DirectorySnapshot directory = directory(ref, exactCommit, fullPath.substring(0, separator));
        if (directory == null) return null;
        GitEntry entry =
                directory.entries().stream()
                        .filter(
                                candidate ->
                                        candidate.name().equals(fullPath.substring(separator + 1)))
                        .findFirst()
                        .orElse(null);
        if (entry == null) return null;
        if (!entry.regularBlob()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        byte[] bytes = readBlob(entry.sha(), configuration.maxDocumentBytes());
        return new FileSnapshot(directory.commitSha(), entry.sha(), bytes);
    }

    /** Resolve each component through non-recursive Git trees pinned to one commit. */
    private DirectorySnapshot directory(String ref, boolean exact, String directoryPath) {
        String key = ref + "\n" + exact + "\n" + directoryPath;
        if (snapshotCaching.get()) {
            var cached = snapshotDirectories.get().get(key);
            if (cached != null) return cached;
        }
        DirectorySnapshot directory = directoryUncached(ref, exact, directoryPath);
        if (snapshotCaching.get() && directory != null && snapshotDirectories.get().size() < 1024)
            snapshotDirectories.get().put(key, directory);
        return directory;
    }

    private DirectorySnapshot directoryUncached(
            String ref, boolean exactCommit, String directoryPath) {
        String commitSha = exactCommit ? ref : ref(ref, true);
        if (commitSha == null) return null;
        String treeSha = commitTree(commitSha);
        if (treeSha == null) return null;
        List<GitEntry> entries = treeEntries(treeSha);
        for (String component : directoryPath.split("/")) {
            GitEntry next =
                    entries.stream()
                            .filter(entry -> entry.name().equals(component))
                            .findFirst()
                            .orElse(null);
            if (next == null) return null;
            if (!next.directory()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
            entries = treeEntries(next.sha());
        }
        return new DirectorySnapshot(commitSha, entries);
    }

    private String commitTree(String commitSha) {
        Supplier<String> load =
                () -> {
                    Response response =
                            request(
                                    "GET",
                                    "/repos/" + repository() + "/git/commits/" + path(commitSha),
                                    null,
                                    Set.of(200, 404));
                    if (response.status() == 404) return null;
                    Map<String, Object> commit = object(json(response.body()));
                    if (commit.containsKey("sha") && !commitSha.equals(string(commit, "sha")))
                        throw unavailable();
                    return string(object(commit.get("tree")), "sha");
                };
        return immutableReads.get() ? immutableCommits.get(List.of(commitSha), load) : load.get();
    }

    private List<GitEntry> treeEntries(String identity) {
        return immutableReads.get()
                ? immutableTrees.get(List.of(identity), () -> treeEntriesUncached(identity))
                : treeEntriesUncached(identity);
    }

    private List<GitEntry> treeEntriesUncached(String treeSha) {
        Map<String, Object> body =
                object(
                        json(
                                request(
                                                "GET",
                                                "/repos/"
                                                        + repository()
                                                        + "/git/trees/"
                                                        + path(treeSha),
                                                null,
                                                Set.of(200))
                                        .body()));
        if (body.containsKey("sha") && !treeSha.equals(string(body, "sha"))) throw unavailable();
        if (!(body.get("truncated") instanceof Boolean truncated) || truncated) throw unavailable();
        if (!(body.get("tree") instanceof List<?> values)) throw unavailable();
        var entries = new ArrayList<GitEntry>();
        for (Object value : values) {
            Map<String, Object> entry = object(value);
            String name = string(entry, "path");
            if (name.contains("/") || name.equals(".") || name.equals("..")) throw unavailable();
            entries.add(
                    new GitEntry(
                            name,
                            string(entry, "mode"),
                            string(entry, "type"),
                            string(entry, "sha")));
        }
        if (entries.stream().map(GitEntry::name).distinct().count() != entries.size())
            throw unavailable();
        return List.copyOf(entries);
    }

    private void ensureDraftBranch(Actor actor) {
        if (ref(draftBranch(actor), true) != null) return;
        String release = ref(configuration.releaseBranch(), false);
        var body = Map.of("ref", "refs/heads/" + draftBranch(actor), "sha", release);
        request("POST", "/repos/" + repository() + "/git/refs", jsonBytes(body), Set.of(201, 422));
        if (ref(draftBranch(actor), true) == null) throw unavailable();
    }

    /**
     * Writes through the Git Data API and advances the tenant draft ref with a non-force,
     * expected-parent update. The final ref compare-and-swap is the mutation boundary; contents,
     * release and publication are checked again immediately before it.
     */
    private void commitFile(
            Actor actor, String documentId, byte[] graphMl, Revision expected, String message) {
        String branch = draftBranch(actor);
        String parent = ref(branch, false);
        Map<String, Object> parentCommit =
                object(
                        json(
                                request(
                                                "GET",
                                                "/repos/"
                                                        + repository()
                                                        + "/git/commits/"
                                                        + path(parent),
                                                null,
                                                Set.of(200))
                                        .body()));
        String baseTree = string(object(parentCommit.get("tree")), "sha");
        Object blobSha = null;
        if (graphMl != null) {
            var blob =
                    object(
                            json(
                                    request(
                                                    "POST",
                                                    "/repos/" + repository() + "/git/blobs",
                                                    jsonBytes(
                                                            Map.of(
                                                                    "content",
                                                                    Base64.getEncoder()
                                                                            .encodeToString(
                                                                                    graphMl),
                                                                    "encoding",
                                                                    "base64")),
                                                    Set.of(201))
                                            .body()));
            blobSha = string(blob, "sha");
        }
        var treeEntry = new LinkedHashMap<String, Object>();
        treeEntry.put("path", documentPath(actor, documentId));
        treeEntry.put("mode", "100644");
        treeEntry.put("type", "blob");
        treeEntry.put("sha", blobSha);
        var tree =
                object(
                        json(
                                request(
                                                "POST",
                                                "/repos/" + repository() + "/git/trees",
                                                jsonBytes(
                                                        Map.of(
                                                                "base_tree",
                                                                baseTree,
                                                                "tree",
                                                                List.of(treeEntry))),
                                                Set.of(201))
                                        .body()));
        var commit =
                object(
                        json(
                                request(
                                                "POST",
                                                "/repos/" + repository() + "/git/commits",
                                                jsonBytes(
                                                        Map.of(
                                                                "message",
                                                                message,
                                                                "tree",
                                                                string(tree, "sha"),
                                                                "parents",
                                                                List.of(parent))),
                                                Set.of(201))
                                        .body()));
        String commitSha = string(commit, "sha");

        // A release merge or publication between the initial check and this point invalidates the
        // authored-version transition even though it touches another ref/system.
        requireExpected(expected, state(actor, documentId).revision());
        if (!parent.equals(ref(branch, false)))
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        request(
                "PATCH",
                "/repos/" + repository() + "/git/refs/heads/" + path(branch),
                jsonBytes(Map.of("sha", commitSha, "force", false)),
                Set.of(200));
        State after = state(actor, documentId);
        if (!expected.release().equals(after.revision().release())
                || !expected.publication().equals(after.revision().publication())) {
            compensateStaleCommit(branch, parent, baseTree, commitSha, message);
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
    }

    /**
     * Restores the pre-mutation tree with a new fast-forward commit when release/publication state
     * changes in the narrow interval after GitHub accepted the draft CAS. History records both the
     * attempted operation and its cancellation; no shared ref is force-pushed or rewound.
     */
    private void compensateStaleCommit(
            String branch,
            String originalParent,
            String originalTree,
            String staleCommit,
            String originalMessage) {
        if (!staleCommit.equals(ref(branch, false))) return;
        String cancellation =
                "Cancel stale graph draft mutation\n\nRavenroot-Authoring-Cancelled-Commit: "
                        + staleCommit
                        + "\n"
                        + originalMessage;
        var commit =
                object(
                        json(
                                request(
                                                "POST",
                                                "/repos/" + repository() + "/git/commits",
                                                jsonBytes(
                                                        Map.of(
                                                                "message",
                                                                cancellation,
                                                                "tree",
                                                                originalTree,
                                                                "parents",
                                                                List.of(staleCommit))),
                                                Set.of(201))
                                        .body()));
        String cancellationSha = string(commit, "sha");
        if (!staleCommit.equals(ref(branch, false))) return;
        request(
                "PATCH",
                "/repos/" + repository() + "/git/refs/heads/" + path(branch),
                jsonBytes(Map.of("sha", cancellationSha, "force", false)),
                Set.of(200));
        if (!cancellationSha.equals(ref(branch, false))) throw unavailable();
        // The original parent remains reachable through both commits, which preserves the complete
        // audit trail while restoring its exact tree.
        if (!originalParent.equals(objectCommitParent(staleCommit))) throw unavailable();
        if (!staleCommit.equals(objectCommitParent(cancellationSha))) throw unavailable();
    }

    private String objectCommitParent(String commitSha) {
        Map<String, Object> commit =
                object(
                        json(
                                request(
                                                "GET",
                                                "/repos/"
                                                        + repository()
                                                        + "/git/commits/"
                                                        + path(commitSha),
                                                null,
                                                Set.of(200))
                                        .body()));
        Object parents = commit.get("parents");
        if (!(parents instanceof List<?> values) || values.size() != 1) throw unavailable();
        return string(object(values.getFirst()), "sha");
    }

    /** Reconciles an unknown response by finding this request's server-authored commit marker. */
    private boolean committed(Actor actor, String documentId, String fingerprint) {
        if (ref(draftBranch(actor), true) == null) return false;
        Response response =
                request(
                        "GET",
                        "/repos/"
                                + repository()
                                + "/commits?sha="
                                + query(draftBranch(actor))
                                + "&path="
                                + query(documentPath(actor, documentId))
                                + "&per_page=100&page=1",
                        null,
                        Set.of(200));
        Object decoded = json(response.body());
        if (!(decoded instanceof List<?> values)) throw unavailable();
        String marker = "Ravenroot-Authoring-Request: " + fingerprint;
        for (Object value : values) {
            Map<String, Object> commit = object(object(value).get("commit"));
            if (string(commit, "message").contains(marker)) return true;
        }
        return false;
    }

    private String ref(String branch, boolean optional) {
        Response response =
                request(
                        "GET",
                        "/repos/" + repository() + "/git/ref/heads/" + path(branch),
                        null,
                        optional ? Set.of(200, 404) : Set.of(200));
        if (response.status() == 404) return null;
        Map<String, Object> body = object(json(response.body()));
        return string(object(body.get("object")), "sha");
    }

    private void authorizeAccess() {
        Response response = request("GET", "/repos/" + repository(), null, Set.of(200, 404));
        if (response.status() == 404) throw failure(GraphAuthoringException.Failure.UNAUTHORIZED);
    }

    private Response request(String method, String endpoint, byte[] body, Set<Integer> expected) {
        boolean immutable =
                snapshotCaching.get()
                        && "GET".equals(method)
                        && (endpoint.contains("/git/commits/") || endpoint.contains("/git/trees/"));
        if (immutable) {
            Response cached = snapshotObjects.get().get(endpoint);
            if (cached != null) return cached;
        }

        URI uri = api(endpoint);
        network.requireAllowed(uri);
        if (body != null) network.requireRequestWithinLimit(body);
        String token = tokens.token();
        Response response = send(method, uri, token, body);
        for (int attempt = 0;
                "GET".equals(method)
                        && attempt < configuration.retryLimit()
                        && AuthoringRetry.retryable(response.status(), response.headers());
                attempt++) {
            AuthoringRetry.pause(response.headers(), attempt, configuration.requestTimeout());
            response = send(method, uri, token, body);
        }
        if ((response.status() == 401
                        || response.status() == 403
                                && !AuthoringRetry.limited(response.status(), response.headers()))
                && configuration.credentialMode()
                        == GraphAuthoringConfiguration.CredentialMode.APP) {
            tokens.invalidate(token);
            response = send(method, uri, tokens.token(), body);
        }
        if (AuthoringRetry.limited(response.status(), response.headers())) throw unavailable();
        if (!expected.contains(response.status())) {
            throw failure(
                    switch (response.status()) {
                        case 401, 403 -> GraphAuthoringException.Failure.UNAUTHORIZED;
                        case 404 -> GraphAuthoringException.Failure.NOT_FOUND;
                        case 409, 412, 422 -> GraphAuthoringException.Failure.CONFLICT;
                        case 413 -> GraphAuthoringException.Failure.LIMIT_EXCEEDED;
                        default -> GraphAuthoringException.Failure.UNAVAILABLE;
                    });
        }
        if (immutable
                && response.status() == 200
                && snapshotObjects.get().size() < 1024
                && snapshotBytes.get()[0] + response.body().length <= 64 * 1024 * 1024L) {
            snapshotObjects.get().put(endpoint, response);
            snapshotBytes.get()[0] += response.body().length;
        }
        return response;
    }

    private Response send(String method, URI uri, String token, byte[] body) {
        try {
            var previous = "GET".equals(method) ? conditionalReads.get(uri) : null;
            var builder =
                    HttpRequest.newBuilder(uri)
                            .timeout(configuration.requestTimeout())
                            .header("Accept", "application/vnd.github+json")
                            .header("Authorization", "Bearer " + token)
                            .header("X-GitHub-Api-Version", "2022-11-28");
            if (previous != null) builder.header("If-None-Match", previous.etag());
            if (body != null) builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            providerCalls.increment();
            var response =
                    client.send(
                            builder.build(),
                            BoundedHttpResponseBody.upTo(
                                    providerResponseLimit, configuration.requestTimeout()));
            byte[] bytes = response.body();
            if (response.statusCode() == 304) {
                if (previous == null) throw unavailable();
                return new Response(200, previous.bytes(), response.headers());
            }
            if ("GET".equals(method) && response.statusCode() == 200)
                conditionalReads.observe(uri, bytes, response.headers());
            if (bytes.length > providerResponseLimit)
                throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
            return new Response(response.statusCode(), bytes, response.headers());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GraphAuthoringException(
                    GraphAuthoringException.Failure.UNAVAILABLE, interrupted);
        } catch (IOException failure) {
            throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE, failure);
        }
    }

    private IdempotencyEntry beginIdempotency(
            Actor actor, String key, String operation, String fingerprint) {
        if (!IDEMPOTENCY_KEY.matcher(key).matches())
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        pruneIdempotency();
        IdempotencyScope scope =
                new IdempotencyScope(actor.tenantId(), actor.subject(), operation, key);
        IdempotencyEntry proposed = new IdempotencyEntry(fingerprint, clock.instant(), null);
        IdempotencyEntry existing = idempotency.putIfAbsent(scope, proposed);
        if (existing == null) return null;
        if (!existing.fingerprint().equals(fingerprint))
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        if (existing.result() == null) return existing;
        return existing;
    }

    private void completeIdempotency(
            Actor actor, String key, String operation, String fingerprint, Object result) {
        idempotency.put(
                new IdempotencyScope(actor.tenantId(), actor.subject(), operation, key),
                new IdempotencyEntry(fingerprint, clock.instant(), result));
    }

    private void pruneIdempotency() {
        Instant cutoff = clock.instant().minusSeconds(IDEMPOTENCY_TTL_SECONDS);
        idempotency.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
        if (idempotency.size() <= IDEMPOTENCY_LIMIT) return;
        idempotency.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().createdAt()))
                .limit(idempotency.size() - IDEMPOTENCY_LIMIT)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(idempotency::remove);
    }

    private void requireMutation(
            Actor actor, MutationRequest request, String operation, String source) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        validateDocumentId(request.documentId());
        if (!source.isEmpty()) validateRevision(source);
        if (!IDEMPOTENCY_KEY.matcher(request.idempotencyKey()).matches()) {
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        }
    }

    private static void requireExpected(Revision expected, Revision actual) {
        if (!actual.equals(expected)) throw failure(GraphAuthoringException.Failure.CONFLICT);
    }

    private static void requirePullState(Map<String, Object> pull, String head, String base) {
        if (!head.equals(string(object(pull.get("head")), "sha"))
                || !base.equals(string(object(pull.get("base")), "sha"))) {
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
    }

    private String draftBranch(Actor actor) {
        String namespace = configuration.tenantNamespaces().get(actor.tenantId());
        if (namespace == null) throw failure(GraphAuthoringException.Failure.UNAUTHORIZED);
        return configuration.draftBranch() + "/" + namespace.replace('/', '-');
    }

    private String documentPath(Actor actor, String documentId) {
        try {
            return configuration.tenantDirectory(actor.tenantId()) + "/" + documentId;
        } catch (IllegalArgumentException absent) {
            throw failure(GraphAuthoringException.Failure.UNAUTHORIZED);
        }
    }

    private String repository() {
        return path(configuration.repositoryOwner()) + "/" + path(configuration.repositoryName());
    }

    private URI api(String endpoint) {
        String base = configuration.apiBase().toString();
        return URI.create(
                (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + endpoint);
    }

    private static String token(FileSnapshot file) {
        return file == null ? "absent" : file.commitSha() + ":" + file.blobSha();
    }

    private static String validateRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-fA-F]{40,64}")) {
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        }
        return revision;
    }

    private static void validateDocumentId(String value) {
        if (value == null || !DOCUMENT_ID.matcher(value).matches())
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
    }

    private static int page(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0 || value > 100_000) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        }
    }

    private static int port(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return newline < 0 ? value : value.substring(0, newline);
    }

    private static String commitMessage(
            String operation, Actor actor, String key, String fingerprint) {
        return switch (operation) {
                    case "delete" -> "Delete graph draft";
                    case "restore" -> "Restore graph draft";
                    case "discard" -> "Discard graph draft changes";
                    default -> "Save graph draft";
                }
                + "\n\nRavenroot-Authoring-Actor: "
                + digest(actor.tenantId() + "\n" + actor.subject()).substring(0, 16)
                + "\nRavenroot-Authoring-Operation: "
                + key
                + "\nRavenroot-Authoring-Request: "
                + fingerprint;
    }

    private static String fingerprint(String operation, String id, String expected, byte[] bytes) {
        return digest(operation + "\n" + id + "\n" + expected + "\n" + digest(bytes));
    }

    private static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private byte[] jsonBytes(Object value) {
        return PayloadJson.writeJava(value, providerJsonLimits);
    }

    private Object json(byte[] bytes) {
        try {
            return PayloadJson.read(bytes, providerJsonLimits).toJava();
        } catch (RuntimeException invalid) {
            throw unavailable();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw unavailable();
        return (Map<String, Object>) map;
    }

    private static String string(Map<String, Object> value, String key) {
        Object found = value.get(key);
        if (!(found instanceof String text) || text.isBlank()) throw unavailable();
        return text;
    }

    private <T> CompletionStage<T> async(String operation, Callable<T> task) {
        if (!admissionPermits.tryAcquire())
            return CompletableFuture.failedFuture(
                    failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED));
        var handle = new AtomicReference<Future<?>>();
        var result =
                new CompletableFuture<T>() {
                    @Override
                    public boolean cancel(boolean interrupt) {
                        boolean cancelled = super.cancel(interrupt);
                        var submitted = handle.get();
                        if (cancelled && submitted != null) submitted.cancel(interrupt);
                        return cancelled;
                    }
                };
        try {
            var submitted =
                    new AuthoringAdmission(
                            admissionPermits,
                            () -> {
                                long began = System.nanoTime();
                                boolean acquired = false;
                                try {
                                    operationPermits.acquire();
                                    acquired = true;
                                    immutableReads.set(
                                            Set.of("list", "open", "history", "diff")
                                                    .contains(operation));
                                    authorizeAccess();
                                    result.complete(task.call());
                                } catch (InterruptedException failure) {
                                    Thread.currentThread().interrupt();
                                    result.completeExceptionally(unavailable());
                                } catch (GraphAuthoringException failure) {
                                    result.completeExceptionally(failure);
                                } catch (Exception failure) {
                                    result.completeExceptionally(unavailable());
                                } catch (Error failure) {
                                    result.completeExceptionally(unavailable());
                                    throw failure;
                                } finally {
                                    if (acquired) operationPermits.release();
                                    long elapsed = System.nanoTime() - began;
                                    operationNanos.add(elapsed);
                                    operationDurations
                                            .computeIfAbsent(
                                                    operation,
                                                    ignored ->
                                                            new java.util.concurrent.atomic
                                                                    .LongAdder())
                                            .add(elapsed);
                                    immutableReads.remove();
                                    snapshotObjects.remove();
                                    snapshotCaching.remove();
                                    snapshotBytes.remove();
                                    snapshotDirectories.remove();
                                }
                            });
            handle.set(submitted);
            executor.execute(submitted);
            if (result.isCancelled()) submitted.cancel(true);
        } catch (RejectedExecutionException closed) {
            handle.get().cancel(false);
            result.completeExceptionally(unavailable());
        }
        return result;
    }

    private static GraphAuthoringException failure(GraphAuthoringException.Failure failure) {
        return new GraphAuthoringException(failure);
    }

    private static GraphAuthoringException unavailable() {
        return failure(GraphAuthoringException.Failure.UNAVAILABLE);
    }

    private record FileSnapshot(String commitSha, String blobSha, byte[] bytes) {
        private FileSnapshot {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record DirectorySnapshot(String commitSha, List<GitEntry> entries) {}

    private record GitEntry(String name, String mode, String type, String sha) {
        boolean directory() {
            return "040000".equals(mode) && "tree".equals(type);
        }

        boolean regularBlob() {
            return ("100644".equals(mode) || "100755".equals(mode)) && "blob".equals(type);
        }
    }

    private record State(FileSnapshot draft, FileSnapshot release, DocumentSummary summary) {
        Revision revision() {
            return summary.revision();
        }
    }

    private record Response(int status, byte[] body, HttpHeaders headers) {}

    private record IdempotencyScope(String tenant, String subject, String operation, String key) {}

    private record IdempotencyEntry(String fingerprint, Instant createdAt, Object result) {}

    /** Keeps the runtime submission namespace unavailable to authored documents. */
    private static final class GraphDefinitionIdentityReserved {
        static boolean isReserved(String graphId) {
            return ai.ravenroot.api.persistence.GraphDefinitionIdentity.SUBMISSION_GRAPH_ID.equals(
                    graphId);
        }
    }
}
