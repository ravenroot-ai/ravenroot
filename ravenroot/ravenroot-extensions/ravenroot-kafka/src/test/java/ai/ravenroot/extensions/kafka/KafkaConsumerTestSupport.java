package ai.ravenroot.extensions.kafka;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

final class KafkaConsumerTestSupport {
    static final KafkaConsumerProtocol.Partition ORDERS = new KafkaConsumerProtocol.Partition("orders", 0);

    static KafkaConsumerProfile profile() {
        return new KafkaConsumerProfile("tenant-a", "reader", List.of("broker.example.test:9093"),
                "use_all_dns_ips", true, "SCRAM-SHA-512", "reader", "kafka-reader", "rr-consumer",
                "orders-logical", "rr-orders-v1", "member-a", Set.of("orders", "audit"), null,
                Set.of("trace", "correlation"), "cooperative-sticky", "earliest", "read_committed",
                2_000, 10, 300_000, 10_000, 1_000, 4, 8_192, 4_096, 2_048, 256, 1_024, 512,
                100, 1, 10, 3, "halt", null);
    }

    static KafkaConsumerProfile deadLetterProfile() {
        KafkaConsumerProfile p = profile();
        return new KafkaConsumerProfile(p.tenant(), p.name(), p.bootstrapServers(), p.clientDnsLookup(), p.tls(),
                p.saslMechanism(), p.username(), p.credentialRef(), p.clientId(), p.groupLogicalName(), p.groupId(),
                p.staticMemberId(), p.topics(), p.topicPattern(), p.headers(), p.assignmentStrategy(),
                p.autoOffsetReset(), p.isolationLevel(), p.startupTimeoutMs(), p.pollTimeoutMs(), p.maxPollIntervalMs(),
                p.sessionTimeoutMs(), p.heartbeatIntervalMs(), p.maxInFlight(), p.maxFetchBytes(),
                p.maxPartitionFetchBytes(), p.maxRecordBytes(), p.maxKeyBytes(), p.maxValueBytes(), p.maxHeaderBytes(),
                p.drainTimeoutMs(), p.retryBackoffMs(), p.maxRetryBackoffMs(), 2, "dead-letter", "orders-dlq");
    }

    static KafkaConsumerProfile haltImmediatelyProfile() {
        KafkaConsumerProfile p = profile();
        return new KafkaConsumerProfile(p.tenant(), p.name(), p.bootstrapServers(), p.clientDnsLookup(), p.tls(),
                p.saslMechanism(), p.username(), p.credentialRef(), p.clientId(), p.groupLogicalName(), p.groupId(),
                p.staticMemberId(), p.topics(), p.topicPattern(), p.headers(), p.assignmentStrategy(),
                p.autoOffsetReset(), p.isolationLevel(), p.startupTimeoutMs(), p.pollTimeoutMs(), p.maxPollIntervalMs(),
                p.sessionTimeoutMs(), p.heartbeatIntervalMs(), p.maxInFlight(), p.maxFetchBytes(),
                p.maxPartitionFetchBytes(), p.maxRecordBytes(), p.maxKeyBytes(), p.maxValueBytes(), p.maxHeaderBytes(),
                p.drainTimeoutMs(), p.retryBackoffMs(), p.maxRetryBackoffMs(), 1, "halt", null);
    }

    static NodeConfiguration configuration() {
        return new NodeConfiguration("consume", KafkaConsumeNodeBehavior.BEHAVIOR,
                Map.of("clusterProfile", "reader"));
    }

    static KafkaConsumerProtocol.Record record(long offset, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new KafkaConsumerProtocol.Record(ORDERS, offset, 1_000 + offset, "CREATE_TIME", null, bytes,
                List.of(new KafkaConsumerProtocol.Header("trace", ("t-" + offset).getBytes())), 1, -1, bytes.length);
    }

