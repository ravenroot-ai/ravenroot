package ai.ravenroot.extensions.matrix;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSource;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.node.InboundSourceCapable;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MatrixSyncSourceTest {
    @TempDir Path directory;

    @Test void pollsWithManagedCredentialAndAdvancesOnlyAfterDurableReceipt() {
        Path database = directory.resolve("sync.db"); MatrixTestSupport.HttpHarness http = new MatrixTestSupport.HttpHarness();
        http.reply(200, page("next-1", false, event("$one:example.org", "hello")));
        DurableIngress ingress = new DurableIngress(); SourceRun run = start(database, http, ingress,
                MatrixProfile.InitialSyncMode.DELIVER_BOUNDED, "seed-token", "deployment", "matrix");
        assertEquals(1, ingress.offers.get()); assertTrue(ingress.payloads.getFirst().toString().contains("hello"));
        assertTrue(http.requests.getFirst().destination().toString().contains("since=seed-token"));
        assertEquals("matrix-bearer", http.requests.getFirst().credential().orElseThrow().bindingId());
        assertFalse(http.requests.getFirst().headers().keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("authorization")));
        MatrixSyncStore.SourceKey key = new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment", "matrix");
        assertEquals("next-1", run.store.cursor(key)); run.source.stop().toCompletableFuture().join();
    }

    @Test void refusalAndLimitedTimelineNeverAdvanceCursor() {
        Path refusedDb = directory.resolve("refused.db"); MatrixTestSupport.HttpHarness refusedHttp = new MatrixTestSupport.HttpHarness();
        refusedHttp.reply(200, page("next-refused", false, event("$refused:example.org", "hello")));
        DurableIngress refused = new DurableIngress(); refused.receipt = new IngressReceipt.Refused("buffer full");
        assertThrows(CompletionException.class, () -> start(refusedDb, refusedHttp, refused,
                MatrixProfile.InitialSyncMode.DELIVER_BOUNDED, "seed", "deployment", "node"));
        MatrixConfiguration refusedConfig = MatrixTestSupport.configuration(refusedDb);
        assertNull(new SqliteMatrixSyncStore(refusedConfig.store(), MatrixTestSupport.fixedClock()).cursor(
                new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT, MatrixTestSupport.PROFILE, "deployment", "node")));

        Path gapDb = directory.resolve("gap.db"); MatrixTestSupport.HttpHarness gapHttp = new MatrixTestSupport.HttpHarness();
        gapHttp.reply(200, page("next-gap", true, event("$gap:example.org", "hidden-history")));
        assertThrows(CompletionException.class, () -> start(gapDb, gapHttp, new DurableIngress(),
                MatrixProfile.InitialSyncMode.DELIVER_BOUNDED, "seed", "deployment", "node"));
        MatrixConfiguration gapConfig = MatrixTestSupport.configuration(gapDb);
        assertNull(new SqliteMatrixSyncStore(gapConfig.store(), MatrixTestSupport.fixedClock()).cursor(
                new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT, MatrixTestSupport.PROFILE, "deployment", "node")));
    }

    @Test void explicitInitialSkipDiscardsSnapshotAndPersistsNextBatch() {
        Path database = directory.resolve("skip.db"); MatrixTestSupport.HttpHarness http = new MatrixTestSupport.HttpHarness();
        http.reply(200, page("after-snapshot", true, event("$old:example.org", "old")));
        DurableIngress ingress = new DurableIngress(); SourceRun run = start(database, http, ingress,
                MatrixProfile.InitialSyncMode.SKIP, "", "deployment", "node");
        assertEquals(0, ingress.offers.get());
        assertTrue(http.requests.getFirst().destination().toString().contains("timeout=0"));
        assertEquals("after-snapshot", run.store.cursor(new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment", "node")));
        run.source.stop().toCompletableFuture().join();
    }

    @Test void restartReusesCursorAndDuplicateReceiptCanAdvance() {
        Path database = directory.resolve("restart.db"); DurableIngress ingress = new DurableIngress();
        MatrixTestSupport.HttpHarness firstHttp = new MatrixTestSupport.HttpHarness();
        firstHttp.reply(200, page("cursor-1", false, event("$same:example.org", "hello")));
        SourceRun first = start(database, firstHttp, ingress, MatrixProfile.InitialSyncMode.DELIVER_BOUNDED,
                "seed", "deployment", "node"); first.source.stop().toCompletableFuture().join();
        MatrixTestSupport.HttpHarness secondHttp = new MatrixTestSupport.HttpHarness();
        secondHttp.reply(200, page("cursor-2", false, event("$same:example.org", "hello")));
        SourceRun second = start(database, secondHttp, ingress, MatrixProfile.InitialSyncMode.DELIVER_BOUNDED,
                "ignored-seed", "deployment", "node");
        assertTrue(secondHttp.requests.getFirst().destination().toString().contains("since=cursor-1"));
        assertEquals(2, ingress.offers.get());
        assertEquals("cursor-2", second.store.cursor(new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment", "node")));
        second.source.stop().toCompletableFuture().join();
    }

    @Test void replayIgnoresMutableUnsignedAgeAndJsonObjectOrder() {
        Path database = directory.resolve("mutable-unsigned.db"); DurableIngress ingress = new DurableIngress();
        Map<String, Object> firstEvent = eventWithUnsigned("$stable:example.org", 5_000,
                new java.util.LinkedHashMap<>(Map.of("msgtype", "m.text", "body", "hello")));
        MatrixTestSupport.HttpHarness firstHttp = new MatrixTestSupport.HttpHarness()
                .reply(200, page("cursor-1", false, firstEvent));
        SourceRun first = start(database, firstHttp, ingress, MatrixProfile.InitialSyncMode.DELIVER_BOUNDED,
                "seed", "deployment", "node"); first.source.stop().toCompletableFuture().join();

        Map<String, Object> reorderedContent = new java.util.LinkedHashMap<>();
        reorderedContent.put("body", "hello"); reorderedContent.put("msgtype", "m.text");
        MatrixTestSupport.HttpHarness secondHttp = new MatrixTestSupport.HttpHarness()
                .reply(200, page("cursor-2", false,
                        eventWithUnsigned("$stable:example.org", 19_000, reorderedContent)));
        SourceRun second = start(database, secondHttp, ingress, MatrixProfile.InitialSyncMode.DELIVER_BOUNDED,
                "ignored", "deployment", "node");
        assertEquals("cursor-2", second.store.cursor(new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment", "node")));
        assertEquals(2, ingress.offers.get(), "durable ingress resolves the replay as a duplicate");
        second.source.stop().toCompletableFuture().join();
    }

    @Test void stopCancelsActivePollAndCompletesEvenBeforeReadiness() {
        Path database = directory.resolve("cancel.db");
        MatrixConfiguration configuration = MatrixTestSupport.configuration(database);
        MatrixSyncStore store = new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock());
        MatrixNodePackage nodePackage = new MatrixNodePackage(configuration, store, MatrixTestSupport.fixedClock());
        InboundSourceCapable capable = (InboundSourceCapable) MatrixTestSupport.behavior(
                nodePackage, MatrixBehaviorDescriptors.SYNC);
        Context context = new Context(new DurableIngress(), "deployment", "node");
        MatrixTestSupport.HttpHarness http = new MatrixTestSupport.HttpHarness();
        CompletableFuture<ai.ravenroot.api.node.service.OutboundHttpResponse> pending = new CompletableFuture<>();
        AtomicInteger cancellations = new AtomicInteger();
        http.pending = new ai.ravenroot.api.node.service.OutboundCall<>() {
            @Override public java.util.concurrent.CompletionStage<ai.ravenroot.api.node.service.OutboundHttpResponse> completion() {
                return pending;
            }
            @Override public boolean cancel() { cancellations.incrementAndGet(); return pending.cancel(true); }
        };
        InboundSource source = capable.createSource(MatrixTestSupport.node(MatrixBehaviorDescriptors.SYNC), context, http);
        var readiness = source.start(context).toCompletableFuture();
        waitForRequests(http, 1);
        source.stop().toCompletableFuture().join();
        assertTrue(readiness.isCompletedExceptionally()); assertTrue(cancellations.get() >= 1);
    }

    @Test void profileConcurrencyIsSharedAcrossSyncSources() {
        Path database = directory.resolve("aggregate-concurrency.db");
        MatrixConfiguration configuration = MatrixTestSupport.configuration(database,
                MatrixProfile.InitialSyncMode.DELIVER_BOUNDED, "seed", 1, 20);
        MatrixSyncStore store = new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock());
        MatrixNodePackage nodePackage = new MatrixNodePackage(configuration, store, MatrixTestSupport.fixedClock());
        InboundSourceCapable capable = (InboundSourceCapable) MatrixTestSupport.behavior(
                nodePackage, MatrixBehaviorDescriptors.SYNC);
        MatrixTestSupport.HttpHarness firstHttp = new MatrixTestSupport.HttpHarness();
        Context firstContext = new Context(new DurableIngress(), "deployment-a", "node-a");
        InboundSource first = capable.createSource(MatrixTestSupport.node(MatrixBehaviorDescriptors.SYNC),
                firstContext, firstHttp);
        var firstReady = first.start(firstContext).toCompletableFuture(); waitForRequests(firstHttp, 1);

        MatrixTestSupport.HttpHarness secondHttp = new MatrixTestSupport.HttpHarness();
        Context secondContext = new Context(new DurableIngress(), "deployment-b", "node-b");
        InboundSource second = capable.createSource(MatrixTestSupport.node(MatrixBehaviorDescriptors.SYNC),
                secondContext, secondHttp);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> second.start(secondContext).toCompletableFuture().join());
        assertTrue(failure.getCause().getMessage().contains("matrix-sync-capacity"));
        assertTrue(secondHttp.requests.isEmpty());
        first.stop().toCompletableFuture().join(); assertTrue(firstReady.isCompletedExceptionally());
    }

    @Test void sharedProfileSourcesHaveIndependentCursorNamespaces() {
        Path database = directory.resolve("scoped.db");
        MatrixTestSupport.HttpHarness aHttp = new MatrixTestSupport.HttpHarness().reply(200,
                page("cursor-a", false, event("$a:example.org", "a")));
        MatrixTestSupport.HttpHarness bHttp = new MatrixTestSupport.HttpHarness().reply(200,
                page("cursor-b", false, event("$b:example.org", "b")));
        SourceRun a = start(database, aHttp, new DurableIngress(), MatrixProfile.InitialSyncMode.DELIVER_BOUNDED,
                "seed", "deployment-a", "node-a");
        SourceRun b = start(database, bHttp, new DurableIngress(), MatrixProfile.InitialSyncMode.DELIVER_BOUNDED,
                "seed", "deployment-b", "node-b");
        assertEquals("cursor-a", a.store.cursor(new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment-a", "node-a")));
        assertEquals("cursor-b", b.store.cursor(new MatrixSyncStore.SourceKey(MatrixTestSupport.TENANT,
                MatrixTestSupport.PROFILE, "deployment-b", "node-b")));
        a.source.stop().toCompletableFuture().join(); b.source.stop().toCompletableFuture().join();
    }

    private SourceRun start(Path database, MatrixTestSupport.HttpHarness http, DurableIngress ingress,
                            MatrixProfile.InitialSyncMode mode, String initialSince,
                            String deployment, String node) {
        MatrixConfiguration configuration = MatrixTestSupport.configuration(database, mode, initialSince);
        MatrixSyncStore store = new SqliteMatrixSyncStore(configuration.store(), MatrixTestSupport.fixedClock());
        MatrixNodePackage nodePackage = new MatrixNodePackage(configuration, store, MatrixTestSupport.fixedClock());
        InboundSourceCapable capable = (InboundSourceCapable) MatrixTestSupport.behavior(
                nodePackage, MatrixBehaviorDescriptors.SYNC);
        Context context = new Context(ingress, deployment, node);
        InboundSource source = capable.createSource(MatrixTestSupport.node(MatrixBehaviorDescriptors.SYNC), context, http);
        source.start(context).toCompletableFuture().join();
        return new SourceRun(source, store);
    }
    private static Map<String, Object> page(String next, boolean limited, Map<String, Object> event) {
        return Map.of("next_batch", next, "rooms", Map.of("join", Map.of(MatrixTestSupport.ROOM,
                Map.of("timeline", Map.of("limited", limited, "events", List.of(event))))));
    }
    private static Map<String, Object> event(String id, String text) {
        return Map.of("event_id", id, "type", "m.room.message", "sender", "@alice:example.org",
                "origin_server_ts", 1_788_523_200_000L, "content", Map.of("msgtype", "m.text", "body", text));
    }
    private static Map<String, Object> eventWithUnsigned(String id, long age, Map<String, Object> content) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("unsigned", Map.of("age", age)); event.put("content", content);
        event.put("origin_server_ts", 1_788_523_200_000L); event.put("sender", "@alice:example.org");
        event.put("type", "m.room.message"); event.put("event_id", id); return event;
    }
    private static void waitForRequests(MatrixTestSupport.HttpHarness http, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (http.requests.size() < count && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(http.requests.size() >= count);
    }
    private record SourceRun(InboundSource source, MatrixSyncStore store) { }
    private static final class Context implements InboundSourceContext {
        private final DurableIngress ingress; private final DeploymentId deployment; private final String node;
        private final SecurityContext identity = new SecurityContext("request", MatrixTestSupport.TENANT,
                "operator", PrincipalType.WORKLOAD, "test");
        Context(DurableIngress ingress, String deployment, String node) {
            this.ingress = ingress; this.deployment = DeploymentId.of(deployment); this.node = node;
        }
        @Override public DeploymentId deploymentId() { return deployment; }
        @Override public String nodeId() { return node; }
        @Override public SecurityContext identity() { return identity; }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { }
        @Override public void reportHealthy() { }
    }
    private static final class DurableIngress implements TrustedIngress {
        final Set<String> keys = new HashSet<>(); final AtomicInteger offers = new AtomicInteger();
        final List<Object> payloads = new ArrayList<>(); volatile IngressReceipt receipt;
        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            throw new AssertionError("volatile offer not expected");
        }
        @Override public int bufferCapacity() { return 8; }
        @Override public ai.ravenroot.api.deployment.IngressOverflowPolicy overflowPolicy() {
            return ai.ravenroot.api.deployment.IngressOverflowPolicy.REJECT;
        }
        @Override public synchronized IngressReceipt offerDurably(SecurityContext security, IngressTarget target,
                                                                  Object payload, String sourceId, String key) {
            offers.incrementAndGet(); payloads.add(payload);
            if (receipt != null) return receipt;
            return keys.add(key) ? new IngressReceipt.DurablyCommitted(key) : new IngressReceipt.Duplicate(key);
        }
    }
}
