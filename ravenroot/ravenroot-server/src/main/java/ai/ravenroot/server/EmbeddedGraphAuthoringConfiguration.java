package ai.ravenroot.server;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.server.authoring.GraphAuthoringConfiguration;
import ai.ravenroot.server.authoring.GraphAuthoringRepositories;
import ai.ravenroot.server.authoring.PublicationEvidence;
import ai.ravenroot.server.authoring.PublishedGraphArtifactCatalog;
import ai.ravenroot.server.persistence.ExecutionStoreConfiguration;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Explicit embedded-server opt-in for the shipped Git graph-authoring composition.
 *
 * <p>{@link #fromEnvironment} reads the same repository, provider, artifact and
 * credential-reference settings as {@link RavenrootServerMain}. Credential material remains behind
 * the supplied resolver; this object never stores it. A local-mode configuration is inert,
 * preserving the behavior of every existing {@link RavenrootServer} constructor.
 */
public final class EmbeddedGraphAuthoringConfiguration {
    private static final CredentialResolver NO_CREDENTIALS = ignored -> Optional.empty();

    private final GraphAuthoringConfiguration configuration;
    private final CredentialResolver credentials;
    private final RepositoryFactory repositories;
    private final CatalogFactory catalogs;

    private EmbeddedGraphAuthoringConfiguration(GraphAuthoringConfiguration configuration,
            CredentialResolver credentials, RepositoryFactory repositories, CatalogFactory catalogs) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    /** Keeps Git authoring and immutable publication routes disabled. */
    public static EmbeddedGraphAuthoringConfiguration disabled() {
        return new EmbeddedGraphAuthoringConfiguration(GraphAuthoringConfiguration.local(),
                NO_CREDENTIALS, GraphAuthoringRepositories::create, PublishedGraphArtifactCatalog::new);
    }

    /**
     * Resolves the existing graph-authoring settings for an embedded server.
     *
     * @param environment operator-owned configuration values
     * @param credentials trusted resolver for the configured opaque credential reference
     * @return an inert configuration for LOCAL mode or an explicit Git composition for GIT mode
     */
    public static EmbeddedGraphAuthoringConfiguration fromEnvironment(
            Map<String, String> environment, CredentialResolver credentials) {
        GraphAuthoringConfiguration authoring =
                GraphAuthoringConfiguration.fromEnvironment(environment);
        ExecutionStoreConfiguration persistence =
                authoring.mode() == GraphAuthoringConfiguration.Mode.LOCAL
                        ? null
                        : ExecutionStoreConfiguration.fromSystem(
                                System.getProperties(), environment);
        return gitOrLocal(
                authoring,
                credentials,
                (configuration, resolver, publications, admission) ->
                        GraphAuthoringRepositories.create(
                                configuration, resolver, publications, admission, persistence),
                PublishedGraphArtifactCatalog::new);
    }

    /**
     * Uses an already validated authoring configuration with the host's trusted credential
     * resolver.
     */
    public static EmbeddedGraphAuthoringConfiguration of(GraphAuthoringConfiguration configuration,
            CredentialResolver credentials) {
        return gitOrLocal(configuration, credentials, GraphAuthoringRepositories::create,
                PublishedGraphArtifactCatalog::new);
    }

    /**
     * Decorates the managed archive while retaining admission, publication and resource ownership.
     * The decorator receives a non-owning view; its own close hook and the archive close run once.
     */
    public EmbeddedGraphAuthoringConfiguration decorateRepository(
            java.util.function.UnaryOperator<GraphAuthoringRepository> decorator) {
        Objects.requireNonNull(decorator, "decorator");
        return gitOrLocal(
                configuration,
                credentials,
                (configuration, resolver, publications, admission) ->
                        ai.ravenroot.server.authoring.ForwardingGraphAuthoringRepository.managed(
                                repositories.create(
                                        configuration, resolver, publications, admission),
                                decorator),
                catalogs);
    }

    static EmbeddedGraphAuthoringConfiguration forTest(GraphAuthoringConfiguration configuration,
            CredentialResolver credentials, RepositoryFactory repositories, CatalogFactory catalogs) {
        return gitOrLocal(configuration, credentials, repositories, catalogs);
    }

    private static EmbeddedGraphAuthoringConfiguration gitOrLocal(GraphAuthoringConfiguration configuration,
            CredentialResolver credentials, RepositoryFactory repositories, CatalogFactory catalogs) {
        Objects.requireNonNull(configuration, "configuration");
        return configuration.mode() == GraphAuthoringConfiguration.Mode.LOCAL
                ? disabled()
                : new EmbeddedGraphAuthoringConfiguration(configuration,
                        Objects.requireNonNull(credentials, "credentials"), repositories, catalogs);
    }

    boolean enabled() {
        return configuration.mode() == GraphAuthoringConfiguration.Mode.GIT;
    }

    GraphAuthoringConfiguration configuration() {
        return configuration;
    }

    GraphAuthoringRepository createRepository(PublicationEvidence publications, Consumer<byte[]> admission) {
        return repositories.create(configuration, credentials, publications, admission);
    }

    PublishedGraphArtifactCatalog createCatalog() {
        return catalogs.create(configuration);
    }

    void requireCredentialAvailable() {
        if (!enabled()) return;
        try {
            var resolved = credentials.resolve(configuration.credentialReference());
            if (resolved.isEmpty()) throw unavailableCredential();
            try (var secret = resolved.orElseThrow()) {
                char[] copy = secret.copy();
                try {
                    boolean blank = true;
                    for (char value : copy) blank &= Character.isWhitespace(value);
                    if (blank) throw unavailableCredential();
                } finally {
                    Arrays.fill(copy, '\0');
                }
            }
        } catch (RuntimeException failure) {
            throw unavailableCredential();
        }
    }

    private static IllegalStateException unavailableCredential() {
        return new IllegalStateException("Git graph authoring credential is unavailable");
    }

    @FunctionalInterface
    interface RepositoryFactory {
        GraphAuthoringRepository create(GraphAuthoringConfiguration configuration,
                CredentialResolver credentials, PublicationEvidence publications, Consumer<byte[]> admission);
    }

    @FunctionalInterface
    interface CatalogFactory {
        PublishedGraphArtifactCatalog create(GraphAuthoringConfiguration configuration);
    }
}
