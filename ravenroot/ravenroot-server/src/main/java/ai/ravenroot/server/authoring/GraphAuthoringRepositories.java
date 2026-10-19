package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringRepository;
import ai.ravenroot.api.security.CredentialResolver;
import java.util.function.Consumer;

/** Trusted composition boundary selecting a source archive without leaking provider APIs. */
public final class GraphAuthoringRepositories {
    private GraphAuthoringRepositories() { }

    /** Creates the configured Git source archive with the shared admission boundary. */
    public static GraphAuthoringRepository create(GraphAuthoringConfiguration configuration,
            CredentialResolver credentials, PublicationEvidence publications, Consumer<byte[]> admission) {
        return switch (configuration.provider()) {
            case GITHUB -> new GithubAuthoringRepository(configuration, credentials, publications, admission);
            case GITLAB -> new GitlabAuthoringRepository(configuration, credentials, publications, admission);
            case NONE -> throw new IllegalArgumentException("Git authoring requires a provider");
        };
    }
}
