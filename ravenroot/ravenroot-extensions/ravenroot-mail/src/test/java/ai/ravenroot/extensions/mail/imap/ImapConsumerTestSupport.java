package ai.ravenroot.extensions.mail.imap;

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
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import ai.ravenroot.extensions.mail.imap.ImapConsumerProtocol.Failure;
import ai.ravenroot.extensions.mail.imap.ImapConsumerProtocol.Item;
import ai.ravenroot.extensions.mail.imap.ImapConsumerProtocol.Poll;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class ImapConsumerTestSupport {
    static final String TENANT = "tenant";
    static final String PROFILE = "reader";
    static final String FOLDER = "INBOX";
    static final String SECRET = "secret-sentinel";

    private ImapConsumerTestSupport() { }

    static ImapProfile profile() {
        return new ImapProfile(TENANT, PROFILE, "mail.example.test", 993, "IMAPS", "reader",
                "credential-ref", Set.of(FOLDER), 500, 500, 2, 20, 256);
    }

    static ImapConsumerPolicy policy() {
        return new ImapConsumerPolicy(TENANT, PROFILE, FOLDER, 100, 4, 32,
                100, 1_000, 3, 65_536, "preview", 256);
    }

    static MimeMessage message(String id, String subject, String body) {
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
                @Override public int getSize() { return 128; }
            };
            message.setHeader("Message-ID", id);
            message.setFrom(new InternetAddress("sender@example.test"));
            message.setRecipients(Message.RecipientType.TO, "reader@example.test");
            message.setSubject(subject);
            message.setText(body);
            message.setSentDate(java.util.Date.from(Instant.parse("2026-01-01T00:00:00Z")));
            message.saveChanges();
            return message;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    static final class FakeProtocol implements ImapConsumerProtocol {
        final ConcurrentLinkedQueue<Object> opens = new ConcurrentLinkedQueue<>();
        final AtomicInteger openCalls = new AtomicInteger();
        final CountDownLatch openEntered = new CountDownLatch(1);
        volatile CountDownLatch openGate = new CountDownLatch(0);
        volatile Opening observedOpening;
        volatile char[] observedPassword;
        FakeProtocol(Object... owners) { opens.addAll(List.of(owners)); }
        FakeProtocol gateOpen() { openGate = new CountDownLatch(1); return this; }
        void releaseOpen() { openGate.countDown(); }
        @Override public Owner open(ImapProfile profile, String folder, char[] password, Opening opening)
                throws Failure {
            observedOpening = opening;
            observedPassword = password;
            openCalls.incrementAndGet();
            openEntered.countDown();
            await(openGate);
            if (opening.cancelled()) throw new Failure(false, "imap-startup-cancelled");
            Object next = opens.remove();
            if (next instanceof Failure failure) throw failure;
            return (Owner) next;
        }
    }

    static final class FakeOwner implements ImapConsumerProtocol.Owner {
        final String folder;
        final long validity;
        final LinkedBlockingQueue<Object> polls = new LinkedBlockingQueue<>();
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);
        final CountDownLatch pollEntered = new CountDownLatch(1);
        volatile boolean wakeup;
        FakeOwner() { this(FOLDER, 42); }
        FakeOwner(String folder, long validity) { this.folder = folder; this.validity = validity; }
        void deliver(long uid, Message message) {
            polls.add(new Poll(validity, uid, List.of(new Item(uid, message))));
        }
        void rollover(long newValidity) { polls.add(new Poll(newValidity, 0, List.of())); }
        void disconnect() { polls.add(new Failure(false, "imap-transport-disconnected")); }
        @Override public String sourceFolder() { return folder; }
        @Override public long uidValidity() { return validity; }
        @Override public Poll pollAfter(long afterUid, int batchSize, int scanWindow) throws Failure {
            pollEntered.countDown();
            if (wakeup) throw new Failure(false, "imap-consumer-wakeup");
            try {
                Object value = polls.poll(100, TimeUnit.MILLISECONDS);
                if (wakeup) throw new Failure(false, "imap-consumer-wakeup");
                if (value instanceof Failure failure) throw failure;
                return value == null ? new Poll(validity, afterUid, List.of()) : (Poll) value;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new Failure(false, "imap-consumer-interrupted");
            }
        }
        @Override public void wakeup() { wakeup = true; polls.offer(new Poll(validity, 0, List.of())); }
        @Override public void close() { closes.incrementAndGet(); closed.countDown(); }
    }

    static final class Context implements InboundSourceContext {
        final Ingress ingress;
        final AtomicInteger healthy = new AtomicInteger();
        final List<String> degraded = new CopyOnWriteArrayList<>();
        final CountDownLatch healthyLatch;
        Context(Ingress ingress) { this(ingress, 1); }
        Context(Ingress ingress, int healthyCount) {
            this.ingress = ingress; healthyLatch = new CountDownLatch(healthyCount);
        }
        @Override public DeploymentId deploymentId() { return DeploymentId.of("imap-consumer-test"); }
        @Override public String nodeId() { return "consume"; }
        @Override public SecurityContext identity() {
            return new SecurityContext("request", TENANT, "consumer", PrincipalType.WORKLOAD, "issuer");
        }
        @Override public TrustedIngress ingress() { return ingress; }
        @Override public void reportDegraded(String reason) { degraded.add(reason); }
        @Override public void reportHealthy() { healthy.incrementAndGet(); healthyLatch.countDown(); }
    }

    static final class Ingress implements TrustedIngress {
        final ArrayDeque<IngressReceipt> receipts = new ArrayDeque<>();
        final List<Map<String, Object>> payloads = new CopyOnWriteArrayList<>();
        final List<String> keys = new CopyOnWriteArrayList<>();
        final List<String> sourceIds = new CopyOnWriteArrayList<>();
        final List<Long> advances = new CopyOnWriteArrayList<>();
        final Set<String> durableKeys = new HashSet<>();
        final Map<String, JournalCursor> cursors = new ConcurrentHashMap<>();
        final CountDownLatch offered;
        final CountDownLatch checkpointRequested = new CountDownLatch(1);
        volatile boolean durable = true;
        volatile boolean deduplicate;
        volatile CompletableFuture<JournalCursor> checkpointOverride;
        volatile CountDownLatch offerGate = new CountDownLatch(0);
        Ingress() { this(1); }
        Ingress(int expected) { offered = new CountDownLatch(expected); }
        Ingress gateOffer() { offerGate = new CountDownLatch(1); return this; }
        void releaseOffer() { offerGate.countDown(); }
        @Override public IngressDisposition offer(SecurityContext security, IngressTarget target, Object payload) {
            return IngressDisposition.ACCEPTED;
        }
        @Override public int bufferCapacity() { return 16; }
        @Override public IngressOverflowPolicy overflowPolicy() { return IngressOverflowPolicy.REJECT; }
        @SuppressWarnings("unchecked")
        @Override public synchronized IngressReceipt offerDurably(SecurityContext security, IngressTarget target,
                Object payload, String sourceId, String idempotentKey) {
            payloads.add((Map<String, Object>) payload);
            keys.add(idempotentKey);
            sourceIds.add(sourceId);
            offered.countDown();
            await(offerGate);
            if (!receipts.isEmpty()) return receipts.removeFirst();
            if (deduplicate && !durableKeys.add(idempotentKey)) return new IngressReceipt.Duplicate(idempotentKey);
            return new IngressReceipt.DurablyCommitted(idempotentKey);
        }
        @Override public java.util.concurrent.CompletionStage<JournalCursor> sourceCheckpoint(
                SecurityContext security, String sourceId) {
            checkpointRequested.countDown();
            if (checkpointOverride != null) return checkpointOverride;
            if (!durable) return CompletableFuture.failedFuture(new UnsupportedOperationException("no store"));
            return CompletableFuture.completedFuture(cursors.computeIfAbsent(sourceId,
                    key -> JournalCursor.start(security.tenantId(), key)));
        }
        @Override public synchronized java.util.concurrent.CompletionStage<JournalCursor> advanceSourceCheckpoint(
                JournalCursor expected, long position) {
            JournalCursor current = cursors.get(expected.destination());
            if (!expected.equals(current)) return CompletableFuture.failedFuture(
                    new IllegalStateException("conflict"));
            JournalCursor advanced = new JournalCursor(expected.tenantId(), expected.destination(), position);
            cursors.put(expected.destination(), advanced);
            advances.add(position);
            return CompletableFuture.completedFuture(advanced);
        }
    }

    static void await(CountDownLatch latch) {
        try { org.junit.jupiter.api.Assertions.assertTrue(latch.await(3, TimeUnit.SECONDS), "gate timed out"); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
        }
    }
}
