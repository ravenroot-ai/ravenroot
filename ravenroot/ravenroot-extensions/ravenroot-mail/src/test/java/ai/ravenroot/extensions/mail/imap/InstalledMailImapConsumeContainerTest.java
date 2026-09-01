package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionIdentitySource;
import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.DeploymentState;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.DefaultGraphDeployment;
import ai.ravenroot.core.runtime.ExecutionMonitor;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.persistence.sqlite.SqliteExecutionStore;
import ai.ravenroot.plugin.bundle.PluginBundleLoader;
import ai.ravenroot.pekko.PekkoExecutionEngine;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Separate container gate: installed bundle bytes drive a real durable graph lifecycle. */
class InstalledMailImapConsumeContainerTest {
    private static final String DEPLOYMENT_ID = "installed-mail-consumer";
    private static final String WORKER_ID = "installed-mail-consumer-worker";
    private static final SecurityContext IDENTITY = new SecurityContext("installed-mail-consumer",
            "tenant", "container", PrincipalType.WORKLOAD, "ravenroot-container-test");
    private static final String GRAPH = """
            <?xml version="1.0" encoding="UTF-8"?>
            <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
              <key id="kind" for="node" attr.name="kind" attr.type="string"/>
              <key id="behavior" for="node" attr.name="behavior" attr.type="string"/>
              <key id="profile" for="node" attr.name="profile" attr.type="string"/>
              <key id="content" for="node" attr.name="contentMode" attr.type="string"/>
              <graph id="installed-mail-consumer" edgedefault="directed">
                <node id="start"><data key="kind">start</data></node>
                <node id="consume"><data key="kind">behavior</data><data key="behavior">mail.imap.consume</data><data key="profile">reader</data><data key="content">metadata</data></node>
                <node id="capture"><data key="kind">behavior</data><data key="behavior">test.mail.capture</data></node>
                <node id="end"><data key="kind">end</data></node>
                <node id="error"><data key="kind">error</data></node>
                <edge source="start" target="consume"/><edge source="consume" target="capture"/><edge source="capture" target="end"/>
              </graph>
            </graphml>
            """;

    @TempDir Path directory;

    @Test void installedBundleTraversesAndDurableCheckpointSurvivesStoreAndDeploymentReopen() throws Exception {
        String installedRoot = System.getProperty("ravenroot.mail.installedBundleRoot", "");
        Assumptions.assumeTrue(!installedRoot.isBlank(),
                "run scripts/verify-mail-imap-consumer-container.sh for installed-bundle proof");
        int port = Integer.getInteger("ravenroot.mail.imapFixturePort", 31_443);
        Path database = directory.resolve("installed-consumer.db");
        var consumeCompletions = new AtomicInteger();
        var executionCompletions = new AtomicInteger();
        var events = new CopyOnWriteArrayList<ExecutionEvent>();
        var captured = new CopyOnWriteArrayList<Map<String, Object>>();
        var monitor = new ExecutionMonitor();
        try (var subscription = monitor.subscribe(event -> {
                 events.add(event);
                 if (event.type() == ExecutionEventType.NODE_COMPLETED
                         && "consume".equals(event.nodeId())
                         && "mail.imap.consume".equals(event.nodeCatalogKey())) consumeCompletions.incrementAndGet();
                 if (event.type() == ExecutionEventType.EXECUTION_COMPLETED) executionCompletions.incrementAndGet();
             });
             var fixture = DeterministicImapFixture.startImapsWithDefaultTrust(port);
             var activation = PluginBundleLoader.load(Path.of(installedRoot),
                     Set.of("ai.ravenroot.extensions.mail"))) {
            assertEquals(Set.of("mail.send", "mail.imap.query", "mail.imap.consume",
                            "mail.imap.move", "mail.imap.delete"),
                    activation.packages().getFirst().behaviors().stream()
                            .map(behavior -> behavior.descriptor().behavior())
                            .collect(java.util.stream.Collectors.toSet()));
            assertNotSame(InstalledMailImapConsumeContainerTest.class.getClassLoader(),
                    activation.packages().getFirst().getClass().getClassLoader(),
                    "the runtime package must come from the installed bundle classloader");
            ClassLoader installedLoader = activation.packages().getFirst().getClass().getClassLoader();
            var registry = NodePackages.registerAll(new BehaviorRegistry(), activation.packages())
                    .register("test.mail.capture", message -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = (Map<String, Object>) message.payload();
                        captured.add(Map.copyOf(payload));
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                NodeResult.continueWith(message.payload()));
                    });
            var user = fixture.server().setUser("reader@example.test", "reader", "secret");

