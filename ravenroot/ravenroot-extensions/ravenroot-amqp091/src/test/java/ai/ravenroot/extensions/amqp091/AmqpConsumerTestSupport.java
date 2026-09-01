package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.deployment.DeploymentId;
import ai.ravenroot.api.deployment.InboundSourceContext;
import ai.ravenroot.api.deployment.IngressDisposition;
import ai.ravenroot.api.deployment.IngressOverflowPolicy;
import ai.ravenroot.api.deployment.IngressReceipt;
import ai.ravenroot.api.deployment.IngressTarget;
import ai.ravenroot.api.deployment.TrustedIngress;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class AmqpConsumerTestSupport {
    private AmqpConsumerTestSupport() { }

    static AmqpConsumerPolicy policy() {
        return new AmqpConsumerPolicy(AmqpTestSupport.TENANT, AmqpTestSupport.PROFILE, "orders.q", 4,
                Set.of("trace", "identity"), "", 4_096, 1_024, 100, 1_000, 3, "dead-letter", 10);
    }

    static AmqpConsumerProtocol.Delivery delivery(long tag, String id, boolean redelivered) {
        return new AmqpConsumerProtocol.Delivery(tag, redelivered, "orders", "created",
                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new AmqpConsumerProtocol.Properties("text/plain", "utf-8", id, "c-1", null,
                        "order", "test", 1_000, Map.of("trace", "t-1")));
    }

    static final class FakeProtocol implements AmqpConsumerProtocol {
        final ConcurrentLinkedQueue<Object> opens = new ConcurrentLinkedQueue<>();
        final AtomicInteger openCalls = new AtomicInteger();
        final CountDownLatch openEntered = new CountDownLatch(1);
        volatile CountDownLatch openGate = new CountDownLatch(0);
        volatile int observedPrefetch;
        FakeProtocol(Object... owners) { opens.addAll(List.of(owners)); }
        FakeProtocol gateOpen() { openGate = new CountDownLatch(1); return this; }
        void releaseOpen() { openGate.countDown(); }
        @Override public Owner open(AmqpProfile profile, AmqpConsumerPolicy policy, char[] password,
                                    int prefetch) throws Failure {
            openCalls.incrementAndGet(); observedPrefetch = prefetch; openEntered.countDown(); await(openGate);
            Object next = opens.remove();
            if (next instanceof Failure failure) throw failure;
            return (Owner) next;
        }
    }

    static final class FakeOwner implements AmqpConsumerProtocol.Owner {
        final LinkedBlockingQueue<AmqpConsumerProtocol.Event> events = new LinkedBlockingQueue<>();
        final List<Long> acks = new CopyOnWriteArrayList<>();
        final List<String> nacks = new CopyOnWriteArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch acked;
        final CountDownLatch firstNack = new CountDownLatch(1);
        final CountDownLatch nacked;
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch pollEntered = new CountDownLatch(1);
        volatile boolean wakeup;
        FakeOwner() { this(1, 1); }
        FakeOwner(int expectedNacks) { this(1, expectedNacks); }
        FakeOwner(int expectedAcks, int expectedNacks) {
            acked = new CountDownLatch(expectedAcks); nacked = new CountDownLatch(expectedNacks);
        }
        void deliver(AmqpConsumerProtocol.Delivery value) { events.add(value); }
        void reject(long tag, String reason) { events.add(new AmqpConsumerProtocol.Rejected(tag, reason)); }
        void disconnect() { events.add(new AmqpConsumerProtocol.Disconnected("amqp-connection-lost")); }
        @Override public AmqpConsumerProtocol.Event poll(Duration timeout) throws AmqpConsumerProtocol.Failure {
            pollEntered.countDown();
            if (wakeup) throw new AmqpConsumerProtocol.Failure(false, "amqp-consumer-wakeup");
            try {
                var value = events.poll(100, TimeUnit.MILLISECONDS);
                if (wakeup) throw new AmqpConsumerProtocol.Failure(false, "amqp-consumer-wakeup");
                return value == null ? new AmqpConsumerProtocol.Idle() : value;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new AmqpConsumerProtocol.Failure(false, "interrupted");
            }
        }
        @Override public void ack(long tag) { acks.add(tag); acked.countDown(); }
        @Override public void nack(long tag, boolean requeue) {
            nacks.add(tag + ":" + requeue); firstNack.countDown(); nacked.countDown();
        }
        @Override public void wakeup() { wakeup = true; events.offer(new AmqpConsumerProtocol.Idle()); }
        @Override public void close(Duration timeout) { closes.incrementAndGet(); closed.countDown(); }
    }

    static final class Context implements InboundSourceContext {
        final Ingress ingress;
        final AtomicInteger healthy = new AtomicInteger();
        final List<String> degraded = new CopyOnWriteArrayList<>();
        final CountDownLatch healthyLatch;
        Context(Ingress ingress) { this(ingress, 1); }
        Context(Ingress ingress, int healthyCount) { this.ingress = ingress; healthyLatch = new CountDownLatch(healthyCount); }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("amqp-test"); }
        @Override public String nodeId() { return "consume"; }
        @Override public SecurityContext identity() {
            return new SecurityContext("request", AmqpTestSupport.TENANT, "consumer", PrincipalType.WORKLOAD, "issuer");
        }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { degraded.add(reason); }
        @Override public void reportHealthy() { healthy.incrementAndGet(); healthyLatch.countDown(); }
    }

    static final class Ingress implements TrustedIngress {
        final ArrayDeque<IngressReceipt> receipts = new ArrayDeque<>();
        final List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<String> outcomes = new CopyOnWriteArrayList<>();
        final Set<String> durableKeys = new HashSet<>();
        final CountDownLatch offered;
        volatile boolean durable = true;
        volatile CountDownLatch offerGate = new CountDownLatch(0);
        Ingress() { this(1); }
        Ingress(int expected) { offered = new CountDownLatch(expected); }
        Ingress gateOffer() { offerGate = new CountDownLatch(1); return this; }
        Ingress deduplicate() { deduplicate = true; return this; }
        void releaseOffer() { offerGate.countDown(); }
        volatile boolean deduplicate;
        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            return IngressDisposition.ACCEPTED;
        }
        @Override public int bufferCapacity() { return 16; }
        @Override public IngressOverflowPolicy overflowPolicy() { return IngressOverflowPolicy.REJECT; }
        @SuppressWarnings("unchecked")
        @Override public synchronized IngressReceipt offerDurably(SecurityContext security, IngressTarget target,
                Object payload, String sourceId, String idempotentKey) {
            payloads.add((Map<String, Object>) payload); keys.add(idempotentKey); offered.countDown(); await(offerGate);
            IngressReceipt receipt;
            if (!receipts.isEmpty()) receipt = receipts.removeFirst();
            else if (deduplicate && !durableKeys.add(idempotentKey)) receipt = new IngressReceipt.Duplicate(idempotentKey);
            else receipt = new IngressReceipt.DurablyCommitted(idempotentKey);
            outcomes.add(receipt.getClass().getSimpleName());
            return receipt;
        }
        @Override public java.util.concurrent.CompletionStage<JournalCursor> sourceCheckpoint(
                SecurityContext security, String sourceId) {
            return durable ? CompletableFuture.completedFuture(JournalCursor.start(security.tenantId(), sourceId))
                    : CompletableFuture.failedFuture(new UnsupportedOperationException("no store"));
        }
    }

    static void await(CountDownLatch latch) {
        try { org.junit.jupiter.api.Assertions.assertTrue(latch.await(2, TimeUnit.SECONDS), "gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
