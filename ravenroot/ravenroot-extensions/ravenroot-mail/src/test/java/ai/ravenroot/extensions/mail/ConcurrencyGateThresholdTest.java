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
 * <p>It asserts only that a refusal happens above the declared bound, which is the gate working as
 * written. It deliberately does not assert a policy.
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
        var action = mailSendAction(declaredConcurrency);

        var refusals = new AtomicInteger();
        var admitted = new AtomicInteger();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(arrivals);
        try {
            var calls = new CompletableFuture[arrivals];
            for (int index = 0; index < arrivals; index++) {
                calls[index] = CompletableFuture.runAsync(() -> {
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
                        } else {
                            // Admitted by the gate. It then fails on the unreachable SMTP host, which
                            // is not what is being measured: admission is, and admission already
                            // happened before any socket was opened.
                            admitted.incrementAndGet();
                        }
                        return null;
                    }).toCompletableFuture().join();
                }, pool);
            }
            start.countDown();
            CompletableFuture.allOf(calls).get(120, TimeUnit.SECONDS);
        } finally {
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

        assertTrue(refusals.get() > 0,
                "with maxConcurrency=" + declaredConcurrency + " and " + arrivals + " concurrent "
                        + "arrivals the gate must refuse at least one. It refused none, which would "
                        + "mean the arrivals were still being serialised somewhere.");
    }

    private static NodeAction mailSendAction(int maxConcurrency) {
        var behavior = new MailSendNodeBehavior(reference -> Optional.empty(),
                (tenant, name) -> Optional.of(MailTestSupport.profile(tenant, name, "127.0.0.1", 2525,
                        "SMTP", "", "", 0)));
        var properties = new LinkedHashMap<String, Object>();
        properties.put("mailProfile", MailTestSupport.PROFILE);
        properties.put("credentialRef", "");
        properties.put("maxConcurrency", maxConcurrency);
        return behavior.create(new NodeConfiguration("gate-node", "mail.send", properties));
    }

    private static NodeMessage message() {
        return new NodeMessage(IDENTITY, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(), "gate-node",
                Map.of("to", "someone@example.com", "subject", "s", "body", "b"), Map.of());
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
