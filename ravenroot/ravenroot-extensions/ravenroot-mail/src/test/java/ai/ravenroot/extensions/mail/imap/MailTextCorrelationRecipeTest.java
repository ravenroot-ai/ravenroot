package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.application.*;
import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.*;
import ai.ravenroot.api.security.*;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.core.runtime.*;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual IMAPS SOURCE and bounded explicit text transform/derivation from the public recipe. */
class MailTextCorrelationRecipeTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(strings={"valid", "malformed", "oversized-id", "truncated"})
    void realImapsTextOnlyEstablishesComputedIdentityThroughExplicitAuditedDerivation(String mode) throws Exception {
        try (var fixture = DeterministicImapFixture.startImaps()) {
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");
            var profile = new ImapProfile("tenant-a", "email", "localhost", fixture.port(), "IMAPS", "reader", "credential-ref", Set.of("INBOX"), 10000, 10000, 1, 20, 1024);
            var policy = new ImapConsumerPolicy("tenant-a", "email", "INBOX", 100, 4, 32, 100, 1000, 3, 65536, "preview", 1024);
            var trustedSocketFactory = fixture.trustedSocketFactory();
            var protocol = new AngusImapConsumerProtocol(properties -> { properties.put("mail.imaps.ssl.socketFactory", trustedSocketFactory); return properties; });
            var credentials = (CredentialResolver) ignored -> Optional.of(new SecretValue("secret".toCharArray()));
            var profiles = (ImapProfileResolver) (tenant, name) -> Optional.of(profile);
            var policies = (ImapConsumerPolicyResolver) (tenant, name) -> Optional.of(policy);
            var behavior = new MailImapConsumeNodeBehavior(profiles, credentials, policies, protocol, task -> Thread.startVirtualThread(task), Clock.systemUTC());
            class FixtureSource implements NodeBehavior, InboundSourceCapable {
                @Override public ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor() { return behavior.descriptor(); }
                @Override public NodeAction create(NodeConfiguration config) { return behavior.create(config); }
                @Override public InboundSource createSource(NodeConfiguration config, InboundSourceContext context) {
                    return new ImapConsumerSource(config, credentials, profiles, policies, protocol, task -> Thread.startVirtualThread(task), Clock.systemUTC(),
                            ignored -> {}, () -> 0.5, ReservedNetworkPolicy.fromCommaSeparatedExceptions("localhost:LOOPBACK"));
                }
            }
            NodePackage packageUnderTest = new NodePackage() {
                @Override public String id() { return "mail.recipe.fixture"; }
                @Override public String version() { return "1"; }
                @Override public String sdkContract() { return NodeSdk.CONTRACT; }
                @Override public List<NodeBehavior> behaviors() { return List.of(new FixtureSource()); }
            };
            Path graphFile = Path.of(System.getProperty("basedir"), "../../../docs/examples/correlation/mail-text-context.graphml").normalize();
            String graph = Files.readString(graphFile).replace("replace-with-operator-profile", "email");
            String version = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(graph.getBytes(StandardCharsets.UTF_8)));
            var scope = new BusinessScope("tenant-a", "email-recipe", version);
            var monitor = new ExecutionMonitor(); var terminals = new CountDownLatch(mode.equals("valid") ? 2 : 1);
            monitor.subscribe(event -> { if (event.type().isTraversalTerminal()) terminals.countDown(); });
            try (var engine = new PekkoExecutionEngine("email-recipe-" + mode); var store = new SqliteExecutionStore(directory.resolve(mode + ".db"), Clock.systemUTC())) {
                var registry = NodePackages.registerAll(BehaviorRegistry.standard(), List.of(packageUnderTest));
                var deployment = new DefaultGraphDeployment(DeploymentId.of("email-recipe"), engine, registry, monitor, ExecutionIdentitySource.randomUuids(), graph.getBytes(StandardCharsets.UTF_8), 32, store, Duration.ofHours(1));
                try {
                    var identity = new SecurityContext("request", "tenant-a", "operator", PrincipalType.USER, "fixture");
                    assertEquals(DeploymentState.READY, deployment.start(identity).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                    String id = mode.equals("oversized-id") ? "x".repeat(257) : "email-ingestion-42";
                    String body = "{\"ingestionId\":\"" + id + "\",\"partition\":\"part-2\",\"stage\":\"silver\",\"round\":\"round-1\",\"filename\":\"sales.csv\"}";
                    if (mode.equals("malformed")) body = "ingestionId=email-ingestion-42";
                    if (mode.equals("truncated")) body += " ".repeat(1200);
                    var mail = new MimeMessage(Session.getInstance(new Properties())); mail.setFrom(new InternetAddress("sender@example.test"));
                    mail.setRecipient(Message.RecipientType.TO, new InternetAddress("reader@example.test")); mail.setSubject("Explicit operation format"); mail.setText(body); mail.saveChanges();
                    user.deliver(mail);
                    assertTrue(terminals.await(20, TimeUnit.SECONDS), "actual source/derived traversal did not settle");
                    var histories = store.businessHistories(scope, BusinessHistoryQuery.first(10)).toCompletableFuture().get().items();
                    assertEquals(mode.equals("valid") ? 2 : 1, histories.size());
                    if (mode.equals("valid")) {
                        var child = store.correlatedTraversals(scope, "email-ingestion-42", Optional.empty(), 10).toCompletableFuture().get().items().getFirst();
                        assertEquals("store-result", child.entryNodeId()); assertEquals(CorrelatedTraversal.Cause.DERIVED_CONTEXT, child.cause());
                        assertEquals(Optional.of("part-2"), child.context().contributionId()); assertEquals("round-1", child.context().roundId());
                        assertEquals(Optional.of("sales.csv"), child.context().publicReference());
                        var parent = child.derivation(); assertNotNull(parent);
                        var original = store.correlatedTraversal("tenant-a", parent.processInstanceId(), parent.traversalId()).toCompletableFuture().get().orElseThrow();
                        assertEquals("mail", original.entryNodeId()); assertNotEquals(child.context().applicationCorrelationId(), original.context().applicationCorrelationId());
                        assertEquals(TraversalStatus.COMPLETED, child.status());
                    } else assertTrue(store.businessHistory(scope, "email-ingestion-42").toCompletableFuture().get().isEmpty(), "failed explicit extraction must not create a guessed history");
                } finally { deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS); }
            }
        }
    }
}
