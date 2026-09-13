package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the concurrency at which this node's admission gate begins refusing.
 *
 * <h2>Why this measurement exists, and why it is not a bug report</h2>
 * <p>Every extension builds a per-node {@code maxConcurrency} gate and refuses above it — mail with
 * {@code CAPACITY_UNAVAILABLE}, AMQP with {@code TEMPORARY_FAILURE/LOCAL_CAPACITY}. Those gates have
 * been in the code for a long time and, on the previous single-actor runtime, <b>could never fire</b>: a
 * logical node was one actor, an actor handles one message at a time, so the number of concurrent
 * invocations of a node was exactly one, and a gate whose smallest legal value is one had nothing to
 * refuse.
 *
 * <p>ADR 0024 makes concurrent invocation of one logical node the normal case. The gates therefore
 * become live, and graphs that run today can start seeing capacity refusals they have never seen, for
 * a change entirely internal to the engine. Whether those ceilings are the intended semantics, and
 * whether their defaults are right now that they bite, is a product question. What this test
 * contributes is the number, so the question can be
 * answered from evidence instead of from imagination.
 *
 * <p>It asserts the declared boundary exactly: one invocation is admitted and every simultaneous
 * arrival above that bound is refused. It deliberately does not assert a different policy.
 */
class ConcurrencyGateThresholdTest {

    private static final SecurityContext IDENTITY = new SecurityContext("gate-request", "gate-tenant",
            "gate-subject", PrincipalType.WORKLOAD, "urn:ravenroot:gate");

    /**
     * The per-node gate refuses from {@code maxConcurrency + 1} concurrent invocations onward.
     *
     * <p>Driven at the declared minimum of 1, because that is the boundary that matters: a graph
     * that set {@code maxConcurrency=1} previously expressed something that changed nothing, and
     * now means "refuse the second concurrent arrival". The smallest legal declaration is also the
     * most likely one to be sitting unnoticed in an existing graph.
     */
    @Test
    void measuresWhereTheMailSendGateBeginsRefusing() throws Exception {
        int declaredConcurrency = 1;
        int arrivals = 8;

        var refusals = new AtomicInteger();
        var admitted = new AtomicInteger();
        var ready = new CountDownLatch(arrivals);
        var start = new CountDownLatch(1);
        var sendEntered = new CountDownLatch(1);
        var releaseSend = new CountDownLatch(1);
        var refusalsObserved = new CountDownLatch(arrivals - declaredConcurrency);
        var action = mailSendAction(declaredConcurrency, sendEntered, releaseSend);
        var pool = Executors.newFixedThreadPool(arrivals);
        try {
            var calls = new CompletableFuture[arrivals];
            for (int index = 0; index < arrivals; index++) {
                calls[index] = CompletableFuture.runAsync(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    CompletionStage<NodeResult> stage = action.handle(message());
                    stage.handle((result, error) -> {
                        if (isCapacityRefusal(error)) {
                            refusals.incrementAndGet();
                            refusalsObserved.countDown();
                        } else {
                            // Admitted by the gate. The credential boundary below holds that one
                            // operation until every simultaneous excess arrival has been observed.
                            admitted.incrementAndGet();
                        }
                        return null;
                    }).toCompletableFuture().join();
                }, pool);
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "all callers must be ready before admission starts");
            start.countDown();
            assertTrue(sendEntered.await(10, TimeUnit.SECONDS), "the admitted send must hold its gate permit");
            assertTrue(refusalsObserved.await(10, TimeUnit.SECONDS),
                    "every simultaneous arrival above maxConcurrency must be refused while the permit is held");
            releaseSend.countDown();
            CompletableFuture.allOf(calls).get(120, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            releaseSend.countDown();
            pool.shutdownNow();
        }

        System.out.println("[concurrency-gate] mail.send with maxConcurrency=" + declaredConcurrency + ": "
                + arrivals + " concurrent invocations of ONE logical node -> " + admitted.get()
                + " admitted, " + refusals.get() + " refused with CAPACITY_UNAVAILABLE. "
                + "Previously the same graph produced at most 1 concurrent invocation, so this gate "
                + "could never refuse. The process-wide ceilings remain fixed and are shared by every "
                + "deployment in the JVM: MailSendNodeBehavior.MAIL_SLOTS=32, "
                + "MailImapQueryNodeBehavior.GLOBAL_CONCURRENCY=32 and RESOLVER_GLOBAL_CONCURRENCY=32. "
                + "Declarable maxConcurrency is validated to 1..16 in MailProfile, ImapProfile, "
                + "KafkaProfile and AmqpProfile.");

        assertEquals(declaredConcurrency, admitted.get(), "the declared number of sends must be admitted");
        assertEquals(arrivals - declaredConcurrency, refusals.get(),
                "every simultaneous arrival above maxConcurrency must be refused");
    }

    private static NodeAction mailSendAction(int maxConcurrency, CountDownLatch sendEntered,
                                             CountDownLatch releaseSend) {
        var behavior = MailTestSupport.loopbackBehavior(reference -> {
                    sendEntered.countDown();
                    await(releaseSend);
                    return Optional.empty();
                },
                (tenant, name) -> Optional.of(MailTestSupport.profile(tenant, name, "127.0.0.1", 2525,
                        "STARTTLS", "smtp-user", "gate-hold", 0)), "127.0.0.1");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("mailProfile", MailTestSupport.PROFILE);
        properties.put("credentialRef", "gate-hold");
        properties.put("maxConcurrency", maxConcurrency);
        return behavior.create(new NodeConfiguration("gate-node", "mail.send", properties));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the admission measurement to release the send");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("admission measurement interrupted", interrupted);
        }
    }

    private static NodeMessage message() {
        return new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(), "gate-node",
                Map.of("version", "mail.send.v1", "to", java.util.List.of("someone@example.com"),
                        "subject", "s", "text", "b"), Map.of());
    }

    /** A capacity refusal, distinguished from every other way a send can fail. */
    private static boolean isCapacityRefusal(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof MailSendException failure
                    && failure.code() == MailSendException.Code.CAPACITY_UNAVAILABLE) {
                return true;
            }
        }
        return false;
    }
}
