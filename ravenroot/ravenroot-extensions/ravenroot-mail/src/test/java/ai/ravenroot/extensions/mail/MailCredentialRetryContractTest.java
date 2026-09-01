package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MailCredentialRetryContractTest {
    /**
     * Same convention and same value as {@link MailSendNodeSmtpProtocolTest#GENEROUS_TIMEOUT_MS}:
     * headroom against scheduling jitter on a loaded reactor, not a value
     * {@link #transientReconnectUsesBoundedBackoff()} depends on being exact.
     */
    private static final int GENEROUS_TIMEOUT_MS = 10_000;

    @Test void clearsErasableCredentialBeforeTransientConnectAndReusesOneResolution() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger copies = new AtomicInteger();
        AtomicReference<char[]> producedCopy = new AtomicReference<>();
        SecretValue secret = new SecretValue("retry-secret".toCharArray());
        try (var trusted = DeterministicSmtpFixture.trustFixtureCertificate();
             var smtp = DeterministicSmtpFixture.disconnectingOnceThenAcceptingAuthentication("retry-secret")) {
            smtp.pauseBeforeFirstDisconnect();
            var execution = authenticated(smtp.port(), 1, ref -> {
                resolutions.incrementAndGet();
                return Optional.of(secret);
            }, value -> {
                copies.incrementAndGet();
                char[] copy = value.copy();
                producedCopy.set(copy);
                return copy;
            }, String::new).handle(message());
            boolean cleared;
            try {
                assertTrue(smtp.awaitFirstDisconnectConnection(),
                        "first connection must reach the deterministic barrier");
                char[] observed = secret.copy();
                cleared = java.util.Arrays.equals(observed, new char[observed.length]);
                java.util.Arrays.fill(observed, '\0');
                assertArrayEquals(new char[producedCopy.get().length], producedCopy.get(),
                        "copied credential must be cleared before the first connect can retry");
            } finally { smtp.releaseFirstDisconnect(); }
            assertEquals("SENT", ((Map<?, ?>) execution.toCompletableFuture().join().payload()).get("status"));
            assertTrue(cleared, "erasable credential must be cleared before the first connect can retry");
            assertEquals(1, resolutions.get());
            assertEquals(1, copies.get());
        } finally { secret.close(); }
    }

    @Test void clearsSecretAndCopyWhenPasswordStringConstructionFails() {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger copies = new AtomicInteger();
        AtomicReference<char[]> producedCopy = new AtomicReference<>();
        SecretValue secret = new SecretValue("setup-secret".toCharArray());
        try {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> result(authenticated(1, 0, ref -> {
                        resolutions.incrementAndGet();
                        return Optional.of(secret);
                    }, value -> {
                        copies.incrementAndGet();
                        char[] copy = value.copy();
                        producedCopy.set(copy);
                        return copy;
                    }, ignored -> { throw new IllegalStateException("synthetic string construction failure"); })));
            assertEquals(MailSendException.Code.TRANSPORT_FAILURE,
                    assertInstanceOf(MailSendException.class, failure.getCause()).code());
            assertArrayEquals(new char[producedCopy.get().length], producedCopy.get());
            char[] observed = secret.copy();
            try { assertArrayEquals(new char[observed.length], observed); }
            finally { java.util.Arrays.fill(observed, '\0'); }
            assertEquals(1, resolutions.get());
            assertEquals(1, copies.get());
        } finally { secret.close(); }
    }

    @Test void resolvesCredentialExactlyOnceAcrossTransientConnectRetry() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        try (var trusted = DeterministicSmtpFixture.trustFixtureCertificate();
             var smtp = DeterministicSmtpFixture.disconnectingOnceThenAcceptingAuthentication("retry-secret")) {
            Map<?, ?> result = result(authenticated(smtp.port(), 1, ref -> {
                resolutions.incrementAndGet();
                return Optional.of(new SecretValue("retry-secret".toCharArray()));
            }));
            assertEquals("SENT", result.get("status"));
            assertEquals(2, smtp.connections());
            assertEquals(1, resolutions.get());
            assertTrue(smtp.retryGapMillis() >= 40, "transient retry must wait before reconnecting");
        }
    }

    @Test void authenticationFailureIsTerminalDistinctAndNotRetried() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        try (var trusted = DeterministicSmtpFixture.trustFixtureCertificate();
             var smtp = DeterministicSmtpFixture.rejectingAuthentication()) {
            CompletionException failure = assertThrows(CompletionException.class, () -> result(authenticated(smtp.port(), 3, ref -> {
                resolutions.incrementAndGet();
                return Optional.of(new SecretValue("credential-secret-sentinel".toCharArray()));
            })));
            MailSendException typed = assertInstanceOf(MailSendException.class, failure.getCause());
            assertEquals("AUTHENTICATION_FAILURE", typed.code().name());
            assertEquals(1, smtp.connections());
            assertEquals(1, smtp.authenticationAttempts());
            assertEquals(1, resolutions.get());
            assertFalse(typed.toString().contains("credential-secret-sentinel"));
        }
    }

    /**
     * The original floor here was {@code elapsedMillis >= 40} against a single retry (one
     * {@code backoff(0)} call, 50ms) on a two-attempt connect to port 1. Measurement showed that floor never
     * reddening when {@code backoff()} was mutated to sleep 0ms, in either regime (isolated 99-106ms,
     * 9-way contended 165-209ms), because the residual cost of two failed connects to a privileged port
     * cleared 40ms by 2.5x on its own — the floor was proving connect overhead, not backoff.
     *
     * <p>Re-measured directly on this test as written (temporary {@code System.err} instrumentation on
     * {@code elapsedMillis}, reverted, never committed), the picture was more unstable than the first measurement:
     * whether the floor reddened depended on which other tests had already run in the same forked JVM.
     * Run as the only test in a fresh fork, mutated {@code elapsedMillis} was 99-106ms, confirming the isolated result.
     * Run as part of this class (the way {@code mvn test} actually executes it), mutated
     * {@code elapsedMillis} dropped to 4-10ms, because the earlier tests in the class had already warmed
     * the JVM's mail/TLS class loading and JIT paths, cutting the residual per-connect cost from ~100ms
     * to single digits — and at that residual, the original 40ms floor did redden, deterministically
     * (isolated and under artificial 9-way contention, 6/6 and 3/3 runs). So the floor's ability to catch
     * this regression was real but order-dependent: a property of which tests happened to run first, not
     * of the retry path itself. That is not a floor worth keeping as written.
     *
     * <p>The backoff dominates regardless of warm-up state: {@code retries} is 3, so three
     * {@code backoff()} calls stack
     * (50+100+200=350ms) instead of one. Measured on this fixture (Apple M1 Max, 10 core, macOS 26.5.2,
     * JDK 21), {@code backoff()} intact vs. mutated to sleep 0ms, across every execution context tried:
     * <ul>
     *   <li>fresh JVM fork, this test alone, isolated: baseline 467-483ms (3 runs) vs. mutated 101-105ms
     *       (3 runs)</li>
     *   <li>full class in one fork (the {@code mvn test} regime), isolated: baseline 372-382ms (3 runs)
     *       vs. mutated 4ms (3 runs)</li>
     *   <li>full class in one fork, under 9-way artificial contention: baseline 378-385ms (3 runs) vs.
     *       mutated 6-10ms (3 runs)</li>
     * </ul>
     * The worst-case mutated reading across all three contexts is 105ms; the worst-case baseline reading
     * is 372ms — a margin of at least 267ms on both sides of any threshold placed between them, unlike the
     * 40ms floor's ~2ms margin under its own best case. The new floor is set at 200ms, in the middle of
     * that gap, so no execution-order effect observed here can move a run across it in either direction.
     */
    @Test void transientRetryWaitsAndFinalFailureRetainsOnlySafeCause() {
        long started = System.nanoTime();
        CompletionException failure = assertThrows(CompletionException.class,
                () -> result(MailTestSupport.action(ref -> Optional.empty(), "127.0.0.1", 1, "SMTP", 3)));
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        MailSendException typed = assertInstanceOf(MailSendException.class, failure.getCause());
        assertEquals(MailSendException.Code.TRANSPORT_FAILURE, typed.code());
        assertTrue(elapsedMillis >= 200, "a retry must include bounded backoff that dominates connect overhead");
        assertNotNull(typed.getCause(), "the final classified cause must not be discarded");
        assertFalse(typed.getCause().toString().contains("127.0.0.1"));
    }

    /**
     * Shares {@link MailTestSupport#profile}'s fixed 2000ms connect/read/write budget with the
     * SMTP-protocol-test family: a real, even if loopback, round trip that
     * must reconnect once against the deterministic fixture before it can succeed, so the same
     * reactor-load jitter that reddened {@code completesStartTlsAndSmtpsHandshakesWithTheTrustedFixtureCertificate}
     * at exactly 2000ms reddens this one identically. The property -- that a transient connect failure
     * is retried once, after a bounded backoff, and the retry succeeds -- does not depend on the client
     * timeout at all; the 40ms/1000ms bounds already asserted below are the instrument for backoff
     * timing, this budget is only headroom for the round trip to complete. Widened to
     * {@link #GENEROUS_TIMEOUT_MS} for the same reason as the rest of the family: a client timeout
     * aborting an in-flight retry under load is a false red, and raising a bound whose expiry produces
     * only a false red cannot hide a defect in the property under test.
     */
    @Test void transientReconnectUsesBoundedBackoff() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, true)) {
            assertEquals("SENT", result(MailTestSupport.action(ref -> Optional.empty(), "127.0.0.1", smtp.port(), "SMTP", 1, GENEROUS_TIMEOUT_MS)).get("status"));
            assertTrue(smtp.retryGapMillis() >= 40, "transient retry must wait before reconnecting");
            assertTrue(smtp.retryGapMillis() <= 1_000, "retry wait must remain bounded");
        }
    }

    private static NodeAction authenticated(int port, int retries, ai.ravenroot.api.security.CredentialResolver credentials) {
        return new MailSendNodeBehavior(credentials, (tenant, name) -> Optional.of(MailTestSupport.profile(
                tenant, name, "127.0.0.1", port, "STARTTLS", "smtp-user", "mail-primary", retries)))
                .create(MailTestSupport.configuration(Map.of()));
    }
    private static NodeAction authenticated(int port, int retries,
                                            ai.ravenroot.api.security.CredentialResolver credentials,
                                            MailSendNodeBehavior.SecretCopy secretCopy,
                                            MailSendNodeBehavior.PasswordString passwordString) {
        return new MailSendNodeBehavior(credentials, (tenant, name) -> Optional.of(MailTestSupport.profile(
                tenant, name, "127.0.0.1", port, "STARTTLS", "smtp-user", "mail-primary", retries)),
                secretCopy, passwordString).create(MailTestSupport.configuration(Map.of()));
    }
    private static Map<?, ?> result(NodeAction action) {
        return (Map<?, ?>) action.handle(message()).toCompletableFuture().join().payload();
    }
    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", "t", "s", PrincipalType.USER, "i"), id, id, id, id,
                Set.of(), "mail", Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body"), Map.of());
    }
}
