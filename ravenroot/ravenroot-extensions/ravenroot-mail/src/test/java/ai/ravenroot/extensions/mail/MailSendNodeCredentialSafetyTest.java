package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailSendNodeCredentialSafetyTest {
    private static final String SENTINEL = "credential-secret-sentinel";

    @Test void authenticationRejectionAfterResolutionNeverLeaksTheSecret() throws Exception {
        synchronized (MailSendNodeCredentialSafetyTest.class) {
            try (var trusted = DeterministicSmtpFixture.trustFixtureCertificate();
                 var smtp = DeterministicSmtpFixture.rejectingAuthentication()) {
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> nodeAction(ref -> Optional.of(new SecretValue(SENTINEL.toCharArray())), smtp.port()).handle(message()).toCompletableFuture().join());
                assertEquals(MailSendException.Code.AUTHENTICATION_FAILURE, mailFailure(failure).code());
                assertSecretAbsent(failure);
                assertEquals(1, smtp.authenticationAttempts());
            }
        }
    }

    @Test void transportFailureAfterResolutionNeverLeaksTheSecret() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, true)) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> nodeAction(ref -> Optional.of(new SecretValue(SENTINEL.toCharArray())), smtp.port()).handle(message()).toCompletableFuture().join());
            assertEquals(MailSendException.Code.TRANSPORT_FAILURE, mailFailure(failure).code());
            assertSecretAbsent(failure);
        }
    }

    @Test void missingCredentialReferenceIsReportedWithItsTypedCodeAndNeverLeaked() {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> nodeAction(ref -> Optional.empty(), 9).handle(message()).toCompletableFuture().join());
        assertEquals(MailSendException.Code.CREDENTIAL_UNAVAILABLE, mailFailure(failure).code());
        assertSecretAbsent(failure);
    }

    @Test void reservedLiteralFromCustomProfileIsRefusedBeforeCredentialAndExactExceptionReachesTransport() {
        MailProfile literal = MailTestSupport.profile("t", MailTestSupport.PROFILE, "127.0.0.1", 9,
                "SMTPS", "smtp-user", "mail-primary", 0);
        AtomicInteger resolutions = new AtomicInteger();
        var denied = new MailSendNodeBehavior(reference -> {
            resolutions.incrementAndGet();
            return Optional.of(new SecretValue(SENTINEL.toCharArray()));
        }, (tenant, name) -> Optional.of(literal), SecretValue::copy, String::new,
                ReservedNetworkPolicy.denyAllReserved()).create(MailTestSupport.configuration(Map.of()));
        CompletionException refusal = assertThrows(CompletionException.class,
                () -> denied.handle(message()).toCompletableFuture().join());
        assertEquals(MailSendException.Code.CONFIGURATION, mailFailure(refusal).code());
        assertEquals(0, resolutions.get());
        assertFalse(refusal.toString().contains("127.0.0.1"));

        var allowed = new MailSendNodeBehavior(reference -> {
            resolutions.incrementAndGet();
            return Optional.of(new SecretValue(SENTINEL.toCharArray()));
        }, (tenant, name) -> Optional.of(literal), SecretValue::copy, String::new,
                ReservedNetworkPolicy.fromCommaSeparatedExceptions("127.0.0.1:LOOPBACK"))
                .create(MailTestSupport.configuration(Map.of("connectTimeoutMs", "1")));
        assertThrows(CompletionException.class, () -> allowed.handle(message()).toCompletableFuture().join());
        assertEquals(1, resolutions.get(), "an exact operator exception must reach credential resolution");
    }

    private static ai.ravenroot.api.node.NodeAction nodeAction(ai.ravenroot.api.security.CredentialResolver resolver, int port) {
        return MailTestSupport.authenticatedAction(resolver, "127.0.0.1", port, "STARTTLS");
    }

    private static MailSendException mailFailure(Throwable failure) { return assertInstanceOf(MailSendException.class, failure.getCause()); }
    private static void assertSecretAbsent(Throwable failure) {
        assertSecretAbsent(failure, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    private static void assertSecretAbsent(Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) return;
        assertFalse(String.valueOf(failure.getMessage()).contains(SENTINEL));
        assertFalse(failure.toString().contains(SENTINEL));
        assertSecretAbsent(failure.getCause(), visited);
        for (Throwable suppressed : failure.getSuppressed()) assertSecretAbsent(suppressed, visited);
        if (failure instanceof jakarta.mail.MessagingException messaging) assertSecretAbsent(messaging.getNextException(), visited);
    }
    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", "t", "s", PrincipalType.USER, "i"), id, id, id, id, Set.of(), "mail",
                Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body"), Map.of());
    }
}
