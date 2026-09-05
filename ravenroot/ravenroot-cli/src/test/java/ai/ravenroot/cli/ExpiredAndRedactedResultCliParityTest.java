package ai.ravenroot.cli;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.persistence.ResultPayloadState;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.cli.remote.RemoteBackend;
import ai.ravenroot.server.RavenrootServer;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API-05: {@link EmbeddedBackend} and {@link RemoteBackend} must fail a caller reading an expired or a
 * redacted execution result <em>identically</em>, down to the message text -- the property
 * {@code CliBackendContract} states but, per its own Javadoc, cannot exercise for these two answers
 * without a store-backed fixture neither of its subclasses composes.
 *
 * <p>This is not two backends independently agreeing with a hand-written expectation; it is one canned
 * {@link ExecutionLookup} served by the identical stub {@link RavenrootApplication} to both transports
 * -- one directly through {@link EmbeddedBackend}, the other through a real, running
 * {@link RavenrootServer} and a real {@link RemoteBackend} -- with the two resulting messages compared
 * to each other. A drift between {@code EmbeddedBackend#tombstoneDetail} and
 * {@code RemoteBackend#tombstoneDetail} fails here even if each, read alone, still looked plausible.</p>
 */
class ExpiredAndRedactedResultCliParityTest {

    @Test
    void expiredReadsIdenticallyOnBothTransports() throws Exception {
        UUID executionId = UUID.randomUUID();
        assertIdenticalFailure(new ExecutionLookup.Expired(executionId, ProcessInstanceStatus.FAILED,
                        ExecutionTerminationReason.CANCELLED),
                "EXECUTION_RESULT_EXPIRED");
    }

    @Test
    void withheldRedactedReadsIdenticallyOnBothTransports() throws Exception {
        UUID executionId = UUID.randomUUID();
        assertIdenticalFailure(new ExecutionLookup.Redacted(executionId, ProcessInstanceStatus.COMPLETED,
                        null, ResultPayloadState.WITHHELD),
                "EXECUTION_RESULT_REDACTED");
    }

    @Test
    void unconvertibleRedactedReadsIdenticallyOnBothTransports() throws Exception {
        UUID executionId = UUID.randomUUID();
        assertIdenticalFailure(new ExecutionLookup.Redacted(executionId, ProcessInstanceStatus.FAILED,
                        ExecutionTerminationReason.CANCELLED, ResultPayloadState.UNCONVERTIBLE),
                "EXECUTION_RESULT_REDACTED");
    }

    /** The regression this class exists to close: expired and redacted must not share one message. */
    @Test
    void expiredAndRedactedAreDistinguishableOnEachTransportSeparately() throws Exception {
        var stub = new StubApplication(new ExecutionLookup.Expired(UUID.randomUUID(),
                ProcessInstanceStatus.COMPLETED));
        var redactedLookup = new ExecutionLookup.Redacted(UUID.randomUUID(), ProcessInstanceStatus.COMPLETED,
                null, ResultPayloadState.WITHHELD);

        String embeddedExpired = failureMessage(embeddedBackend(stub), stub.lookup.executionId());
        String embeddedRedacted = failureMessage(embeddedBackend(new StubApplication(redactedLookup)),
                redactedLookup.executionId());
        assertNotEquals(embeddedExpired, embeddedRedacted);

        try (var server = testServer(stub); var redactedServer = testServer(new StubApplication(redactedLookup))) {
            server.start();
            redactedServer.start();
            String remoteExpired = failureMessage(remoteBackend(server), stub.lookup.executionId());
            String remoteRedacted = failureMessage(remoteBackend(redactedServer), redactedLookup.executionId());
            assertNotEquals(remoteExpired, remoteRedacted);
        }
    }

    private static void assertIdenticalFailure(ExecutionLookup lookup, String expectedCode) throws Exception {
        var stub = new StubApplication(lookup);
        String embeddedMessage = failureMessage(embeddedBackend(stub), lookup.executionId());
        assertTrue(embeddedMessage.contains(expectedCode), embeddedMessage);

        try (var server = testServer(stub)) {
            server.start();
            String remoteMessage = failureMessage(remoteBackend(server), lookup.executionId());
            assertEquals(embeddedMessage, remoteMessage,
                    "the embedded and remote transports must fail identically for the same execution");
        }
    }

    private static String failureMessage(CliBackend backend, UUID executionId) {
        IOException failure = assertThrows(IOException.class, () -> backend.result(executionId.toString()));
        return failure.getMessage();
    }

    private static EmbeddedBackend embeddedBackend(RavenrootApplication application) {
        var authorized = new AuthorizedRavenrootApplication(application,
                new DefaultAuthorizationService(event -> { }), event -> { }, true);
        var context = new RequestContext("cli-parity-request", "cli-parity-caller", PrincipalType.USER,
                "urn:ravenroot:cli-parity-test", "local", Set.of(Role.PLATFORM_ADMIN),
                Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));
        return new EmbeddedBackend(authorized, context);
    }

    private static RavenrootServer testServer(RavenrootApplication application) {
        return new RavenrootServer(application, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                null, new DisabledLoopbackAuthenticator());
    }

    private static RemoteBackend remoteBackend(RavenrootServer server) {
        return new RemoteBackend(URI.create("http://localhost:" + server.port() + "/"),
                "cli-parity-placeholder-token", Duration.ofSeconds(10));
    }

    /**
     * The minimal {@link RavenrootApplication} needed to answer one canned {@link ExecutionLookup},
     * copied from {@code RedactedExecutionResultHttpTest} in {@code ravenroot-server} rather than
     * shared across modules -- {@code ravenroot-cli}'s test sources cannot reach another module's test
     * classes, and this codebase's existing convention is a private stub per test file rather than a
     * shared test-jar for one small fixture.
     */
    private static final class StubApplication implements RavenrootApplication {
        final ExecutionLookup lookup;

        StubApplication(ExecutionLookup lookup) {
            this.lookup = lookup;
        }

        @Override
        public ExecutionLookup executionResult(String tenantId, UUID executionId) {
            return lookup;
        }

        @Override
        public ApplicationStatus status() {
            return new ApplicationStatus("RUNNING", "stub", Set.of());
        }

        @Override
        public RuntimeSnapshot runtimeSnapshot() {
            return new RuntimeSnapshot(0, Map.of());
        }

        @Override
        public List<NodeTypeDescriptor> nodeTypes() {
            return List.of();
        }

        @Override
        public List<GeneratedArtifact> programArtifacts() {
            return List.of();
        }

        @Override
        public GeneratedArtifact createProgramArtifact(String language, String source,
                                                       Map<String, String> metadata) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> trustedEvidence) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public GraphSummary inspectGraphMl(InputStream graphMl) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public ai.ravenroot.api.application.ExecutionSubmission startGraphMl(SecurityContext security,
                                                                             UUID executionId,
                                                                             InputStream graphMl, Object payload) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
            return () -> { };
        }

        @Override
        public List<ExecutionEvent> executionEventsAfter(long sequence) {
            return List.of();
        }

        @Override
        public boolean durableEventJournalAvailable() {
            return false;
        }

        @Override
        public List<ai.ravenroot.api.application.DurableExecutionEvent> durableEventsAfter(String tenantId,
                                                                                            long afterOffset,
                                                                                            int limit) {
            throw new UnsupportedOperationException("not exercised by this stub");
        }

        @Override
        public void close() {
        }
    }
}
