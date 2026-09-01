package ai.ravenroot.cli;

import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RavenrootCliTest {
    @Test
    void statusUsesTheSharedApplicationApi() {
        var bytes = new ByteArrayOutputStream();
        try (var engine = new PekkoExecutionEngine("ravenroot-cli-test")) {
            var cli = cli(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                    new PrintStream(bytes));

            assertEquals(0, cli.run("status"));
            assertTrue(bytes.toString().contains("execution-engine=apache-pekko"));
        }
    }

    @Test
    void nodeTypesUsesTheSameCatalogAsTheServerAndUi() {
        var bytes = new ByteArrayOutputStream();
        try (var engine = new PekkoExecutionEngine("ravenroot-cli-catalog-test")) {
            var cli = cli(new DefaultRavenrootApplication(engine, new ExecutionMonitor()),
                    new PrintStream(bytes));

            assertEquals(0, cli.run("node-types"));
            String printed = bytes.toString();
            assertTrue(printed.contains("cel-transform"), printed);
            // The kind column, which is `agentic` for an agentic descriptor and the visual type
            // otherwise (see EmbeddedBackend). It used to be asserted as the literal "agentic",
            // satisfied by the core `agent` node; that node left the core, so what is pinned
            // now is that the column is populated from the descriptor at all.
            assertTrue(printed.contains("flow"), printed);
            // The CLI catalog surface requires the same absence
            // RavenrootServerTest asserts on GET /v1/node-types. Both are read from the one
            // BehaviorRegistry, which is what "the same catalog as the server and UI" means here, so
            // asserting it on both is not duplication -- it is the claim of this test's own name.
            assertFalse(printed.contains("llm-prompt"), printed);
            assertFalse(printed.contains("agentic"), printed);
        }
    }

    private static RavenrootCli cli(DefaultRavenrootApplication application, PrintStream output) {
        var authorized = new AuthorizedRavenrootApplication(application,
                new DefaultAuthorizationService(event -> { }), event -> { }, true);
        var context = new RequestContext("test", "test-cli", PrincipalType.USER, "test", "local",
                java.util.Set.of(Role.PLATFORM_ADMIN),
                java.util.Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                        .map(AuthorizationAction::requiredScope)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return new RavenrootCli(authorized, context, output, System.err);
    }
}
