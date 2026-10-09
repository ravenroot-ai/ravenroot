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

    /** Operations exposed by a configured source-archive provider. */
    enum Capability {
        /** Enumerates tenant-owned graph documents. */
        LIST,
        /** Reads an immutable source revision. */
        OPEN,
        /** Creates a tenant-owned draft. */
        CREATE,
        /** Persists a changed draft. */
        SAVE,
        /** Enumerates file-scoped source revisions. */
        HISTORY,
        /** Compares two immutable source revisions. */
        DIFF,
        /** Replaces a draft with bytes from an earlier revision. */
        RESTORE,
        /** Removes a draft document while retaining repository history. */
        DELETE,
        /** Resets a draft to its reviewed release. */
        DISCARD,
        /** Opens or reuses a provider review proposal. */
        RELEASE_PROPOSAL
    }

    /**
     * Authenticated owner of one operation. The repository maps the tenant to a trusted namespace.
     *
     * @param tenantId authenticated tenant whose configured namespace confines repository access
     * @param subject authenticated principal used to scope idempotency and audit attribution
     */
    record Actor(String tenantId, String subject) {
        /**
         * Validates an authenticated repository actor.
         *
         * @param tenantId authenticated tenant whose namespace confines access
         * @param subject authenticated principal used for idempotency and audit attribution
         */
        public Actor {
            tenantId = requireText(tenantId, "tenantId");
            subject = requireText(subject, "subject");
        }
    }

    /**
     * Opaque concurrency state spanning draft, release, and publication observations.
     *
     * @param draft provider revision observed for the tenant draft
     * @param release provider revision observed for the reviewed release branch
     * @param publication immutable artifact-publication token observed for this document
     */
    record Revision(String draft, String release, String publication) {
        /**
         * Validates a complete optimistic-concurrency observation.
         *
         * @param draft provider revision observed for the tenant draft
         * @param release provider revision observed for the reviewed release branch
         * @param publication immutable artifact-publication token observed for the document
         */
        public Revision {
            draft = requireText(draft, "draft");
            release = requireText(release, "release");
            publication = requireText(publication, "publication");
        }
    }

    /**
     * Browser-safe source status with no provider credentials or repository-internal path.
     *
     * @param documentId opaque tenant-scoped document identifier accepted by later operations
     * @param displayPath non-sensitive path label suitable for an authoring client
     * @param graphId stable authored graph identifier carried by the GraphML
     * @param releaseVersion positive authored release version carried by the GraphML
     * @param revision complete optimistic-concurrency state for the document
     * @param released whether reviewed release bytes exist for this graph
     * @param published whether immutable publication evidence exists for the release
     * @param draftDeleted whether the draft is absent while retained release bytes remain
     */
    record DocumentSummary(String documentId, String displayPath, String graphId, long releaseVersion,
                           Revision revision, boolean released, boolean published, boolean draftDeleted) {
        /**
         * Validates browser-safe identity, version, and source state.
         *
         * @param documentId opaque tenant-scoped identifier
         * @param displayPath non-sensitive authoring label
         * @param graphId stable authored graph identifier
         * @param releaseVersion positive authored release version
         * @param revision optimistic-concurrency state
         * @param released whether reviewed release bytes exist
         * @param published whether immutable publication evidence exists
         * @param draftDeleted whether only retained release bytes remain
         */
        public DocumentSummary {
            documentId = requireText(documentId, "documentId");
            displayPath = requireText(displayPath, "displayPath");
            graphId = requireText(graphId, "graphId");
            if (releaseVersion < 1) throw new IllegalArgumentException("releaseVersion must be positive");
            Objects.requireNonNull(revision, "revision");
        }
    }

    /**
     * Exact supported GraphML plus its trusted source status.
     *
     * @param summary source identity, version, and concurrency state derived by the repository
     * @param graphMl canonical GraphML bytes; the value is defensively copied
     */
    record Document(DocumentSummary summary, byte[] graphMl) {
        /**
         * Captures source state and defensively copies its canonical bytes.
         *
         * @param summary trusted source identity, version, and concurrency state
         * @param graphMl canonical GraphML bytes
         */
        public Document {
            Objects.requireNonNull(summary, "summary");
            graphMl = Objects.requireNonNull(graphMl, "graphMl").clone();
        }
        /**
         * Returns canonical GraphML without exposing mutable record storage.
         *
         * @return defensive copy of the canonical bytes
         */
        @Override public byte[] graphMl() { return graphMl.clone(); }
    }

    /**
     * One bounded page from a tenant-scoped listing.
     *
     * @param <T> listed projection type
     * @param items immutable items in provider order
     * @param nextCursor opaque continuation cursor, or an empty string at the end
     */
    record Page<T>(List<T> items, String nextCursor) {
        /**
         * Copies page items and normalizes an absent continuation to an empty string.
         *
         * @param items items in provider order
         * @param nextCursor opaque continuation or {@code null} at the end
         */
        public Page {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            nextCursor = nextCursor == null ? "" : nextCursor;
        }
    }

    /**
     * One file-scoped commit projection with browser-safe author metadata.
     *
     * @param revision immutable provider revision accepted by open, diff, or restore
     * @param summary provider commit summary with sensitive response fields excluded
     * @param author display attribution supplied by the source archive
     * @param authoredAt provider timestamp for the revision
     */
    record HistoryEntry(String revision, String summary, String author, Instant authoredAt) {
        /**
         * Validates one browser-safe file revision projection.
         *
         * @param revision immutable provider revision
         * @param summary provider commit summary
         * @param author provider display attribution
         * @param authoredAt provider timestamp
         */
        public HistoryEntry {
            revision = requireText(revision, "revision");
            summary = requireText(summary, "summary");
            author = requireText(author, "author");
            Objects.requireNonNull(authoredAt, "authoredAt");
        }
    }

    /**
     * Bounded textual comparison of two immutable source revisions.
     *
     * @param fromRevision immutable base revision
     * @param toRevision immutable target revision
     * @param unifiedDiff provider-neutral unified text diff
     * @param truncated whether the repository omitted diff content at its response ceiling
     */
    record Difference(String fromRevision, String toRevision, String unifiedDiff, boolean truncated) {
        /**
         * Validates immutable endpoints and bounded diff text.
         *
         * @param fromRevision immutable base revision
         * @param toRevision immutable target revision
         * @param unifiedDiff provider-neutral unified comparison
         * @param truncated whether content was omitted at the response ceiling
         */
        public Difference {
            fromRevision = requireText(fromRevision, "fromRevision");
            toRevision = requireText(toRevision, "toRevision");
            unifiedDiff = Objects.requireNonNull(unifiedDiff, "unifiedDiff");
        }
    }

    /**
     * Save or create request with mandatory optimistic-concurrency state.
     *
     * @param documentId opaque tenant-scoped document identifier
     * @param graphMl proposed GraphML bytes; the value is defensively copied
     * @param expected draft, release, and publication state observed by the caller
     * @param idempotencyKey caller-generated retry key scoped to the actor and request fingerprint
     */
    record SaveRequest(String documentId, byte[] graphMl, Revision expected, String idempotencyKey) {
        /**
         * Validates mutation state and defensively copies proposed bytes.
         *
         * @param documentId opaque tenant-scoped identifier
         * @param graphMl proposed GraphML bytes
         * @param expected caller-observed optimistic-concurrency state
         * @param idempotencyKey caller-generated bounded retry key
         */
        public SaveRequest {
            documentId = requireText(documentId, "documentId");
            graphMl = Objects.requireNonNull(graphMl, "graphMl").clone();
            Objects.requireNonNull(expected, "expected");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }
        /**
         * Returns proposed GraphML without exposing mutable record storage.
         *
         * @return defensive copy of the proposed bytes
         */
        @Override public byte[] graphMl() { return graphMl.clone(); }
    }

    /**
     * File mutation whose content is derived from repository history.
     *
     * @param documentId opaque tenant-scoped document identifier
     * @param sourceRevision immutable source revision used by restore, or empty when inapplicable
     * @param expected draft, release, and publication state observed by the caller
     * @param idempotencyKey caller-generated retry key scoped to the actor and request fingerprint
     */
    record MutationRequest(String documentId, String sourceRevision, Revision expected,
                           String idempotencyKey) {
        /**
         * Validates mutation state and normalizes an absent historical source.
         *
         * @param documentId opaque tenant-scoped identifier
         * @param sourceRevision immutable history source, or {@code null} when inapplicable
         * @param expected caller-observed optimistic-concurrency state
         * @param idempotencyKey caller-generated bounded retry key
         */
        public MutationRequest {
            documentId = requireText(documentId, "documentId");
            sourceRevision = sourceRevision == null ? "" : sourceRevision;
            Objects.requireNonNull(expected, "expected");
            idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        }
    }

    /**
     * Reviewed release proposal concerning the configured graph repository only.
     *
     * @param url provider review URL safe to present to the authenticated actor
     * @param draftHead exact draft commit proposed for review
     * @param releaseHead exact reviewed-branch commit used as the proposal base
     * @param reused whether an existing proposal with the same immutable heads was returned
     */
    record ReleaseProposal(String url, String draftHead, String releaseHead, boolean reused) {
        /**
         * Validates the exact immutable heads bound to a review URL.
         *
         * @param url provider review URL
         * @param draftHead proposed draft commit
         * @param releaseHead reviewed-branch base commit
         * @param reused whether an existing exact-head proposal was returned
         */
        public ReleaseProposal {
            url = requireText(url, "url");
            draftHead = requireText(draftHead, "draftHead");
            releaseHead = requireText(releaseHead, "releaseHead");
        }
    }

    /**
     * Returns the operations implemented by this configured adapter.
     *
     * @return immutable provider capability set
     */
    Set<Capability> capabilities();

    /**
     * Lists source documents owned by the actor's configured tenant namespace.
     *
     * @param actor authenticated repository actor
     * @param cursor opaque continuation cursor, or an empty string for the first page
     * @return stage yielding one bounded page of browser-safe summaries
     */
    CompletionStage<Page<DocumentSummary>> list(Actor actor, String cursor);

    /**
     * Opens canonical bytes from the current draft or reviewed release.
     *
     * @param actor authenticated repository actor
     * @param documentId tenant-scoped identifier returned by a listing
     * @param releasedSource {@code true} to select reviewed release bytes, {@code false} for draft bytes
     * @return stage yielding exact GraphML and the concurrency state used to read it
     */
    CompletionStage<Document> open(Actor actor, String documentId, boolean releasedSource);

    /**
     * Validates and persists a draft through an expected-parent provider update.
     *
     * @param actor authenticated repository actor
     * @param request proposed bytes, expected state, and bounded idempotency key
     * @return stage yielding server-canonical GraphML and the advanced source state
     */
    CompletionStage<Document> save(Actor actor, SaveRequest request);

    /**
     * Lists immutable file revisions for a tenant-owned document.
     *
     * @param actor authenticated repository actor
     * @param documentId tenant-scoped identifier returned by a listing
     * @param cursor opaque continuation cursor, or an empty string for the first page
     * @return stage yielding one bounded history page
     */
    CompletionStage<Page<HistoryEntry>> history(Actor actor, String documentId, String cursor);

    /**
     * Compares two immutable revisions of a tenant-owned document.
     *
     * @param actor authenticated repository actor
     * @param documentId tenant-scoped identifier returned by a listing
     * @param fromRevision immutable base revision from history
     * @param toRevision immutable target revision from history
     * @return stage yielding a bounded unified comparison
     */
    CompletionStage<Difference> diff(Actor actor, String documentId, String fromRevision, String toRevision);

    /**
     * Restores historical GraphML as a new draft revision after stale-state checks.
     *
     * @param actor authenticated repository actor
     * @param request historical source revision, expected state, and idempotency key
     * @return stage yielding the restored canonical draft and its new source state
     */
    CompletionStage<Document> restore(Actor actor, MutationRequest request);

    /**
     * Deletes the current draft while preserving repository history.
     *
     * @param actor authenticated repository actor
     * @param request expected state and idempotency key for the deletion
     * @return stage completing after the provider ref advances to the deletion commit
     */
    CompletionStage<Void> deleteDraft(Actor actor, MutationRequest request);

    /**
     * Replaces a changed draft with its reviewed release after stale-state checks.
     *
     * @param actor authenticated repository actor
     * @param request expected state and idempotency key for the discard
     * @return stage yielding the canonical replacement draft and advanced source state
     */
    CompletionStage<Document> discardDraft(Actor actor, MutationRequest request);

    /**
     * Opens or reuses review for the exact current draft and release heads.
     *
     * @param actor authenticated repository actor
     * @param request expected state and idempotency key for the proposal
     * @return stage yielding the provider review URL and immutable head bindings
     */
    CompletionStage<ReleaseProposal> proposeRelease(Actor actor, MutationRequest request);

    @Override default void close() { }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }
}
