package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;
import ai.ravenroot.server.persistence.SharedExecutionStoreDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Trusted composition boundary selecting a source archive and its owned disposable projection. */
public final class GraphAuthoringRepositories {
    private GraphAuthoringRepositories() {}

    /** Creates an archive using the process deployment persistence configuration. */
    public static GraphAuthoringRepository create(
            GraphAuthoringConfiguration configuration,
            CredentialResolver credentials,
            PublicationEvidence publications,
            Consumer<byte[]> admission) {
        return create(configuration, credentials, publications, admission, System.getenv());
    }

    /**
     * Creates an archive using explicit deployment settings, without opening resources in LOCAL
     * mode.
     */
    public static GraphAuthoringRepository create(
            GraphAuthoringConfiguration configuration,
            CredentialResolver credentials,
            PublicationEvidence publications,
            Consumer<byte[]> admission,
            Map<String, String> environment) {
        if (configuration.provider() == GraphAuthoringConfiguration.Provider.NONE)
            throw new IllegalArgumentException("Git authoring requires a provider");
        return create(
                configuration,
                credentials,
                publications,
                admission,
                ExecutionStoreConfiguration.fromSystem(System.getProperties(), environment));
    }

    /**
     * Creates an archive from a resolved deployment snapshot, without retaining its environment.
     */
    public static GraphAuthoringRepository create(
            GraphAuthoringConfiguration configuration,
            CredentialResolver credentials,
            PublicationEvidence publications,
            Consumer<byte[]> admission,
            ExecutionStoreConfiguration persistence) {
        if (configuration.provider() == GraphAuthoringConfiguration.Provider.NONE)
            throw new IllegalArgumentException("Git authoring requires a provider");
        return create(
                configuration,
                credentials,
                publications,
                admission,
                openIndex(Objects.requireNonNull(persistence, "persistence")));
    }

    /**
     * Creates the managed archive, taking ownership of the supplied disposable index even on
     * failure.
     */
    public static GraphAuthoringRepository create(
            GraphAuthoringConfiguration configuration,
            CredentialResolver credentials,
            PublicationEvidence publications,
            Consumer<byte[]> admission,
            GraphAuthoringReadIndex index) {
        Objects.requireNonNull(index, "index");
        GraphAuthoringRepository repository = null;
        try {
            switch (configuration.provider()) {
                case GITHUB -> {
                    var adapter =
                            new GithubAuthoringRepository(
                                    configuration, credentials, publications, admission);
                    repository = adapter;
                    adapter.useReadIndex(index);
                }
                case GITLAB -> {
                    var adapter =
                            new GitlabAuthoringRepository(
                                    configuration, credentials, publications, admission);
                    repository = adapter;
                    adapter.useReadIndex(index);
                }
                default -> throw new IllegalArgumentException("Git authoring requires a provider");
            }
            return new ReconciledGraphAuthoringRepository(
                    repository, configuration.tenantNamespaces().keySet(), Duration.ofSeconds(60));
        } catch (RuntimeException | Error failure) {
            try {
                if (repository != null) repository.close();
                else index.close();
            } catch (RuntimeException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    /**
     * Signals a managed archive, including managed decorations, after the host has authenticated
     * the provider delivery and mapped its configured project to an authorized tenant. No event
     * payload or revision is trusted by reconciliation.
     */
    public static CompletionStage<Void> signal(GraphAuthoringRepository repository, String tenant) {
        if (repository instanceof ForwardingGraphAuthoringRepository managed) {
            return managed.signal(tenant);
        }
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository has no managed reconciliation receiver"));
    }

    private static GraphAuthoringReadIndex openIndex(ExecutionStoreConfiguration persistence) {
        if (persistence instanceof ExecutionStoreConfiguration.Shared shared) {
            var pool = SharedExecutionStoreDataSource.open(shared.connection());
            try {
                return new PostgresGraphAuthoringReadIndex(pool.dataSource(), pool);
            } catch (RuntimeException | Error failure) {
                pool.close();
                throw failure;
            }
        }
        Path directory =
                persistence instanceof ExecutionStoreConfiguration.SingleHost local
                        ? local.location().directory()
                        : ((ExecutionStoreConfiguration.Disabled) persistence)
                                .location()
                                .directory();
        return new SqliteGraphAuthoringReadIndex(
                directory.resolve("ravenroot-authoring-read-index.db"));
    }
}
