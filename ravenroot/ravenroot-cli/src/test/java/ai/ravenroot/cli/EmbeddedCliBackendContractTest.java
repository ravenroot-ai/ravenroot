package ai.ravenroot.cli;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The embedded half of {@link CliBackendContract} -- today's in-process path, unchanged. */
class EmbeddedCliBackendContractTest extends CliBackendContract {
    /** The two-argument constructor this class used before defaults {@code maxActiveDeployments}
     * to 0 (the deployment-admission contract's fail-closed default), which would refuse every {@code start} this contract's
     * deployment-lifecycle tests issue. The wider constructor is used instead, purely to raise this cap
     * for the test fixture -- nothing else about the composed application changes. */
    private static final int TEST_MAX_ACTIVE_DEPLOYMENTS = 8;

    private PekkoExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PekkoExecutionEngine("dual-transport-embedded-" + UUID.randomUUID());
        var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new InMemoryArtifactRegistry(),
                new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), null,
                TEST_MAX_ACTIVE_DEPLOYMENTS);
        var authorized = new AuthorizedRavenrootApplication(application,
                new DefaultAuthorizationService(event -> { }), event -> { }, true);
        var context = new RequestContext("dual-transport-embedded-request", "embedded-caller",
                PrincipalType.USER, "urn:ravenroot:dual-transport-test", "local", Set.of(Role.PLATFORM_ADMIN),
                Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));
        backend = new EmbeddedBackend(authorized, context);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }
}
