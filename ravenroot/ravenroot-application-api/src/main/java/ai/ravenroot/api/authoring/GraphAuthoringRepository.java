package ai.ravenroot.api.authoring;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Provider-neutral, tenant-confined source archive for GraphML authoring.
 *
 * <p>This port owns source revisions and reviewed release proposals. It is deliberately separate
 * from runtime graph-definition persistence: drafts are mutable Git history while a
 * {@code GraphDefinitionStore} binds immutable executable bytes.</p>
 */
public interface GraphAuthoringRepository extends AutoCloseable {

    /** Operations a configured provider supports. */
    enum Capability { LIST, OPEN, CREATE, SAVE, HISTORY, DIFF, RESTORE, DELETE, DISCARD, RELEASE_PROPOSAL }

    /** Authenticated owner of one operation. The repository maps the tenant to a trusted namespace. */
    record Actor(String tenantId, String subject) {
        public Actor {
            tenantId = requireText(tenantId, "tenantId");
            subject = requireText(subject, "subject");
        }
    }

    /** Opaque concurrency state spanning draft, release and publication observations. */
    record Revision(String draft, String release, String publication) {
        public Revision {
            draft = requireText(draft, "draft");
            release = requireText(release, "release");
            publication = requireText(publication, "publication");
        }
    }

    /** Browser-safe source status. */
    record DocumentSummary(String documentId, String displayPath, String graphId, long releaseVersion,
                           Revision revision, boolean released, boolean published, boolean draftDeleted) {
        public DocumentSummary {
            documentId = requireText(documentId, "documentId");
            displayPath = requireText(displayPath, "displayPath");
            graphId = requireText(graphId, "graphId");
            if (releaseVersion < 1) throw new IllegalArgumentException("releaseVersion must be positive");
            Objects.requireNonNull(revision, "revision");
        }
    }

    /** Exact supported GraphML plus its trusted source status. */
    record Document(DocumentSummary summary, byte[] graphMl) {
        public Document {
            Objects.requireNonNull(summary, "summary");
            graphMl = Objects.requireNonNull(graphMl, "graphMl").clone();
        }
        @Override public byte[] graphMl() { return graphMl.clone(); }
    }

    /** One bounded page. */
    record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            nextCursor = nextCursor == null ? "" : nextCursor;
        }
    }

    /** One file-scoped commit projection. */
    record HistoryEntry(String revision, String summary, String author, Instant authoredAt) {
        public HistoryEntry {
            revision = requireText(revision, "revision");
            summary = requireText(summary, "summary");
            author = requireText(author, "author");
            Objects.requireNonNull(authoredAt, "authoredAt");
        }
    }

    /** Bounded textual comparison of two immutable source revisions. */
    record Difference(String fromRevision, String toRevision, String unifiedDiff, boolean truncated) {
        public Difference {
            fromRevision = requireText(fromRevision, "fromRevision");
            toRevision = requireText(toRevision, "toRevision");
            unifiedDiff = Objects.requireNonNull(unifiedDiff, "unifiedDiff");
        }
    }

    /** Save/create request. Expected state is mandatory for every mutation. */
    record SaveRequest(String documentId, byte[] graphMl, Revision expected, String idempotencyKey) {
        public SaveRequest {
            documentId = requireText(documentId, "documentId");
            graphMl = Objects.requireNonNull(graphMl, "graphMl").clone();
            Objects.requireNonNull(expected, "expected");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }
        @Override public byte[] graphMl() { return graphMl.clone(); }
    }

    /** File mutation whose content is derived from repository history. */
    record MutationRequest(String documentId, String sourceRevision, Revision expected,
                           String idempotencyKey) {
        public MutationRequest {
            documentId = requireText(documentId, "documentId");
            sourceRevision = sourceRevision == null ? "" : sourceRevision;
            Objects.requireNonNull(expected, "expected");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }
    }

    /** Reviewed release proposal; this concerns the configured graph repository only. */
    record ReleaseProposal(String url, String draftHead, String releaseHead, boolean reused) {
        public ReleaseProposal {
            url = requireText(url, "url");
            draftHead = requireText(draftHead, "draftHead");
            releaseHead = requireText(releaseHead, "releaseHead");
        }
    }

    Set<Capability> capabilities();
    CompletionStage<Page<DocumentSummary>> list(Actor actor, String cursor);
    CompletionStage<Document> open(Actor actor, String documentId, boolean releasedSource);
    CompletionStage<Document> save(Actor actor, SaveRequest request);
    CompletionStage<Page<HistoryEntry>> history(Actor actor, String documentId, String cursor);
    CompletionStage<Difference> diff(Actor actor, String documentId, String fromRevision, String toRevision);
    CompletionStage<Document> restore(Actor actor, MutationRequest request);
    CompletionStage<Void> deleteDraft(Actor actor, MutationRequest request);
    CompletionStage<Document> discardDraft(Actor actor, MutationRequest request);
    CompletionStage<ReleaseProposal> proposeRelease(Actor actor, MutationRequest request);

    @Override default void close() { }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }
}
