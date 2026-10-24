package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.correlation.*;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.security.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class MailCorrelationHeaderProtocolTest {
    private static final String HEADER = "X-Business-Operation";
    private static final ApplicationCorrelationContext CONTEXT = new ApplicationCorrelationContext("ingestion-42",
            Optional.of("partition-2"), Optional.empty(), "round-3", Optional.empty());

    @Test void smtpWireContainsMappedBusinessHeadersAndIndependentMessageId() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, false)) {
            var action = behavior(smtp.port(), Set.of(HEADER, "X-Business-Part", "X-Business-Round")).create(MailTestSupport.configuration(Map.of(
                    CorrelationHeaderMapping.APPLICATION, HEADER, CorrelationHeaderMapping.CONTRIBUTION, "X-Business-Part",
                    CorrelationHeaderMapping.ROUND, "X-Business-Round")));
            var payload = payload(Map.of());
            var result = (Map<?, ?>) action.handle(message(payload, CONTEXT)).toCompletableFuture().join().payload();
            assertEquals("SENT", result.get("status")); assertEquals(1, smtp.dataAccepted());
            var wire = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(smtp.messages().getFirst().getBytes(StandardCharsets.US_ASCII)));
            assertEquals("ingestion-42", wire.getHeader(HEADER, null)); assertEquals("partition-2", wire.getHeader("X-Business-Part", null));
            assertEquals("round-3", wire.getHeader("X-Business-Round", null));
            assertNotNull(wire.getMessageID()); assertNotEquals(CONTEXT.applicationCorrelationId(), wire.getMessageID());
            assertNull(wire.getHeader("X-Correlation-ID")); assertEquals("mail-transport-reference", payload.get("correlationId"));
            assertEquals(Map.of(), payload.get("headers"), "mapping copies the structured input");
        }
    }

    @Test void configuredMappingRefusesMissingContextConflictAndCaseDuplicatesBeforeSocket() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, false)) {
            var action = behavior(smtp.port(), Set.of(HEADER)).create(MailTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, HEADER)));
            for (var input : List.of(message(payload(Map.of()), null), message(payload(Map.of(HEADER, "spoof")), CONTEXT),
                    message(payload(Map.of(HEADER, "ingestion-42", "x-business-operation", "ingestion-42")), CONTEXT))) {
                var error = assertThrows(CompletionException.class, () -> action.handle(input).toCompletableFuture().join());
                assertEquals(MailSendException.Code.INVALID_INPUT, assertInstanceOf(MailSendException.class, error.getCause()).code());
            }
            assertEquals(0, smtp.connections());
        }
    }

    @Test void mappingDoesNotExpandHeaderAuthorityOrBypassNativeCountLimit() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, false)) {
            var denied = behavior(smtp.port(), Set.of("X-Other")).create(MailTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, HEADER)));
            assertThrows(CompletionException.class, () -> denied.handle(message(payload(Map.of()), CONTEXT)).toCompletableFuture().join());
            var zeroHeaders = behavior(smtp.port(), Set.of(HEADER)).create(MailTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, HEADER, "maxHeaders", 0)));
            assertThrows(CompletionException.class, () -> zeroHeaders.handle(message(payload(Map.of()), CONTEXT)).toCompletableFuture().join());
            assertEquals(0, smtp.connections());
        }
    }

    @Test void reservedAndDuplicateTargetsRefuseAtConstruction() {
        var behavior = behavior(2525, Set.of(HEADER));
        for (String target : List.of("Message-ID", "X-Correlation-ID", "X-Ravenroot-Payload-SHA256"))
            assertThrows(IllegalArgumentException.class, () -> behavior.create(MailTestSupport.configuration(Map.of(CorrelationHeaderMapping.APPLICATION, target))));
        assertThrows(IllegalArgumentException.class, () -> behavior.create(MailTestSupport.configuration(Map.of(
                CorrelationHeaderMapping.APPLICATION, HEADER, CorrelationHeaderMapping.ROUND, "x-business-operation"))));
    }
    private static Map<String, Object> payload(Map<String, Object> headers) {
        return Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "bounded result",
                "headers", headers, "correlationId", "mail-transport-reference");
    }
    private static NodeMessage message(Object payload, ApplicationCorrelationContext context) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", "t", "sender", PrincipalType.WORKLOAD, "issuer"),
                id, id, id, id, Set.of(), "mail", payload, Map.of("applicationCorrelationId", "spoof"), ai.ravenroot.api.execution.NodeCommand.PROCESS, context);
    }
    private static MailSendNodeBehavior behavior(int port, Set<String> headers) {
        MailProfile profile = new MailProfile("t", MailTestSupport.PROFILE, "127.0.0.1", port, "SMTP", true, "", "", MailTestSupport.FROM,
                Set.of(MailTestSupport.FROM), Set.of(), Set.of("*"), headers,
                100, 40, 8192, 1048576, 10, 5242880, 10485760, 13981016, 10000, 10000, 10000, 0, 16);
        return MailTestSupport.loopbackBehavior(ref -> { throw new AssertionError("no credential lookup"); },
                (tenant, name) -> Optional.of(profile), "127.0.0.1");
    }
}