    static final class FakeOwner implements KafkaConsumerProtocol.Owner {
        final ArrayDeque<List<KafkaConsumerProtocol.Record>> polls = new ArrayDeque<>();
        final List<Map<KafkaConsumerProtocol.Partition, Long>> commits = new CopyOnWriteArrayList<>();
        final List<Set<KafkaConsumerProtocol.Partition>> pauses = new CopyOnWriteArrayList<>();
        final List<Set<KafkaConsumerProtocol.Partition>> resumes = new CopyOnWriteArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger pollCalls = new AtomicInteger();
        final CountDownLatch subscribed = new CountDownLatch(1);
        final CountDownLatch pollEntered = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch paused = new CountDownLatch(1);
        final CountDownLatch resumed = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch deadLettered = new CountDownLatch(1);
        final CountDownLatch rebalanceProcessed = new CountDownLatch(1);
        final CountDownLatch secondPollEntered = new CountDownLatch(1);
        final CountDownLatch secondPollGate = new CountDownLatch(1);
        final CountDownLatch readinessCheckEntered = new CountDownLatch(1);
        final CountDownLatch readinessCheckGate = new CountDownLatch(1);
        final CountDownLatch assignmentGate;
        final CountDownLatch closeGate;
        final ConcurrentLinkedQueue<Runnable> controls = new ConcurrentLinkedQueue<>();
        final AtomicInteger nonEmptyAssignmentChecks = new AtomicInteger();
        volatile boolean wakeup;
        volatile boolean gateSecondPoll;
        volatile boolean gateReadinessCheck;
        volatile KafkaConsumerProtocol.RebalanceListener listener;
        volatile Set<KafkaConsumerProtocol.Partition> assignment = Set.of();
        volatile boolean deadLetterSucceeds;
        final AtomicInteger deadLetters = new AtomicInteger();

        FakeOwner() { this(false, false); }
        FakeOwner(boolean gateAssignment, boolean gateClose) {
            assignmentGate = new CountDownLatch(gateAssignment ? 1 : 0);
            closeGate = new CountDownLatch(gateClose ? 1 : 0);
        }
        void releaseAssignment() { assignmentGate.countDown(); }
        void releaseClose() { closeGate.countDown(); }
        FakeOwner gateSecondPoll() { gateSecondPoll = true; return this; }
        void releaseSecondPoll() { secondPollGate.countDown(); }
        FakeOwner gateReadinessCheck() { gateReadinessCheck = true; return this; }
        void releaseReadinessCheck() { readinessCheckGate.countDown(); }

        @Override public void subscribe(KafkaConsumerProtocol.Subscription subscription,
                                        KafkaConsumerProtocol.RebalanceListener listener) {
            this.listener = listener;
            subscribed.countDown();
        }
        @Override public List<KafkaConsumerProtocol.Record> poll(Duration timeout) {
            int call = pollCalls.incrementAndGet(); pollEntered.countDown();
            awaitGate(assignmentGate);
            if (gateSecondPoll && call == 2) {
                secondPollEntered.countDown();
                awaitGate(secondPollGate);
            }
            if (wakeup) throw new org.apache.kafka.common.errors.WakeupException();
            if (assignment.isEmpty()) {
                assignment = Set.of(ORDERS); listener.assigned(assignment);
            }
            Runnable control;
            while ((control = controls.poll()) != null) control.run();
            List<KafkaConsumerProtocol.Record> values = polls.pollFirst();
            return values == null ? List.of() : values;
        }
        @Override public Set<KafkaConsumerProtocol.Partition> assignment() {
            Set<KafkaConsumerProtocol.Partition> observed = assignment;
            if (!observed.isEmpty() && gateReadinessCheck
                    && nonEmptyAssignmentChecks.incrementAndGet() == 2) {
                readinessCheckEntered.countDown();
                awaitGate(readinessCheckGate);
            }
            return observed;
        }
        @Override public void pause(Collection<KafkaConsumerProtocol.Partition> values) {
            pauses.add(Set.copyOf(values)); paused.countDown();
        }
        @Override public void resume(Collection<KafkaConsumerProtocol.Partition> values) {
            resumes.add(Set.copyOf(values)); resumed.countDown();
        }
        @Override public void commit(Map<KafkaConsumerProtocol.Partition, Long> offsets) {
            commits.add(Map.copyOf(offsets)); committed.countDown();
        }
        @Override public boolean deadLetter(KafkaConsumerProtocol.Record record, String reason, Duration timeout) {
            deadLetters.incrementAndGet(); deadLettered.countDown(); return deadLetterSucceeds;
        }
        @Override public void wakeup() { wakeup = true; assignmentGate.countDown(); }
        @Override public void close(Duration timeout) {
            closeEntered.countDown(); awaitGate(closeGate); closes.incrementAndGet(); closed.countDown();
        }
        void revoke(boolean lost) {
            controls.add(() -> {
                Set<KafkaConsumerProtocol.Partition> previous = assignment;
                assignment = Set.of();
                if (lost) listener.lost(previous); else listener.revoked(previous);
                rebalanceProcessed.countDown();
            });
        }
        void reconnect() {
            controls.add(() -> {
                Set<KafkaConsumerProtocol.Partition> previous = assignment;
                assignment = Set.of();
                listener.revoked(previous);
                assignment = previous;
                listener.assigned(previous);
                rebalanceProcessed.countDown();
            });
        }
        private static void awaitGate(CountDownLatch gate) {
            try {
                if (!gate.await(2, TimeUnit.SECONDS)) throw new AssertionError("test gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
            }
        }
    }