            try (var store = new SqliteExecutionStore(database, Clock.systemUTC());
                 var engine = new PekkoExecutionEngine("installed-mail-consumer-first")) {
                var deployment = deployment(engine, registry, monitor, store);
                assertEquals(DeploymentState.READY,
                        deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                user.deliver(message("installed-after-ready-one", "first"));
                awaitCount(consumeCompletions, 1);
                awaitCount(executionCompletions, 1);
                awaitCount(captured, 1);
                assertMessage(captured.getFirst(), 1L, "installed-after-ready-one");
                assertActorTraversal(events, 1);
                assertEquals(DeploymentState.STOPPED,
                        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                assertNoSourceResidue(installedLoader);
            }

            try (var reopened = new SqliteExecutionStore(database, Clock.systemUTC());
                 var engine = new PekkoExecutionEngine("installed-mail-consumer-reopened")) {
                var deployment = deployment(engine, registry, monitor, reopened);
                assertEquals(DeploymentState.READY,
                        deployment.start(IDENTITY).toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                TimeUnit.MILLISECONDS.sleep(350);
                assertEquals(1, consumeCompletions.get(),
                        "the reopened durable checkpoint must suppress the already committed UID");
                assertEquals(1, captured.size(), "a restart must not dispatch UID 1 twice");
                user.deliver(message("installed-after-reopen-two", "second"));
                awaitCount(consumeCompletions, 2);
                awaitCount(executionCompletions, 2);
                awaitCount(captured, 2);
                assertMessage(captured.getLast(), 2L, "installed-after-reopen-two");
                assertActorTraversal(events, 2);
                assertEquals(DeploymentState.STOPPED,
                        deployment.stop().toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                assertNoSourceResidue(installedLoader);
            }
        }
    }

    private static DefaultGraphDeployment deployment(PekkoExecutionEngine engine,
                                                      BehaviorRegistry registry, ExecutionMonitor monitor,
                                                      SqliteExecutionStore store) {
        return new DefaultGraphDeployment(DeploymentId.of(DEPLOYMENT_ID), engine, registry, monitor,
                ExecutionIdentitySource.randomUuids(), GRAPH.getBytes(StandardCharsets.UTF_8),
                DefaultGraphDeployment.DEFAULT_INGRESS_BUFFER_CAPACITY, store,
                DefaultGraphDeployment.DEFAULT_INBOX_RETENTION, WORKER_ID, Duration.ofSeconds(30));
    }

    private static MimeMessage message(String subject, String body) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.test"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("reader@example.test"));
        message.setSubject(subject);
        message.setText(body);
        message.saveChanges();
        return message;
    }

    private static void awaitCount(AtomicInteger count, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count.get() < expected && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(expected, count.get());
    }

    private static void awaitCount(List<?> values, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (values.size() < expected && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(expected, values.size());
    }

    private static void assertMessage(Map<String, Object> payload, long uid, String subject) {
        assertEquals("mail.imap.message.v1", payload.get("version"));
        assertEquals("INBOX", payload.get("sourceFolder"));
        assertEquals(uid, ((Number) payload.get("uid")).longValue());
        assertEquals(subject, payload.get("subject"));
        assertFalse(payload.toString().contains("secret"));
    }

    private static void assertActorTraversal(List<ExecutionEvent> events, int expected) {
        List<ExecutionEvent> starts = events.stream().filter(event -> event.type() == ExecutionEventType.NODE_STARTED
                && "capture".equals(event.nodeId())).toList();
        List<ExecutionEvent> completions = events.stream().filter(event -> event.type() == ExecutionEventType.NODE_COMPLETED
                && "capture".equals(event.nodeId())).toList();
        assertEquals(expected, starts.size());
        assertEquals(expected, completions.size());
        ExecutionEvent started = starts.get(expected - 1), completed = completions.get(expected - 1);
        assertEquals(started.traversalId(), completed.traversalId());
        assertEquals(started.workloadId(), completed.workloadId());
        assertEquals(DEPLOYMENT_ID, started.deploymentId());
        assertEquals("test.mail.capture", started.nodeCatalogKey());
    }

    private static void assertNoSourceResidue(ClassLoader installedLoader) throws Exception {
        assertEquals(0, installedCounter(installedLoader, "activeLeases"),
                "deployment stop must release the installed source lease");
        assertEquals(0, installedCounter(installedLoader, "activeResolverTasks"),
                "deployment stop must leave no installed resolver task");
        assertEquals(0, installedCounter(installedLoader, "activeResolverProfiles"),
                "deployment stop must leave no installed resolver lease");
    }

    private static int installedCounter(ClassLoader installedLoader, String methodName) throws Exception {
        Class<?> source = Class.forName("ai.ravenroot.extensions.mail.imap.ImapConsumerSource", true,
                installedLoader);
        var method = source.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return ((Number) method.invoke(null)).intValue();
    }
}
