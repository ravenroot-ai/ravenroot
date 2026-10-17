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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** GitLab REST v4 implementation of the neutral authoring archive. */
public final class GitlabAuthoringRepository implements GraphAuthoringRepository {
    private static final Set<Capability> CAPABILITIES = Set.of(Capability.values());
    private static final Pattern DOCUMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,155}\\.graphml");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{15,127}");
    
    private final GraphAuthoringConfiguration configuration;
    private final PublicationEvidence publications;
    private final HttpClient client;
    private final CredentialResolver credentials;
    private final ThreadLocal<Pending> pending = new ThreadLocal<>();
    private static final String COORDINATION_FILE = ".ravenroot-authoring.json";
    private static final int MAX_PROVIDER_PAGES = 20;
    private static final int MAX_REQUESTS = 4096;
    private final ThreadLocal<Integer> requestCount = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Long> operationDeadline = new ThreadLocal<>();
    private final OutboundHttpPolicy network;
    private final java.util.function.Consumer<byte[]> admission;
    private final ExecutorService executor;
    private final int providerResponseLimit;
    private final PayloadLimits providerJsonLimits;

    public GitlabAuthoringRepository(GraphAuthoringConfiguration configuration,
                                     CredentialResolver credentials,
                                     PublicationEvidence publications) {
        this(configuration, credentials, publications, ignored -> { });
    }

    public GitlabAuthoringRepository(GraphAuthoringConfiguration configuration,
                                     CredentialResolver credentials,
                                     PublicationEvidence publications,
                                     java.util.function.Consumer<byte[]> admission) {
        this(configuration, credentials, publications, admission,
                HttpClient.newBuilder().connectTimeout(configuration.requestTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
    }

    GitlabAuthoringRepository(GraphAuthoringConfiguration configuration, CredentialResolver credentials,
                              PublicationEvidence publications, java.util.function.Consumer<byte[]> admission,
                              HttpClient client, Clock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        if (configuration.mode() != GraphAuthoringConfiguration.Mode.GIT
                || configuration.provider() != GraphAuthoringConfiguration.Provider.GITLAB) {
            throw new IllegalArgumentException("GitLab repository requires Git authoring configuration");
        }
        this.publications = Objects.requireNonNull(publications, "publications");
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(clock, "clock");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.providerResponseLimit = Math.toIntExact(Math.addExact(1024L * 1024,
                Math.multiplyExact((configuration.maxDocumentBytes() + 2L) / 3L, 4L)));
        this.providerJsonLimits = new PayloadLimits(providerResponseLimit, 24, 10_000,
                100_000, providerResponseLimit, 512);
        this.network = new OutboundHttpPolicy(Set.of(configuration.apiBase().getHost()),
                configuration.requestTimeout(), Set.of(port(configuration.apiBase())),
                providerResponseLimit, configuration.maxDocumentBytes() * 2L + 1024 * 1024);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override public Set<Capability> capabilities() { return CAPABILITIES; }

    @Override public CompletionStage<Page<DocumentSummary>> list(Actor actor, String cursor) {
        return async(() -> listSync(actor, cursor));
    }

    @Override public CompletionStage<Document> open(Actor actor, String documentId, boolean releasedSource) {
        return async(() -> openSync(actor, documentId, releasedSource));
    }

    @Override public CompletionStage<Document> save(Actor actor, SaveRequest request) {
        return async(() -> saveSync(actor, request, "save"));
    }

    @Override public CompletionStage<Page<HistoryEntry>> history(Actor actor, String documentId, String cursor) {
        return async(() -> historySync(actor, documentId, cursor));
    }

    @Override public CompletionStage<Difference> diff(Actor actor, String documentId,
                                                       String fromRevision, String toRevision) {
        return async(() -> diffSync(actor, documentId, fromRevision, toRevision));
    }

    @Override public CompletionStage<Document> restore(Actor actor, MutationRequest request) {
        return async(() -> {
            requireMutation(actor, request, "restore", request.sourceRevision());
            FileSnapshot historical = file(actor, request.documentId(), request.sourceRevision(), true);
            if (historical == null) throw failure(GraphAuthoringException.Failure.NOT_FOUND);
            return saveSync(actor, new SaveRequest(request.documentId(), historical.bytes(), request.expected(),
                    request.idempotencyKey()), "restore", false, request.sourceRevision());
        });
    }

    @Override public CompletionStage<Void> deleteDraft(Actor actor, MutationRequest request) {
        return async(() -> { deleteSync(actor, request); return null; });
    }

    @Override public CompletionStage<Document> discardDraft(Actor actor, MutationRequest request) {
        return async(() -> {
            requireMutation(actor, request, "discard", "");
            State state = state(actor, request.documentId());
            requireExpected(request.expected(), state.revision());
            if (state.release() == null) throw failure(GraphAuthoringException.Failure.NOT_FOUND);
            return saveSync(actor, new SaveRequest(request.documentId(), state.release().bytes(),
                    request.expected(), request.idempotencyKey()), "discard", true);
        });
    }

    @Override public CompletionStage<ReleaseProposal> proposeRelease(Actor actor, MutationRequest request) {
        return async(() -> proposeReleaseSync(actor, request));
    }

    @Override public void close() { executor.close(); }

    private Page<DocumentSummary> listSync(Actor actor, String cursor) {
        Objects.requireNonNull(actor, "actor");
        int page = page(cursor);
        var names = new java.util.TreeSet<String>();
        names.addAll(documentsAt(actor, draftBranch(actor)));
        names.addAll(documentsAt(actor, configuration.releaseBranch()));
        List<String> documents = List.copyOf(names);
        int start = Math.min(page * configuration.pageSize(), documents.size());
        int end = Math.min(start + configuration.pageSize(), documents.size());
        var summaries = new ArrayList<DocumentSummary>();
        for (String document : documents.subList(start, end)) summaries.add(state(actor, document).summary());
        String next = end < documents.size() ? Integer.toString(page + 1) : "";
        return new Page<>(summaries, next);
    }

    private List<String> documentsAt(Actor actor, String branch) {
        DirectorySnapshot directory = directory(branch, false, configuration.tenantDirectory(actor.tenantId()));
        if (directory == null) return List.of();
        var documents = new ArrayList<String>();
        for (GitEntry entry : directory.entries()) {
            if (!DOCUMENT_ID.matcher(entry.name()).matches()) continue;
            if (!entry.regularBlob()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
            documents.add(entry.name());
        }
        return List.copyOf(documents);
    }

    private void requireUniqueGraphId(Actor actor, String documentId, String graphId) {
        var documents = new java.util.HashSet<String>();
        documents.addAll(documentsAt(actor, draftBranch(actor)));
        documents.addAll(documentsAt(actor, configuration.releaseBranch()));
        for (String other : documents) {
            if (!other.equals(documentId) && graphId.equals(state(actor, other).summary().graphId())) {
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
        var summary = new DocumentSummary(documentId, documentId, metadata.graphId(), metadata.releaseVersion(),
                state.revision(), true, published, true, metadata.graphId(), metadata.releaseVersion());
        return new Document(summary, source.bytes());
    }

    private Document saveSync(Actor actor, SaveRequest request, String operation) {
        return saveSync(actor, request, operation, false);
    }

    private Document saveSync(Actor actor, SaveRequest request, String operation,
                              boolean preserveSubmittedReleaseMetadata) {
        return saveSync(actor, request, operation, preserveSubmittedReleaseMetadata, "");
    }

    private Document saveSync(Actor actor, SaveRequest request, String operation,
                              boolean preserveSubmittedReleaseMetadata, String sourceRevision) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        validateDocumentId(request.documentId());
        if (request.graphMl().length > configuration.maxDocumentBytes()) {
            throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        }
        admission.accept(request.graphMl());
        String fingerprint = requestFingerprint(actor, request.idempotencyKey(), operation, request.documentId(), request.expected().toString() + "\n" + sourceRevision, request.graphMl());
        beginIdempotency(actor, request.idempotencyKey(), operation, fingerprint);

        boolean draftBranchExisted = ref(draftBranch(actor), true) != null;
        if (draftBranchExisted && committed(actor, request.documentId(), fingerprint)) {
            State recovered = state(actor, request.documentId());
            if (recovered.draft() == null) throw unavailable();
            GraphReleaseMetadata.Rewritten recoveredBytes = preserveSubmittedReleaseMetadata
                    ? preservedGraphMl(request.graphMl()) : acceptedGraphMl(request.graphMl(), recovered);
            if (!MessageDigest.isEqual(recovered.draft().bytes(), recoveredBytes.graphMl())) throw unavailable();
            Document result = new Document(recovered.summary(), recovered.draft().bytes());

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
        GraphReleaseMetadata.Rewritten accepted = preserveSubmittedReleaseMetadata
                ? preservedGraphMl(request.graphMl()) : acceptedGraphMl(request.graphMl(), state);
        requireUniqueGraphId(actor, request.documentId(), accepted.metadata().graphId());
        if (accepted.graphMl().length > configuration.maxDocumentBytes()) {
            throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        }
        try {
            commitFile(actor, request.documentId(), accepted.graphMl(), state.revision(),
                    commitMessage(operation, actor, request.idempotencyKey(), fingerprint));
        } catch (GraphAuthoringException failure) {
            if (failure.failure() != GraphAuthoringException.Failure.UNAVAILABLE) throw failure;
            if (!committed(actor, request.documentId(), fingerprint)) throw failure;
        }
        State stored = state(actor, request.documentId());
        if (stored.draft() == null || !MessageDigest.isEqual(stored.draft().bytes(), accepted.graphMl())) throw unavailable();
        Document result = new Document(stored.summary(), stored.draft().bytes());

        return result;
    }

    private static GraphReleaseMetadata.Rewritten preservedGraphMl(byte[] graphMl) {
        GraphReleaseMetadata.Metadata metadata = GraphReleaseMetadata.read(graphMl);
        if (metadata == null) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        return new GraphReleaseMetadata.Rewritten(metadata, graphMl);
    }

    private void deleteSync(Actor actor, MutationRequest request) {
        requireMutation(actor, request, "delete", "");
        String fingerprint = requestFingerprint(actor, request.idempotencyKey(), "delete", request.documentId(), request.expected().toString(), new byte[0]);
        beginIdempotency(actor, request.idempotencyKey(), "delete", fingerprint);
        if (committed(actor, request.documentId(), fingerprint)) {
            if (state(actor, request.documentId()).draft() != null) throw unavailable();

            return;
        }
        State state = state(actor, request.documentId());
        requireExpected(request.expected(), state.revision());
        if (state.draft() == null) throw failure(GraphAuthoringException.Failure.NOT_FOUND);
        try {
            commitFile(actor, request.documentId(), null, state.revision(),
                    commitMessage("delete", actor, request.idempotencyKey(), fingerprint));
        } catch (GraphAuthoringException failure) {
            if (failure.failure() != GraphAuthoringException.Failure.UNAVAILABLE
                    || !committed(actor, request.documentId(), fingerprint)
                    || state(actor, request.documentId()).draft() != null) throw failure;
        }

    }

    private Page<HistoryEntry> historySync(Actor actor, String documentId, String cursor) {
        validateDocumentId(documentId);
        String head = ref(draftBranch(actor), true);
        if (head == null) head = ref(configuration.releaseBranch(), false);
        int index = page(cursor) + 1;
        if (index > MAX_PROVIDER_PAGES) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        Object decoded = json(request("GET", projectApi() + "/repository/commits?ref_name=" + query(head)
                + "&path=" + query(documentPath(actor, documentId)) + "&per_page=" + configuration.pageSize()
                + "&page=" + index, null, Set.of(200)).body());
        if (!(decoded instanceof List<?> values) || values.size() > configuration.pageSize()) throw unavailable();
        var entries = new ArrayList<HistoryEntry>();
        for (Object value : values) {
            Map<String, Object> item = object(value);
            entries.add(new HistoryEntry(validateRevision(string(item, "id")), firstLine(string(item, "message")),
                    string(item, "author_name"), Instant.parse(string(item, "authored_date"))));
        }
        return new Page<>(entries, values.size() == configuration.pageSize() ? Integer.toString(index) : "");
    }

    private Difference diffSync(Actor actor, String documentId, String from, String to) {
        validateDocumentId(documentId);
        FileSnapshot before = file(actor, documentId, validateRevision(from), true);
        FileSnapshot after = file(actor, documentId, validateRevision(to), true);
        if (before == null || after == null) throw failure(GraphAuthoringException.Failure.NOT_FOUND);
        String left = new String(before.bytes(), StandardCharsets.UTF_8);
        String right = new String(after.bytes(), StandardCharsets.UTF_8);
        String diff = left.equals(right) ? "" : "--- " + from + "\n+++ " + to + "\n-" + left + "\n+" + right;
        boolean truncated = diff.length() > 64 * 1024;
        if (truncated) diff = diff.substring(0, 64 * 1024);
        return new Difference(from, to, diff, truncated);
    }

    private ReleaseProposal proposeReleaseSync(Actor actor, MutationRequest request) {
        requireMutation(actor, request, "release", "");
        String fingerprint = requestFingerprint(actor, request.idempotencyKey(), "release", request.documentId(),
                request.expected().toString(), new byte[0]);
        beginIdempotency(actor, request.idempotencyKey(), "release", fingerprint);
        requireExpected(request.expected(), state(actor, request.documentId()).revision());
        String head = ref(draftBranch(actor), false);
        String base = ref(configuration.releaseBranch(), false);
        if (head.equals(base)) throw failure(GraphAuthoringException.Failure.CONFLICT);
        String listing = projectApi() + "/merge_requests?state=opened&source_branch=" + query(draftBranch(actor))
                + "&target_branch=" + query(configuration.releaseBranch()) + "&per_page=100";
        Map<String, Object> existing = findMergeRequest(listing, actor, head, base);
        if (existing != null) return proposal(actor, request, existing, head, base, true);
        String marker = "Ravenroot-Authoring-Scope: " + pending.get().scope() + "\nRavenroot-Authoring-Request: " + fingerprint;
        var body = Map.of("title", "Release graph changes", "source_branch", draftBranch(actor),
                "target_branch", configuration.releaseBranch(), "remove_source_branch", false,
                "description", "Review graph-source changes for the configured namespace. "
                        + "This does not authorize a Ravenroot product release.\n\n" + marker);
        requireProposalState(actor, request.documentId(), request.expected(), head, base);
        try {
            Map<String, Object> created = object(json(request("POST", projectApi() + "/merge_requests",
                    jsonBytes(body), Set.of(201)).body()));
            // Newly created diff_refs may be empty while GitLab computes the diff: fetch details
            // once, then fail safely and let a retry reconcile instead of creating a second MR.
            Map<String, Object> detailed = mergeRequestDetails(created);
            return proposal(actor, request, detailed, head, base, false);
        } catch (GraphAuthoringException failed) {
            // GitLab enforces one open MR for an identical source/target pair. This also covers
            // concurrent creation (409/422), and an accepted POST whose response was lost.
            if (failed.failure() != GraphAuthoringException.Failure.UNAVAILABLE
                    && failed.failure() != GraphAuthoringException.Failure.CONFLICT) throw failed;
            Map<String, Object> found = findMergeRequest(listing, actor, head, base);
            if (found == null) throw failed;
            return proposal(actor, request, found, head, base, true);
        }
    }

    private Map<String, Object> findMergeRequest(String listing, Actor actor, String head, String base) {
        for (int page = 1; page <= MAX_PROVIDER_PAGES; page++) {
            Object decoded = json(request("GET", listing + "&page=" + page, null, Set.of(200)).body());
            if (!(decoded instanceof List<?> values) || values.size() > 100) throw unavailable();
            for (Object value : values) {
                Map<String, Object> item = object(value);
                if (!draftBranch(actor).equals(string(item, "source_branch"))
                        || !configuration.releaseBranch().equals(string(item, "target_branch"))) continue;
                Map<String, Object> details = mergeRequestDetails(item);
                requireMergeRequestHeads(details, head, base);
                return details;
            }
            if (values.size() < 100) return null;
        }
        throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
    }

    private Map<String, Object> mergeRequestDetails(Map<String, Object> item) {
        Object iid = item.get("iid");
        if (!(iid instanceof Number number) || number.longValue() < 1) throw unavailable();
        return object(json(request("GET", projectApi() + "/merge_requests/" + number.longValue(),
                null, Set.of(200)).body()));
    }

    private ReleaseProposal proposal(Actor actor, MutationRequest request, Map<String, Object> item,
                                     String head, String base, boolean reused) {
        requireMergeRequestHeads(item, head, base);
        Object description = item.get("description");
        Pending operation = pending.get();
        if (description instanceof String text && text.contains("Ravenroot-Authoring-Scope: " + operation.scope())
                && !text.contains("Ravenroot-Authoring-Request: " + operation.fingerprint())) {
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
        requireProposalState(actor, request.documentId(), request.expected(), head, base);
        URI url;
        try { url = URI.create(string(item, "web_url")); } catch (IllegalArgumentException bad) { throw unavailable(); }
        if (!"https".equals(url.getScheme()) || !configuration.apiBase().getHost().equals(url.getHost())
                || port(url) != port(configuration.apiBase()) || url.getUserInfo() != null
                || url.getQuery() != null || url.getFragment() != null) throw unavailable();
        return new ReleaseProposal(url.toString(), head, base, reused);
    }

    private static void requireMergeRequestHeads(Map<String, Object> item, String head, String base) {
        Map<String, Object> refs = object(item.get("diff_refs"));
        if (!head.equals(string(refs, "head_sha")) || !base.equals(string(refs, "start_sha"))) {
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
    }

    private void requireProposalState(Actor actor, String documentId, Revision expected, String head, String base) {
        requireExpected(expected, state(actor, documentId).revision());
        if (!head.equals(ref(draftBranch(actor), false)) || !base.equals(ref(configuration.releaseBranch(), false))) {
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
    }

    private GraphReleaseMetadata.Rewritten acceptedGraphMl(byte[] submitted, State state) {
        GraphReleaseMetadata.Metadata submittedMetadata = GraphReleaseMetadata.read(submitted);
        GraphReleaseMetadata.Metadata draftMetadata = state.draft() == null ? null : GraphReleaseMetadata.read(state.draft().bytes());
        GraphReleaseMetadata.Metadata releaseMetadata = state.release() == null ? null : GraphReleaseMetadata.read(state.release().bytes());
        String graphId = draftMetadata != null ? draftMetadata.graphId()
                : releaseMetadata != null ? releaseMetadata.graphId()
                : submittedMetadata != null ? submittedMetadata.graphId()
                : "g" + java.util.UUID.randomUUID().toString().replace("-", "");
        if (GraphDefinitionIdentityReserved.isReserved(graphId)) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        if (submittedMetadata != null && !graphId.equals(submittedMetadata.graphId())) throw failure(GraphAuthoringException.Failure.CONFLICT);
        long version;
        if (draftMetadata != null) {
            version = draftMetadata.releaseVersion();
        } else if (releaseMetadata != null) {
            version = releaseMetadata.releaseVersion();
        } else {
            version = submittedMetadata == null ? 1 : submittedMetadata.releaseVersion();
        }
        if (releaseMetadata != null && state.summary().published()) {
            boolean matchesPublishedRelease = equivalent(submitted, state.release().bytes(), graphId,
                    releaseMetadata.releaseVersion());
            version = matchesPublishedRelease ? releaseMetadata.releaseVersion()
                    : Math.max(version, Math.addExact(releaseMetadata.releaseVersion(), 1));
        }
        if (releaseMetadata != null && version < releaseMetadata.releaseVersion()) throw failure(GraphAuthoringException.Failure.CONFLICT);
        return GraphReleaseMetadata.assign(submitted, graphId, version);
    }

    private static boolean equivalent(byte[] left, byte[] right, String graphId, long version) {
        return MessageDigest.isEqual(GraphReleaseMetadata.assign(left, graphId, version).graphMl(),
                GraphReleaseMetadata.assign(right, graphId, version).graphMl());
    }

    private State state(Actor actor, String documentId) {
        validateDocumentId(documentId);
        FileSnapshot draft = file(actor, documentId, draftBranch(actor), false);
        FileSnapshot release = file(actor, documentId, configuration.releaseBranch(), false);
        GraphReleaseMetadata.Metadata draftMetadata = draft == null ? null : GraphReleaseMetadata.read(draft.bytes());
        GraphReleaseMetadata.Metadata releaseMetadata = release == null ? null : GraphReleaseMetadata.read(release.bytes());
        if ((draft != null && draftMetadata == null) || (release != null && releaseMetadata == null)
                || (draftMetadata != null && releaseMetadata != null
                    && !draftMetadata.graphId().equals(releaseMetadata.graphId()))) {
            throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        }
        GraphReleaseMetadata.Metadata metadata = draftMetadata != null ? draftMetadata : releaseMetadata;
        String publication = releaseMetadata == null ? "absent"
                : publications.token(actor.tenantId(), releaseMetadata.graphId(), releaseMetadata.releaseVersion(),
                        release.commitSha(), release.bytes());
        Revision revision = new Revision(token(draft), token(release), publication);
        if (metadata == null) {
            metadata = new GraphReleaseMetadata.Metadata("unassigned", 1);
        }
        boolean released = releaseMetadata != null;
        boolean published = !"absent".equals(publication);
        DocumentSummary summary = new DocumentSummary(documentId, documentId, metadata.graphId(),
                metadata.releaseVersion(), revision, released, published, draft == null && released,
                released ? releaseMetadata.graphId() : "", released ? releaseMetadata.releaseVersion() : 0);
        return new State(draft, release, summary);
    }

    private FileSnapshot file(Actor actor, String documentId, String ref, boolean exactCommit) {
        return sourceFile(actor, documentPath(actor, documentId), ref, exactCommit);
    }

    private FileSnapshot sourceFile(Actor actor, String fullPath, String ref, boolean exactCommit) {
        String commit = exactCommit ? validateRevision(ref) : ref(ref, true);
        if (commit == null) return null;
        if (fullPath.contains("/.ravenroot-authoring/requests/")) return requestRecord(fullPath, commit);
        String directory = fullPath.substring(0, fullPath.lastIndexOf('/'));
        // Traverse each directory component at one immutable SHA. Symlinks and submodules cannot
        // redirect a configured tenant path, even for historical restore/diff reads.
        List<GitEntry> entries = treeEntries(commit, "");
        String prefix = "";
        for (String component : directory.split("/")) {
            GitEntry next = entries.stream().filter(entry -> entry.name().equals(component)).findFirst().orElse(null);
            if (next == null) return null;
            if (!next.directory()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
            prefix = prefix.isEmpty() ? component : prefix + "/" + component;
            entries = treeEntries(commit, prefix);
        }
        String name = fullPath.substring(fullPath.lastIndexOf('/') + 1);
        GitEntry entry = entries.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        if (entry == null) return null;
        if (!entry.regularBlob()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        var body = object(json(request("GET", projectApi() + "/repository/blobs/" + query(entry.sha()),
                null, Set.of(200)).body()));
        if (!entry.sha().equals(string(body, "sha")) || !"base64".equals(string(body, "encoding"))) throw unavailable();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(string(body, "content").replace("\n", "")); }
        catch (IllegalArgumentException bad) { throw unavailable(); }
        if (bytes.length > ((fullPath.endsWith("/" + COORDINATION_FILE) || fullPath.contains("/.ravenroot-authoring/requests/")) ? 1024 * 1024 : configuration.maxDocumentBytes()))
            throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        // Blob reads preserve exact bytes; file metadata supplies the last file commit for CAS.
        var metadata = object(json(request("GET", projectApi() + "/repository/files/" + query(fullPath)
                + "?ref=" + query(commit), null, Set.of(200)).body()));
        if (!entry.sha().equals(string(metadata, "blob_id"))) throw unavailable();
        return new FileSnapshot(commit, entry.sha(), bytes, validateRevision(string(metadata, "last_commit_id")));
    }

    /** Direct lookup keeps lifetime replay records independent of directory pagination ceilings. */
    private FileSnapshot requestRecord(String fullPath, String commit) {
        Response response = request("GET", projectApi() + "/repository/files/" + query(fullPath)
                + "?ref=" + query(commit), null, Set.of(200, 404));
        if (response.status() == 404) return null;
        Map<String, Object> metadata = object(json(response.body()));
        String blob = validateRevision(string(metadata, "blob_id"));
        Map<String, Object> body = object(json(request("GET", projectApi() + "/repository/blobs/" + query(blob),
                null, Set.of(200)).body()));
        if (!blob.equals(string(body, "sha")) || !"base64".equals(string(body, "encoding"))) throw unavailable();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(string(body, "content").replace("\n", "")); }
        catch (IllegalArgumentException bad) { throw unavailable(); }
        if (bytes.length > 4096) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        return new FileSnapshot(commit, blob, bytes, validateRevision(string(metadata, "last_commit_id")));
    }

    private DirectorySnapshot directory(String branch, boolean exact, String directoryPath) {
        String commit = exact ? validateRevision(branch) : ref(branch, true);
        if (commit == null) return null;
        List<GitEntry> entries = treeEntries(commit, "");
        String prefix = "";
        for (String component : directoryPath.split("/")) {
            GitEntry next = entries.stream().filter(entry -> entry.name().equals(component)).findFirst().orElse(null);
            if (next == null) return null;
            if (!next.directory()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
            prefix = prefix.isEmpty() ? component : prefix + "/" + component;
            entries = treeEntries(commit, prefix);
        }
        return new DirectorySnapshot(commit, entries);
    }

    private List<GitEntry> treeEntries(String commit, String directory) {
        var entries = new ArrayList<GitEntry>();
        for (int page = 1; page <= MAX_PROVIDER_PAGES; page++) {
            Response response = request("GET", projectApi() + "/repository/tree?ref=" + query(commit)
                    + "&path=" + query(directory) + "&recursive=false&per_page=100&page=" + page, null, Set.of(200, 404));
            if (response.status() == 404) return List.of();
            Object decoded = json(response.body());
            if (!(decoded instanceof List<?> values) || values.size() > 100) throw unavailable();
            for (Object value : values) {
                Map<String, Object> item = object(value);
                String name = string(item, "name");
                if (name.contains("/") || name.equals(".") || name.equals("..")) throw unavailable();
                String full = directory.isEmpty() ? name : directory + "/" + name;
                if (!full.equals(string(item, "path"))) throw unavailable();
                entries.add(new GitEntry(name, string(item, "mode"), string(item, "type"), validateRevision(string(item, "id"))));
            }
            if (values.size() < 100) return List.copyOf(entries);
        }
        throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
    }

    private void ensureDraftBranch(Actor actor) {
        String branch = draftBranch(actor);
        if (ref(branch, true) != null) return;
        String release = ref(configuration.releaseBranch(), false);
        try {
            request("POST", projectApi() + "/repository/branches",
                    jsonBytes(Map.of("branch", branch, "ref", release)), Set.of(201));
        } catch (GraphAuthoringException failed) {
            if (failed.failure() != GraphAuthoringException.Failure.CONFLICT
                    && failed.failure() != GraphAuthoringException.Failure.UNAVAILABLE) throw failed;
            if (ref(branch, true) == null) throw failed;
        }
    }

    private String coordinationPath(Actor actor) {
        return configuration.tenantDirectory(actor.tenantId()) + "/" + COORDINATION_FILE;
    }

    private String requestPath(Actor actor, Pending operation) {
        return configuration.tenantDirectory(actor.tenantId()) + "/.ravenroot-authoring/requests/" + operation.scope() + ".json";
    }

    private Coordination coordination(Actor actor, String commit) {
        Pending operation = Objects.requireNonNull(pending.get(), "pending operation");
        FileSnapshot guard = sourceFile(actor, coordinationPath(actor), commit, true);
        FileSnapshot record = sourceFile(actor, requestPath(actor, operation), commit, true);
        return new Coordination(guard, record, record == null ? Map.of() : object(json(record.bytes())));
    }

    private void commitFile(Actor actor, String documentId, byte[] graphMl, Revision expected, String message) {
        String branch = draftBranch(actor);
        String parent = ref(branch, false);
        String releaseParent = ref(configuration.releaseBranch(), false);
        // Validate identity within the parent/guard observation, not only during preflight.
        // An absent document's revision stays "absent" when another file is created.
        if (graphMl != null) {
            GraphReleaseMetadata.Metadata metadata = GraphReleaseMetadata.read(graphMl);
            if (metadata == null) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
            requireUniqueGraphId(actor, documentId, metadata.graphId());
        }
        Coordination coordination = coordination(actor, parent);
        Pending operation = Objects.requireNonNull(pending.get(), "pending operation");
        if (coordination.record() != null) throw failure(GraphAuthoringException.Failure.CONFLICT);
        FileSnapshot original = file(actor, documentId, parent, true);
        Map<String, Object> record = Map.of("fingerprint", operation.fingerprint(), "status", "applied", "parent", parent,
                "document", documentId, "release", expected.release(), "publication", expected.publication());
        var actions = new ArrayList<Map<String, Object>>();
        actions.add(action(coordinationPath(actor), coordination.guard(), jsonBytes(Map.of("request", operation.scope()))));
        actions.add(action(requestPath(actor, operation), null, jsonBytes(record)));
        actions.add(action(documentPath(actor, documentId), original, graphMl));
        requireExpected(expected, state(actor, documentId).revision());
        if (!parent.equals(ref(branch, false)) || !releaseParent.equals(ref(configuration.releaseBranch(), false))) {
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
        Map<String, Object> stored;
        try {
            stored = object(json(request("POST", projectApi() + "/repository/commits",
                    jsonBytes(Map.of("branch", branch, "commit_message", message, "actions", actions)), Set.of(201)).body()));
        } catch (GraphAuthoringException failed) {
            if (failed.failure() != GraphAuthoringException.Failure.UNAVAILABLE) throw failed;
            String head = ref(branch, false);
            Coordination observed = coordination(actor, head);
            if (observed.record() == null || !operation.fingerprint().equals(string(observed.row(), "fingerprint"))) throw failed;
            if (observed.guard() == null || !head.equals(observed.guard().lastCommit())
                    || !head.equals(observed.record().lastCommit())) throw unavailable();
            stored = object(json(request("GET", projectApi() + "/repository/commits/" + query(head), null, Set.of(200)).body()));
        }
        String created = validateRevision(string(stored, "id"));
        Object parents = stored.get("parent_ids");
        boolean correctParent = parents instanceof List<?> list && list.size() == 1 && parent.equals(list.getFirst());
        State after = state(actor, documentId);
        if (!correctParent || !releaseParent.equals(ref(configuration.releaseBranch(), false))
                || !expected.release().equals(after.revision().release())
                || !expected.publication().equals(after.revision().publication())) {
            compensate(actor, documentId, original, created, operation, message);
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
    }

    private Map<String, Object> action(String fullPath, FileSnapshot original, byte[] replacement) {
        var action = new LinkedHashMap<String, Object>();
        action.put("action", replacement == null ? "delete" : original == null ? "create" : "update");
        action.put("file_path", fullPath);
        if (original != null) action.put("last_commit_id", original.lastCommit());
        if (replacement != null) {
            action.put("encoding", "base64");
            action.put("content", Base64.getEncoder().encodeToString(replacement));
        }
        return action;
    }

    private void compensate(Actor actor, String documentId, FileSnapshot original, String created,
                            Pending operation, String message) {
        String head = ref(draftBranch(actor), false);
        // If any subsequent writer exists, fail closed: no attempt to overwrite its work.
        if (!created.equals(head)) throw unavailable();
        Coordination coordination = coordination(actor, head);
        if (coordination.guard() == null || !created.equals(coordination.guard().lastCommit())
                || coordination.record() == null || !created.equals(coordination.record().lastCommit())) throw unavailable();
        FileSnapshot current = file(actor, documentId, head, true);
        if (current != null && !created.equals(current.lastCommit())) throw unavailable();
        var cancelledRecord = new LinkedHashMap<>(coordination.row());
        cancelledRecord.put("status", "cancelled");
        var actions = new ArrayList<Map<String, Object>>();
        actions.add(action(coordinationPath(actor), coordination.guard(), jsonBytes(Map.of("cancelled", operation.scope()))));
        actions.add(action(requestPath(actor, operation), coordination.record(), jsonBytes(cancelledRecord)));
        if (current != null || original != null) actions.add(action(documentPath(actor, documentId), current,
                original == null ? null : original.bytes()));
        var cancelled = object(json(request("POST", projectApi() + "/repository/commits",
                jsonBytes(Map.of("branch", draftBranch(actor), "commit_message", "Cancel stale graph draft mutation\n\n" + message,
                        "actions", actions)), Set.of(201)).body()));
        Object parents = cancelled.get("parent_ids");
        if (!(parents instanceof List<?> list) || list.size() != 1 || !created.equals(list.getFirst())) throw unavailable();
    }

    private boolean committed(Actor actor, String documentId, String fingerprint) {
        String head = ref(draftBranch(actor), true);
        if (head == null) return false;
        Coordination coordination = coordination(actor, head);
        if (coordination.record() == null) return false;
        Map<String, Object> entry = coordination.row();
        if (!fingerprint.equals(string(entry, "fingerprint"))) throw failure(GraphAuthoringException.Failure.CONFLICT);
        if (!"applied".equals(string(entry, "status"))) throw failure(GraphAuthoringException.Failure.CONFLICT);
        State observed = state(actor, documentId);
        if (!string(entry, "release").equals(observed.revision().release())
                || !string(entry, "publication").equals(observed.revision().publication())) {
            // A successful earlier response and a later publication are indistinguishable after
            // restart. Reject replay; never compensate an already superseded/reviewed operation.
            throw failure(GraphAuthoringException.Failure.CONFLICT);
        }
        return true;
    }

    private String ref(String branch, boolean optional) {
        Response response = request("GET", projectApi() + "/repository/branches/" + query(branch), null,
                optional ? Set.of(200, 404) : Set.of(200));
        return response.status() == 404 ? null : validateRevision(string(object(object(json(response.body())).get("commit")), "id"));
    }

    private String projectApi() { return "/projects/" + query(configuration.project()); }

    private Response request(String method, String endpoint, byte[] body, Set<Integer> expected) {
        URI uri = api(endpoint);
        network.requireAllowed(uri);
        if (body != null) network.requireRequestWithinLimit(body);
        int count = requestCount.get() + 1;
        requestCount.set(count);
        if (count > MAX_REQUESTS) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
        String token;
        try (var secret = credentials.resolve(configuration.credentialReference()).orElseThrow()) {
            char[] chars = secret.copy();
            try { token = new String(chars); } finally { java.util.Arrays.fill(chars, '\0'); }
        } catch (RuntimeException failed) { throw failure(GraphAuthoringException.Failure.UNAUTHORIZED); }
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw failure(GraphAuthoringException.Failure.UNAUTHORIZED);
        }
        Response response = send(method, uri, token, body);
        for (int attempt = 0; "GET".equals(method) && attempt < configuration.retryLimit()
                && Set.of(429, 502, 503, 504).contains(response.status()); attempt++) {
            try { Thread.sleep(Math.min(1000L, 100L << attempt)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
            }
            response = send(method, uri, token, body);
        }
        if (!expected.contains(response.status())) {
            throw failure(switch (response.status()) {
                case 401, 403 -> GraphAuthoringException.Failure.UNAUTHORIZED;
                case 404 -> GraphAuthoringException.Failure.NOT_FOUND;
                case 400, 409, 412, 422 -> GraphAuthoringException.Failure.CONFLICT;
                case 413 -> GraphAuthoringException.Failure.LIMIT_EXCEEDED;
                default -> GraphAuthoringException.Failure.UNAVAILABLE;
            });
        }
        return response;
    }

    private Response send(String method, URI uri, String token, byte[] body) {
        try {
            Long deadline = operationDeadline.get();
            long remaining = deadline == null ? configuration.requestTimeout().toNanos() : deadline - System.nanoTime();
            if (remaining <= 0) throw unavailable();
            java.time.Duration timeout = java.time.Duration.ofNanos(Math.min(remaining, configuration.requestTimeout().toNanos()));
            var builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Accept", "application/json")
                    .header("PRIVATE-TOKEN", token);
            if (body != null) builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
            var response = client.send(builder.build(),
                    BoundedHttpResponseBody.upTo(providerResponseLimit, timeout));
            byte[] bytes = response.body();
            if (bytes.length > providerResponseLimit) throw failure(GraphAuthoringException.Failure.LIMIT_EXCEEDED);
            return new Response(response.statusCode(), bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
        } catch (IOException failure) {
            throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE);
        }
    }

    private void beginIdempotency(Actor actor, String key, String operation, String fingerprint) {
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
        String scope = digest(actor.tenantId() + "\n" + actor.subject() + "\n" + operation + "\n" + key);
        pending.set(new Pending(scope, fingerprint));
        String head = ref(draftBranch(actor), true);
        if (head != null) {
            Coordination coordination = coordination(actor, head);
            if (coordination.record() != null && !fingerprint.equals(string(coordination.row(), "fingerprint"))) {
                throw failure(GraphAuthoringException.Failure.CONFLICT);
            }
        }
    }

    private void requireMutation(Actor actor, MutationRequest request, String operation, String source) {
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

    private String draftBranch(Actor actor) {
        String namespace = configuration.tenantNamespaces().get(actor.tenantId());
        if (namespace == null) throw failure(GraphAuthoringException.Failure.UNAUTHORIZED);
        return configuration.draftBranch() + "/" + namespace.replace('/', '-');
    }

    private String documentPath(Actor actor, String documentId) {
        try { return configuration.tenantDirectory(actor.tenantId()) + "/" + documentId; }
        catch (IllegalArgumentException absent) { throw failure(GraphAuthoringException.Failure.UNAUTHORIZED); }
    }

    private URI api(String endpoint) {
        String base = configuration.apiBase().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + endpoint);
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
        if (value == null || !DOCUMENT_ID.matcher(value).matches()) throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT);
    }

    private static int page(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0 || value > 100_000) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) { throw failure(GraphAuthoringException.Failure.INVALID_DOCUMENT); }
    }

    private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }
    private static String path(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/"); }
    private static String query(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String firstLine(String value) { int newline = value.indexOf('\n'); return newline < 0 ? value : value.substring(0, newline); }

    private static String commitMessage(String operation, Actor actor, String key, String fingerprint) {
        return switch (operation) {
            case "delete" -> "Delete graph draft";
            case "restore" -> "Restore graph draft";
            case "discard" -> "Discard graph draft changes";
            default -> "Save graph draft";
        } + "\n\nRavenroot-Authoring-Actor: " + digest(actor.tenantId() + "\n" + actor.subject()).substring(0, 16)
                + "\nRavenroot-Authoring-Operation: " + key + "\nRavenroot-Authoring-Request: " + fingerprint;
    }

    private static String requestFingerprint(Actor actor, String key, String operation, String id, String expected, byte[] bytes) {
        return digest(actor.tenantId() + "\n" + actor.subject() + "\n" + key + "\n" + operation
                + "\n" + id + "\n" + expected + "\n" + digest(bytes));
    }

    private static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private byte[] jsonBytes(Object value) { return PayloadJson.writeJava(value, providerJsonLimits); }
    private Object json(byte[] bytes) {
        try { return PayloadJson.read(bytes, providerJsonLimits).toJava(); }
        catch (RuntimeException invalid) { throw unavailable(); }
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

    private <T> CompletionStage<T> async(java.util.concurrent.Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            operationDeadline.set(System.nanoTime() + configuration.requestTimeout().multipliedBy(configuration.retryLimit() + 1L).toNanos());
            try { return task.call(); }
            catch (GraphAuthoringException classified) { throw classified; }
            catch (Exception failure) { throw new GraphAuthoringException(GraphAuthoringException.Failure.UNAVAILABLE); }
            finally { pending.remove(); requestCount.remove(); operationDeadline.remove(); }
        }, executor);
    }

    private static GraphAuthoringException failure(GraphAuthoringException.Failure failure) {
        return new GraphAuthoringException(failure);
    }
    private static GraphAuthoringException unavailable() { return failure(GraphAuthoringException.Failure.UNAVAILABLE); }

    private record FileSnapshot(String commitSha, String blobSha, byte[] bytes, String lastCommit) {
        private FileSnapshot { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private record Pending(String scope, String fingerprint) { }
    private record Coordination(FileSnapshot guard, FileSnapshot record, Map<String, Object> row) { }
    private record DirectorySnapshot(String commitSha, List<GitEntry> entries) { }
    private record GitEntry(String name, String mode, String type, String sha) {
        boolean directory() { return "040000".equals(mode) && "tree".equals(type); }
        boolean regularBlob() { return ("100644".equals(mode) || "100755".equals(mode)) && "blob".equals(type); }
    }
    private record State(FileSnapshot draft, FileSnapshot release, DocumentSummary summary) {
        Revision revision() { return summary.revision(); }
    }
    private record Response(int status, byte[] body) { }

    /** Keeps the runtime submission namespace unavailable to authored documents. */
    private static final class GraphDefinitionIdentityReserved {
        static boolean isReserved(String graphId) {
            return ai.ravenroot.api.persistence.GraphDefinitionIdentity.SUBMISSION_GRAPH_ID.equals(graphId);
        }
    }
}