    static final class FakeContext implements InboundSourceContext {
        final RecordingIngress ingress;
        final List<String> degraded = new CopyOnWriteArrayList<>();
        final AtomicInteger healthy = new AtomicInteger();
        final CountDownLatch healthyReported;
        final CountDownLatch degradedReported = new CountDownLatch(1);
        FakeContext(RecordingIngress ingress) { this(ingress, 1); }
        FakeContext(RecordingIngress ingress, int expectedHealthy) {
            this.ingress = ingress; this.healthyReported = new CountDownLatch(expectedHealthy);
        }
        @Override public DeploymentId deploymentId() { return new DeploymentId("deployment-a"); }
        @Override public String nodeId() { return "consume"; }
        @Override public SecurityContext identity() {
            return new SecurityContext("r", "tenant-a", "consumer", PrincipalType.WORKLOAD, "issuer");
        }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { degraded.add(reason); degradedReported.countDown(); }
        @Override public void reportHealthy() { healthy.incrementAndGet(); healthyReported.countDown(); }
    }

    static final class RecordingIngress implements TrustedIngress {
        final List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final ArrayDeque<IngressReceipt> receipts = new ArrayDeque<>();
        final CountDownLatch offered;
        RecordingIngress() { this(1); }
        RecordingIngress(int expectedOffers) { offered = new CountDownLatch(expectedOffers); }
        volatile boolean checkpoint = true;
        @Override public ai.ravenroot.api.deployment.IngressDisposition offer(SecurityContext security,
                IngressTarget target, Object payload) { return ai.ravenroot.api.deployment.IngressDisposition.ACCEPTED; }
        @Override public int bufferCapacity() { return 16; }
        @Override public ai.ravenroot.api.deployment.IngressOverflowPolicy overflowPolicy() {
            return ai.ravenroot.api.deployment.IngressOverflowPolicy.REJECT;
        }
        @SuppressWarnings("unchecked")
        @Override public synchronized IngressReceipt offerDurably(SecurityContext security, IngressTarget target,
                Object payload, String sourceId, String idempotentKey) {
            payloads.add((Map<String, Object>) payload); keys.add(idempotentKey);
            offered.countDown();
            return receipts.isEmpty() ? new IngressReceipt.DurablyCommitted(idempotentKey) : receipts.removeFirst();
        }
        @Override public java.util.concurrent.CompletionStage<JournalCursor> sourceCheckpoint(
                SecurityContext security, String sourceId) {
            return checkpoint ? CompletableFuture.completedFuture(JournalCursor.start("tenant-a", sourceId))
                    : CompletableFuture.failedFuture(new UnsupportedOperationException("unsupported"));
        }
    }
}
