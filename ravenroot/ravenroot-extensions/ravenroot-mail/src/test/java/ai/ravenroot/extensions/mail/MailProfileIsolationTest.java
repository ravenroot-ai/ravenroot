package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailProfileIsolationTest {
    @Test void graphRedirectWithValidCredentialReferenceIsRejectedBeforeSecretOrSocket() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.STARTTLS, true, null, false)) {
            AtomicInteger secrets = new AtomicInteger();
            var behavior = new MailSendNodeBehavior(ref -> { secrets.incrementAndGet(); return Optional.empty(); },
                    (tenant, name) -> Optional.of(MailTestSupport.profile(tenant, name, "127.0.0.1", smtp.port(), "STARTTLS", "smtp-user", "mail-primary", 0)));
            assertCode(MailSendException.Code.CONFIGURATION, () -> behavior.create(MailTestSupport.configuration(
                    Map.of("host", "attacker.example", "credentialRef", "mail-primary"))).handle(message()).toCompletableFuture().join());
            assertEquals(0, secrets.get());
            assertEquals(0, smtp.connections());
        }
    }

    @Test void unknownAndTenantForbiddenProfilesAreRejectedBeforeSecretOrSocket() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.STARTTLS, true, null, false)) {
            AtomicInteger secrets = new AtomicInteger();
            var credentials = (ai.ravenroot.api.security.CredentialResolver) ref -> { secrets.incrementAndGet(); return Optional.empty(); };
            var unknown = new MailSendNodeBehavior(credentials, (tenant, name) -> Optional.empty());
            var forbidden = new MailSendNodeBehavior(credentials, (tenant, name) -> Optional.of(
                    MailTestSupport.profile("other-tenant", name, "127.0.0.1", smtp.port(), "STARTTLS", "smtp-user", "mail-primary", 0)));
            assertCode(MailSendException.Code.CONFIGURATION, () -> unknown.create(MailTestSupport.configuration(Map.of())).handle(message()).toCompletableFuture().join());
            assertCode(MailSendException.Code.CONFIGURATION, () -> forbidden.create(MailTestSupport.configuration(Map.of())).handle(message()).toCompletableFuture().join());
            assertEquals(0, secrets.get());
            assertEquals(0, smtp.connections());
        }
    }

    @Test void plaintextAuthenticationAndTlsVerificationDisableAreRejectedBeforeSecretOrSocket() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, false)) {
            AtomicInteger secrets = new AtomicInteger();
            var credentials = (ai.ravenroot.api.security.CredentialResolver) ref -> { secrets.incrementAndGet(); return Optional.empty(); };
            var plaintextAuth = new MailSendNodeBehavior(credentials, (tenant, name) -> Optional.of(new MailProfile(
                    tenant, name, "127.0.0.1", smtp.port(), "SMTP", true, "smtp-user", "mail-primary", MailTestSupport.FROM,
                    Set.of(MailTestSupport.FROM), Set.of(), Set.of("*"), Set.of(), 100, 40, 8192, 1_048_576, 10, 5_242_880,
                    10_485_760, 13_981_016, 2_000, 2_000, 2_000, 0, 16)));
            assertCode(MailSendException.Code.CONFIGURATION, () -> plaintextAuth.create(MailTestSupport.configuration(Map.of())).handle(message()).toCompletableFuture().join());

            var secure = new MailSendNodeBehavior(credentials, (tenant, name) -> Optional.of(
                    MailTestSupport.profile(tenant, name, "127.0.0.1", smtp.port(), "STARTTLS", "smtp-user", "mail-primary", 0)));
            assertCode(MailSendException.Code.CONFIGURATION, () -> secure.create(MailTestSupport.configuration(Map.of("tlsVerify", "false"))).handle(message()).toCompletableFuture().join());
            assertEquals(0, secrets.get());
            assertEquals(0, smtp.connections());
        }
    }

    private static NodeMessage message() {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("r", "tenant-a", "s", PrincipalType.USER, "i"), id, id, id, id,
                Set.of(), "mail", Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body"), Map.of());
    }
    private static void assertCode(MailSendException.Code code, org.junit.jupiter.api.function.Executable call) {
        CompletionException failure = assertThrows(CompletionException.class, call);
        assertEquals(code, assertInstanceOf(MailSendException.class, failure.getCause()).code());
    }
}
