package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Supported base for trusted embedded decorators. Closing a view does not close its managed
 * delegate.
 */
public class ForwardingGraphAuthoringRepository implements GraphAuthoringRepository {
    protected final GraphAuthoringRepository delegate;

    public ForwardingGraphAuthoringRepository(GraphAuthoringRepository delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public Set<Capability> capabilities() {
        return delegate.capabilities();
    }

    @Override
    public CompletionStage<Page<DocumentSummary>> list(Actor actor, String cursor) {
        return delegate.list(actor, cursor);
    }

    @Override
    public CompletionStage<Document> open(Actor actor, String documentId, boolean released) {
        return delegate.open(actor, documentId, released);
    }

    @Override
    public CompletionStage<Document> save(Actor actor, SaveRequest request) {
        return delegate.save(actor, request);
    }

    @Override
    public CompletionStage<Document> createNextVersion(Actor actor, MutationRequest request) {
        return delegate.createNextVersion(actor, request);
    }

    @Override
    public CompletionStage<Page<HistoryEntry>> history(
            Actor actor, String documentId, String cursor) {
        return delegate.history(actor, documentId, cursor);
    }

    @Override
    public CompletionStage<Difference> diff(
            Actor actor, String documentId, String from, String to) {
        return delegate.diff(actor, documentId, from, to);
    }

    @Override
    public CompletionStage<Document> restore(Actor actor, MutationRequest request) {
        return delegate.restore(actor, request);
    }

    @Override
    public CompletionStage<Void> deleteDraft(Actor actor, MutationRequest request) {
        return delegate.deleteDraft(actor, request);
    }

    @Override
    public CompletionStage<Document> discardDraft(Actor actor, MutationRequest request) {
        return delegate.discardDraft(actor, request);
    }

    @Override
    public CompletionStage<ReleaseProposal> proposeRelease(Actor actor, MutationRequest request) {
        return delegate.proposeRelease(actor, request);
    }

    /** Forwards a trusted recovery hint without granting webhook authentication authority. */
    public CompletionStage<Void> signal(String tenant) {
        return GraphAuthoringRepositories.signal(delegate, tenant);
    }

    @Override
    public void close() {}

    /**
     * Applies a decoration while retaining ownership of both resources, including construction
     * failures.
     */
    public static GraphAuthoringRepository managed(
            GraphAuthoringRepository repository,
            UnaryOperator<GraphAuthoringRepository> decorator) {
        GraphAuthoringRepository decorated;
        try {
            decorated =
                    Objects.requireNonNull(
                            decorator.apply(new ForwardingGraphAuthoringRepository(repository)),
                            "decorated repository");
        } catch (RuntimeException | Error failure) {
            try {
                repository.close();
            } catch (RuntimeException | Error close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
        return new ForwardingGraphAuthoringRepository(decorated) {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public CompletionStage<Void> signal(String tenant) {
                return GraphAuthoringRepositories.signal(repository, tenant);
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) return;
                Throwable first = null;
                try {
                    decorated.close();
                } catch (RuntimeException | Error failure) {
                    first = failure;
                }
                try {
                    repository.close();
                } catch (RuntimeException | Error failure) {
                    if (first == null) first = failure;
                    else first.addSuppressed(failure);
                }
                if (first instanceof RuntimeException failure) throw failure;
                if (first instanceof Error failure) throw failure;
            }
        };
    }
}
