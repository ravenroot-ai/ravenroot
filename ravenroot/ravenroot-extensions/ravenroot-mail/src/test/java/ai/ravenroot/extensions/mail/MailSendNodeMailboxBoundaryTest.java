package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code permitsLocalhostAndDomainLiteralAddrSpecs} once failed with {@code MailSendException:
 * SMTP connection failed} at 2.015s against four earlier runs at 6-13ms and the run right after at
 * 6ms -- a two-order-of-magnitude jump and a connection failure, not a rejection. Instrumented rather
 * than retried: {@link MailTestSupport#profile}'s default connect/read/write timeouts are all exactly
 * 2000ms, an almost exact match for the observed 2015ms failure, which points at Jakarta Mail's own
 * client-side timeout firing rather than at a DNS lookup, an unbound port or anything the recipient
 * addresses themselves trigger -- both {@code local@localhost} and {@code literal@[127.0.0.1]}
 * resolve to the SAME connection target this test already hands the transport directly
 * ({@code 127.0.0.1:<fixture port>}), so nothing about parsing these specific addr-specs does any
 * network work of its own.
 *
 * <p>Ruled out by direct measurement: raw CPU contention. Twenty busy threads pinned across ten cores
 * for 30 back-to-back runs of this exact call never pushed a single run past 32ms, against a 2000ms
 * budget -- so simple scheduling starvation from other work sharing the machine's cores is not on its
 * own sufficient to explain a multi-second stall. GC pauses or memory pressure from a long-running
 * reactor build remain plausible and were not independently measurable from here; the mechanism that
 * actually produced the 2.015s delay in the original occurrence is not identified.</p>
 *
 * <p>This test's own property is not one a synchronous check can decide: unlike {@link
 * #rejectsInvalidAddrSpecsBeforeOpeningASocket()}, which asserts these addresses are refused before a
 * socket ever opens, this method exists specifically to prove {@code local@localhost} and {@code
 * literal@[127.0.0.1]} survive a REAL SMTP round trip end to end ({@code connections()==1},
 * {@code dataAccepted()==1}) -- so removing the network call would remove the property under test,
 * and a bounded wait would be the wrong tool for an already-synchronous check but is the right one
 * here, where the outcome is genuinely produced by I/O against an in-process fixture. What is fixed
 * is the budget: {@link MailTestSupport#action(ai.ravenroot.api.security.CredentialResolver, String,
 * int, String, int, int)} lets this file alone request a longer one, comfortably inside the loopback
 * fixture's normal single-digit-millisecond completion time and generous enough to absorb realistic
 * scheduling jitter, without loosening any assertion this test makes.</p>
 */
class MailSendNodeMailboxBoundaryTest {

    /**
     * Generous rather than tuned: the fixture normally answers in single-digit milliseconds, so this
     * is headroom against jitter, not a value anything here depends on being exact.
     */
    private static final int GENEROUS_TIMEOUT_MS = 10_000;

    @Test void rejectsInvalidAddrSpecsBeforeOpeningASocket() throws Exception {
        for (String mailbox : List.of("bare", "a@@example.test", "é@example.test", "@example.test", "local@")) {
            try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, false)) {
                CompletionException failure = assertThrows(CompletionException.class, () -> action(smtp.port(), List.of(mailbox)).handle(message(List.of(mailbox))).toCompletableFuture().join());
                assertEquals(MailSendException.Code.INVALID_INPUT, assertInstanceOf(MailSendException.class, failure.getCause()).code(), mailbox);
                assertEquals(0, smtp.connections(), mailbox);
            }
        }
    }

    @Test void permitsLocalhostAndDomainLiteralAddrSpecs() throws Exception {
        List<String> recipients = List.of("local@localhost", "literal@[127.0.0.1]");
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, false)) {
            Map<?, ?> result = (Map<?, ?>) action(smtp.port(), recipients).handle(message(recipients)).toCompletableFuture().join().payload();
            assertEquals("SENT", result.get("status"));
            assertEquals(2, ((List<?>) result.get("acceptedRecipients")).size());
            assertEquals(1, smtp.connections());
            assertEquals(1, smtp.dataAccepted());
        }
    }

    private static ai.ravenroot.api.node.NodeAction action(int port, List<String> recipients) {
        return MailTestSupport.action(ref -> { throw new AssertionError("no credential lookup expected"); },
                "127.0.0.1", port, "SMTP", 0, GENEROUS_TIMEOUT_MS);
    }

    private static NodeMessage message(List<String> recipients) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", "t", "s", PrincipalType.USER, "i"), id, id, id, id, Set.of(), "mail",
                Map.of("version", "mail.send.v1", "to", recipients, "text", "body"), Map.of());
    }
}
