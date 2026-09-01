package ai.ravenroot.server.payload;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.payload.PayloadEnvelope;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.testkit.api.PayloadTransportContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The embedded half of API-01: the contract as seen by a host that calls the application API
 * directly, with no transport in between.
 *
 * <p>Binding the same contract here is what proves the payload guarantees are properties of the
 * application rather than of the HTTP adapter. An embedded host never parses a document, so if the
 * limits lived only in the decoder it would receive none of them — and it is exactly the surface where
 * a caller can construct an arbitrarily large value in memory with no network in the way.</p>
 */
class EmbeddedPayloadContractTest extends PayloadTransportContract {
    private PekkoExecutionEngine engine;
    private DefaultRavenrootApplication application;
    private AuthorizedRavenrootApplication authorized;
    private PayloadObservationHarness harness;

    @BeforeEach
    void start() {
        harness = new PayloadObservationHarness();
        engine = new PekkoExecutionEngine("ravenroot-embedded-payload-" + UUID.randomUUID());
        application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(), harness.registry());
        authorized = new AuthorizedRavenrootApplication(application,
                new DefaultAuthorizationService(event -> {
                }), event -> {
                }, false);
    }

    @AfterEach
    void stop() {
        application.close();
        engine.close();
    }

    @Override
    protected PayloadValue submit(PayloadEnvelope envelope) throws Exception {
        harness.reset();
        authorized.startGraphMl(context(), graph(), envelope);
        return observed();
    }

    @Override
    protected PayloadValue submitLegacyText(String text) throws Exception {
        harness.reset();
        // Deliberately the Object overload: this is the call an existing embedded host already makes,
        // unchanged. If compatibility held only through the new overload it would not hold at all.
        authorized.startGraphMl(context(), graph(), (Object) text);
        return observed();
    }

    @Override
    protected String submitExpectingRejection(PayloadEnvelope envelope) {
        harness.reset();
        PayloadException rejection = assertThrows(PayloadException.class,
                () -> authorized.startGraphMl(context(), graph(), envelope));
        return rejection.code();
    }

    private PayloadValue observed() throws InterruptedException {
        return PayloadValue.fromJava(harness.awaitPayload(), limits());
    }

    private static ByteArrayInputStream graph() {
        return new ByteArrayInputStream(PayloadObservationHarness.GRAPH.getBytes(StandardCharsets.UTF_8));
    }

    private static RequestContext context() {
        return new RequestContext(UUID.randomUUID().toString(), "embedded-host", PrincipalType.WORKLOAD,
                "urn:ravenroot:embedded", "tenant-embedded", Set.of(Role.PLATFORM_ADMIN),
                Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));
    }
}
