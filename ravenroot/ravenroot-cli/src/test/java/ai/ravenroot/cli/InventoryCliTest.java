package ai.ravenroot.cli;

import ai.ravenroot.api.application.AuthorizedRavenrootApplication;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.RequestContext;
import ai.ravenroot.api.security.Role;
import ai.ravenroot.core.persistence.InMemoryExecutionStore;
import ai.ravenroot.core.programming.DisabledProgramRuntime;
import ai.ravenroot.core.programming.InMemoryArtifactRegistry;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultRavenrootApplication;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.pekko.PekkoExecutionEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Embedded-only, mirroring {@link LiveExecutionsCliTest}'s own shape and reason: proving the durable
 * inventory's actual content over {@code ravenroot inventory}/{@code ravenroot traversals} needs a
 * store-backed {@link DefaultRavenrootApplication}, a seam {@link CliBackendContract}'s generic
 * fixture does not expose (both its subclasses compose with no store at all -- see
 * {@link CliBackendContract#bothTransportsRefuseTheInventoryReadIdenticallyWhenNoDurableStoreIsComposed}
 * for the parity that contract proves instead).
 *
 * <p>{@code ravenroot inventory}'s output is parsed for the {@code process-instance-id=} column, the
 * way an operator would use it: never the {@code processInstanceId} {@code run} itself printed, so a
 * green test is itself the evidence that {@code inventory} discovers the row on its own.</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class InventoryCliTest {

    @TempDir
    Path workingDirectory;

    private static final Pattern PROCESS_INSTANCE_ID_LINE = Pattern.compile("process-instance-id=(\\S+)");

    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="node-kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="edge-outcome" for="edge" attr.name="outcome" attr.type="string"/>
              <graph id="inventory-cli-test" edgedefault="directed">
                <node id="error"><data key="node-kind">ERROR</data></node>
                <node id="start"><data key="node-kind">START</data></node>
                <node id="end"><data key="node-kind">END</data></node>
                <edge id="start-end" source="start" target="end">
                  <data key="edge-outcome">continue</data>
                </edge>
              </graph>
            </graphml>
            """;

    @Test
    void aCompletedInstanceIsDiscoveredByInventoryAndItsTraversalsListedByProcessInstanceId() throws Exception {
        Path graphFile = workingDirectory.resolve("wiring.graphml");
        Files.writeString(graphFile, GRAPH, StandardCharsets.UTF_8);

        try (var engine = new PekkoExecutionEngine("inventory-cli-test-" + UUID.randomUUID());
             var store = new InMemoryExecutionStore()) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store);
            var authorized = new AuthorizedRavenrootApplication(application,
                    new DefaultAuthorizationService(event -> { }), event -> { }, true);
            var context = new RequestContext("inventory-cli-test-request", "inventory-cli-test-caller",
                    PrincipalType.USER, "urn:ravenroot:inventory-cli-test", "local", Set.of(Role.PLATFORM_ADMIN),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));

            var runOutput = new ByteArrayOutputStream();
            assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(runOutput), System.err)
                    .run("run", graphFile.toString(), "payload"));

            // 'run' acknowledges submission and returns; the traversal completes on the engine's own
            // dispatcher after that (the same asynchronous boundary CliBackendContract#awaitTerminalResult
            // polls across via 'result', and LiveExecutionsCliTest's hang fixture demonstrates directly),
            // so this polls 'inventory' itself -- the source under test -- until the row reaches COMPLETED.
            String inventoryListing = pollUntilCompleted(authorized, context);
            Matcher matcher = PROCESS_INSTANCE_ID_LINE.matcher(inventoryListing);
            if (!matcher.find()) {
                fail("no process-instance-id= line in 'inventory' output for a run that must have "
                        + "completed by now: " + inventoryListing);
            }
            String processInstanceId = matcher.group(1);
            assertTrue(inventoryListing.contains("status=COMPLETED"), inventoryListing);
            assertTrue(inventoryListing.contains("deployment-id=\t"),
                    () -> "a transient submission opens no deployment domain, so the sanitized-blank "
                            + "column must be empty: " + inventoryListing);
            assertTrue(inventoryListing.contains("retained-from="), inventoryListing);

            var traversalsOutput = new ByteArrayOutputStream();
            assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(traversalsOutput), System.err)
                    .run("traversals", processInstanceId));
            String traversalsListing = traversalsOutput.toString(StandardCharsets.UTF_8);
            assertTrue(traversalsListing.contains("status=COMPLETED"), traversalsListing);
            assertTrue(traversalsListing.contains("ingress-node-id=start"), traversalsListing);
            assertTrue(traversalsListing.contains("retained-from="),
                    () -> "'traversals' must carry the same retention floor 'inventory' does: "
                            + traversalsListing);
        }
    }

    /**
     * Review finding F6: {@code EmbeddedBackend}/{@code RemoteBackend} paged one fixed page (50 rows)
     * and stopped, so a tenant with more instances than that got a silently truncated answer with no
     * signal that anything was missing -- an operator using this to find work that needs recovery
     * would conclude the work is not there. This proves the fix directly through the real backend
     * rather than only at the HTTP layer ({@code ProcessInventoryHttpTest}'s own pagination test):
     * more instances than one page holds are still all discovered by a single {@code ravenroot
     * inventory} call.
     */
    @Test
    void inventoryDoesNotTruncateATenantWithMoreInstancesThanOnePage() throws Exception {
        Path graphFile = workingDirectory.resolve("wiring.graphml");
        Files.writeString(graphFile, GRAPH, StandardCharsets.UTF_8);
        // One row over EmbeddedBackend's own per-request page size (50, see its INVENTORY_PAGE_SIZE),
        // so this fixture is unanswerable by a single page and only passes if the pagination loop
        // this test exists to prove actually runs.
        int instances = 51;

        try (var engine = new PekkoExecutionEngine("inventory-cli-pagination-test-" + UUID.randomUUID());
             var store = new InMemoryExecutionStore()) {
            var application = new DefaultRavenrootApplication(engine, new ExecutionMonitor(),
                    BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()), new InMemoryArtifactRegistry(),
                    new DisabledProgramRuntime(), ExecutionIdentitySource.randomUuids(), store);
            var authorized = new AuthorizedRavenrootApplication(application,
                    new DefaultAuthorizationService(event -> { }), event -> { }, true);
            var context = new RequestContext("inventory-cli-pagination-test-request",
                    "inventory-cli-pagination-test-caller", PrincipalType.USER,
                    "urn:ravenroot:inventory-cli-pagination-test", "local", Set.of(Role.PLATFORM_ADMIN),
                    Arrays.stream(AuthorizationAction.values()).filter(AuthorizationAction::available)
                            .map(AuthorizationAction::requiredScope).collect(Collectors.toUnmodifiableSet()));

            for (int i = 0; i < instances; i++) {
                var runOutput = new ByteArrayOutputStream();
                assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(runOutput), System.err)
                        .run("run", graphFile.toString(), "payload-" + i));
            }

            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            long found = 0;
            String listing = "";
            while (System.nanoTime() < deadline) {
                var output = new ByteArrayOutputStream();
                assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(output), System.err)
                        .run("inventory"));
                listing = output.toString(StandardCharsets.UTF_8);
                found = PROCESS_INSTANCE_ID_LINE.matcher(listing).results().count();
                if (found >= instances) {
                    break;
                }
                Thread.sleep(50);
            }
            String finalListing = listing;
            long finalFound = found;
            assertEquals(instances, found, () -> "expected every one of " + instances + " submitted "
                    + "instances to be discovered by a single 'inventory' call, got " + finalFound + ": "
                    + finalListing);
            assertTrue(finalListing.contains("retained-from="), finalListing);
        }
    }

    private static String pollUntilCompleted(AuthorizedRavenrootApplication authorized, RequestContext context)
            throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        String listing;
        do {
            var output = new ByteArrayOutputStream();
            assertEquals(0, new RavenrootCli(authorized, context, new PrintStream(output), System.err)
                    .run("inventory"));
            listing = output.toString(StandardCharsets.UTF_8);
            if (listing.contains("status=COMPLETED")) {
                return listing;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return listing;
    }
}
