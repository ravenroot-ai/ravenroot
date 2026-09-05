package ai.ravenroot.extensions.mail;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecretValue;
import ai.ravenroot.api.security.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/** Unit evidence for graph-to-profile containment; no SMTP fixture or socket is needed. */
class MailProfilePolicyTest {
    @Test void environmentProfileIsOpaqueAndRejectsMalformedValues() {
        var resolver = new EnvironmentMailProfileResolver(Map.of(
                "RAVENROOT_MAIL_PROFILE_64656661756C74_7072696D617279", "smtp.example.test;587;STARTTLS;false;mailer;primary;from@example.test;to@example.test;X-Trace;2"));
        var profile = resolver.resolve("default", "primary").orElseThrow();
        assertEquals("smtp.example.test", profile.host());
        assertEquals("primary", profile.credentialRef());
        assertEquals(2, profile.maxConcurrency());
        assertTrue(new EnvironmentMailProfileResolver(Map.of("RAVENROOT_MAIL_PROFILE_64656661756C74_626164", "bad")).resolve("default", "bad").isEmpty());
        assertTrue(new EnvironmentMailProfileResolver(Map.of("RAVENROOT_MAIL_PROFILE_64656661756C74_6C6567616379",
                "smtp.example.test;587;STARTTLS;false;mailer;primary;from@example.test;to@example.test;X-Trace"))
                .resolve("default", "legacy").isEmpty());
        assertTrue(new EnvironmentMailProfileResolver(Map.of("RAVENROOT_MAIL_PROFILE_64656661756C74_7A65726F",
                "smtp.example.test;587;STARTTLS;false;mailer;primary;from@example.test;to@example.test;X-Trace;0"))
                .resolve("default", "zero").isEmpty());
        assertTrue(new EnvironmentMailProfileResolver(Map.of("RAVENROOT_MAIL_PROFILE_64656661756C74_746F6F68696768",
                "smtp.example.test;587;STARTTLS;false;mailer;primary;from@example.test;to@example.test;X-Trace;17"))
                .resolve("default", "toohigh").isEmpty());
    }

    @Test void environmentKeysAreInjectiveAndCaseSensitiveForTenantAndProfile() {
        var resolver = new EnvironmentMailProfileResolver(Map.of(
                "RAVENROOT_MAIL_PROFILE_74656E616E742D61_7072696D617279", environmentValue("lower-tenant.test"),
                "RAVENROOT_MAIL_PROFILE_54454E414E542D41_7072696D617279", environmentValue("upper-tenant.test"),
                "RAVENROOT_MAIL_PROFILE_74656E616E742D61_5072696D617279", environmentValue("upper-profile.test")));
        assertEquals("lower-tenant.test", resolver.resolve("tenant-a", "primary").orElseThrow().host());
        assertEquals("upper-tenant.test", resolver.resolve("TENANT-A", "primary").orElseThrow().host());
        assertEquals("upper-profile.test", resolver.resolve("tenant-a", "Primary").orElseThrow().host());
        assertTrue(resolver.resolve("TENANT-A", "Primary").isEmpty());
    }

    @Test void graphCannotRedirectProfileOrRelaxItsMaximum() {
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(behavior(), config(Map.of("host", "attacker.example")), validPayload()));
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(behavior(), config(Map.of("maxRecipients", "101")), validPayload()));
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(behavior(), config(Map.of("maxConcurrency", "17")), validPayload()));
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(behavior(), config(Map.of("maxConcurrency", "33")), validPayload()));
        assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, () -> execute(behavior(), config(Map.of("maxRecipients", "1", "credentialRef", "primary")), validPayload()));
        assertCode(MailSendException.Code.CREDENTIAL_UNAVAILABLE, () -> execute(behavior(), config(Map.of("maxConcurrency", "1", "credentialRef", "primary")), validPayload()));
        assertThrows(IllegalArgumentException.class, () -> MailTestSupport.profile("t", "p", "127.0.0.1", 1, "SMTP", "", "", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> MailTestSupport.profile("t", "p", "127.0.0.1", 1, "SMTP", "", "", 0, 17));
    }

    @Test void unknownAndMisboundProfilesAreRejectedBeforeCredentialLookup() {
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(new MailSendNodeBehavior(ref -> { lookups.incrementAndGet(); return Optional.empty(); }, (tenant, profile) -> Optional.empty()), config(Map.of()), validPayload()));
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(new MailSendNodeBehavior(ref -> { lookups.incrementAndGet(); return Optional.empty(); }, (tenant, profile) -> Optional.of(profile("other", "primary"))), config(Map.of()), validPayload()));
        assertEquals(0, lookups.get());
    }

    @Test void plainSmtpRequiresExplicitLocalUnauthenticatedProfile() {
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(plaintext("smtp.example.test", false, "", ""), config(Map.of()), validPayload()));
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(plaintext("127.0.0.1", false, "", ""), config(Map.of()), validPayload()));
        assertCode(MailSendException.Code.CONFIGURATION, () -> execute(plaintext("127.0.0.1", true, "mailer", "primary"), config(Map.of()), validPayload()));
        assertCode(MailSendException.Code.TRANSPORT_FAILURE, () -> execute(plaintext("::1", true, "", ""), config(Map.of()), validPayload()));
    }

    @Test void secureProfilesRequireAngusServerIdentityVerification() throws Exception {
        assertEquals("true", properties("STARTTLS").getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("true", properties("SMTPS").getProperty("mail.smtps.ssl.checkserveridentity"));
        assertNull(properties("STARTTLS").getProperty("mail.smtp.ssl.trust"));
    }

    @Test void profilePoliciesRejectRecipientsReservedHeadersAndSenderBeforeTransport() {
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(Map.of("to", List.of("other@example.test"), "text", "body")));
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(Map.of("to", List.of("to@example.test"), "from", "evil@example.test", "text", "body")));
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(Map.of("to", List.of("to@example.test"), "headers", Map.of("Bcc", "evil@example.test"), "text", "body")));
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(Map.of("to", List.of("to@example.test"), "headers", Map.of("X-Evil", "x"), "text", "body")));
    }

    @Test void emptyAddressPoliciesFailClosedAndOnlyExplicitWildcardAllowsAnyValue() {
        assertThrows(IllegalArgumentException.class, () -> profileWithPolicies(Set.of(), Set.of("to@example.test"), Set.of("x-trace")));
        assertThrows(IllegalArgumentException.class, () -> profileWithPolicies(Set.of("from@example.test"), Set.of(), Set.of("x-trace")));
        MailProfile restrictive = profileWithPolicies(Set.of("from@example.test"), Set.of("to@example.test"), Set.of());
        assertFalse(restrictive.allowsAddress(restrictive.allowedReplyTo(), "reply@example.test"));
        assertFalse(restrictive.allowsHeader("X-Trace"));
        MailProfile wildcard = profileWithPolicies(Set.of("*"), Set.of("*"), Set.of("*"));
        assertTrue(wildcard.allowsAddress(wildcard.allowedFrom(), "any-sender@example.test"));
        assertTrue(wildcard.allowsAddress(wildcard.allowedRecipients(), "any-recipient@example.test"));
        assertTrue(wildcard.allowsHeader("X-Any"));
        var wildcardBehavior = new MailSendNodeBehavior(ref -> Optional.empty(), (tenant, name) -> Optional.of(
                profileWithPolicies(tenant, name, Set.of("*"), Set.of("*"), Set.of("*"))));
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(wildcardBehavior, config(Map.of()),
                Map.of("version", "mail.send.v1", "to", List.of("any-recipient@example.test"), "text", "body", "headers", Map.of("Bcc", "hidden@example.test"))));
    }

    @Test void aggregateDecodedAndBase64AttachmentBudgetsAreEnforced() {
        byte[] five = new byte[5];
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(Map.of("to", List.of("to@example.test"), "text", "body", "attachments", List.of(attachment(five), attachment(five)))));
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(Map.of("to", List.of("to@example.test"), "text", "body", "attachments", List.of(base64Attachment("AQIDBA=="), base64Attachment("AQIDBA==")))));
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(Map.of("to", List.of("to@example.test"), "text", "body", "attachments", List.of(base64Attachment("not-rfc4648%%%")))));
    }

    @Test void rawRecipientArraysFieldsAndAggregateTextAreBoundedBeforeNetwork() throws Exception {
        try (var smtp = DeterministicSmtpFixture.start(DeterministicSmtpFixture.Mode.PLAIN, false, null, false)) {
            var action = MailTestSupport.action(ref -> { throw new AssertionError("credential lookup is forbidden"); },
                    "127.0.0.1", smtp.port(), "SMTP", 0);
            List<String> tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "r" + i + "@example.test").toList();
            List<String> excessiveAggregate = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(i -> "r" + i + "x".repeat(90) + "@example.test").toList();
            assertCode(MailSendException.Code.INVALID_INPUT, () -> action.handle(message(Map.of("version", "mail.send.v1", "to", tooMany, "text", "body"))).toCompletableFuture().join());
            assertCode(MailSendException.Code.INVALID_INPUT, () -> action.handle(message(Map.of("version", "mail.send.v1", "to", List.of("r".repeat(321)), "text", "body"))).toCompletableFuture().join());
            assertCode(MailSendException.Code.INVALID_INPUT, () -> action.handle(message(Map.of("version", "mail.send.v1", "to", excessiveAggregate, "text", "body"))).toCompletableFuture().join());
            assertEquals(0, smtp.connections());
        }
    }

    @Test void operatorHeaderAndEnvelopeBudgetsCoverAllMetadataFields() {
        Map<String,Object> headers = new LinkedHashMap<>(validPayload()); headers.put("headers", Map.of("X-Trace", "1", "X-Other", "2"));
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(metadataBehavior(headers), config(Map.of()), headers));
        for (String field : List.of("subject", "correlationId", "from", "replyTo")) {
            Map<String,Object> payload = new LinkedHashMap<>(validPayload());
            payload.put(field, field.equals("from") ? "very-long-sender@example.test" : field.equals("replyTo") ? "very-long-reply@example.test" : "x".repeat(80));
            assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(metadataBehavior(payload), config(Map.of()), payload));
        }
        Map<String,Object> longRecipient = Map.of("version", "mail.send.v1", "to", List.of("r".repeat(8_200) + "@example.test"), "text", "body");
        assertCode(MailSendException.Code.INVALID_INPUT, () -> execute(metadataBehavior(longRecipient), config(Map.of()), longRecipient));
    }

    @Test void typedFailuresDoNotRetainProtocolCauses() {
        var failure = new MailSendException(MailSendException.Code.TRANSPORT_FAILURE, "safe", new RuntimeException("server secret"));
        assertNull(failure.getCause());
        assertEquals(MailSendException.Code.TRANSPORT_FAILURE, failure.code());
    }

    private static Object execute(Map<String, Object> payload) {
        Map<String, Object> message = new LinkedHashMap<>(payload); message.put("version", "mail.send.v1");
        return behavior().create(config(Map.of())).handle(message(message)).toCompletableFuture().join();
    }
    private static java.util.Properties properties(String mode) throws Exception {
        var type = Class.forName("ai.ravenroot.extensions.mail.MailSendNodeBehavior$Settings");
        var from = type.getDeclaredMethod("from", NodeConfiguration.class, MailProfileResolver.class, String.class); from.setAccessible(true);
        Object settings = from.invoke(null, config(Map.of("securityMode", mode)), (MailProfileResolver) (tenant, name) -> Optional.of(profile(tenant, name, mode, "mailer", "primary")), "default");
        var properties = type.getDeclaredMethod("properties"); properties.setAccessible(true);
        return (java.util.Properties) properties.invoke(settings);
    }
    private static MailSendNodeBehavior behavior() { return new MailSendNodeBehavior(ref -> Optional.empty(), (tenant, name) -> Optional.of(profile(tenant, name))); }
    private static NodeConfiguration config(Map<String, String> extra) { Map<String, Object> values = new LinkedHashMap<>(); values.put("mailProfile", "primary"); values.putAll(extra); return new NodeConfiguration("mail", "mail.send", values); }
    private static MailProfile profile(String tenant, String name) { return profile(tenant, name, "STARTTLS", "mailer", "primary"); }
    private static MailProfile profile(String tenant, String name, String mode, String username, String credential) {
        return new MailProfile(tenant, name, "smtp.example.test", 587, mode, false, username, credential, "from@example.test",
                Set.of("from@example.test"), Set.of("reply@example.test"), Set.of("to@example.test"), Set.of("x-trace"),
                100, 40, 8192, 1024, 10, 5, 8, 8, 1000, 1000, 1000, 0, 16);
    }
    private static MailSendNodeBehavior plaintext(String host, boolean allowed, String username, String credential) {
        return new MailSendNodeBehavior(ref -> { throw new AssertionError("secret resolver must not run"); }, (tenant, name) -> Optional.of(new MailProfile(tenant, name, host, 1, "SMTP", allowed, username, credential, "from@example.test",
                Set.of("from@example.test"), Set.of(), Set.of("*"), Set.of(), 100, 40, 8192, 1024, 10, 5, 8, 8, 100, 100, 100, 0, 16)),
                SecretValue::copy, String::new, MailTestSupport.loopbackPolicy(host));
    }
    private static Map<String, Object> attachment(byte[] bytes) { return Map.of("name", "a.txt", "contentType", "text/plain", "content", bytes); }
    private static Map<String, Object> base64Attachment(String value) { return Map.of("name", "a.txt", "contentType", "text/plain", "content", value); }
    private static String environmentValue(String host) { return host + ";587;STARTTLS;false;mailer;primary;from@example.test;to@example.test;X-Trace;2"; }
    private static MailProfile profileWithPolicies(Set<String> from, Set<String> recipients, Set<String> headers) {
        return profileWithPolicies("tenant", "profile", from, recipients, headers);
    }
    private static MailProfile profileWithPolicies(String tenant, String name, Set<String> from, Set<String> recipients, Set<String> headers) {
        return new MailProfile(tenant, name, "smtp.example.test", 587, "STARTTLS", false,
                "mailer", "primary", "from@example.test", from, Set.of(), recipients, headers,
                100, 40, 8192, 1024, 10, 5, 8, 8, 1000, 1000, 1000, 0, 16);
    }
    private static Map<String,Object> validPayload() { return Map.of("version", "mail.send.v1", "to", List.of("to@example.test"), "text", "body"); }
    private static Object execute(MailSendNodeBehavior behavior, NodeConfiguration configuration, Map<String,Object> payload) { return behavior.create(configuration).handle(message(payload)).toCompletableFuture().join(); }
    private static MailSendNodeBehavior metadataBehavior(Map<String,Object> payload) {
        Set<String> recipients = new java.util.LinkedHashSet<>(Set.of("to@example.test", "very-long-recipient@example.test"));
        Object rawRecipients = payload.get("to"); if (rawRecipients instanceof List<?> values) values.forEach(value -> recipients.add(String.valueOf(value)));
        return new MailSendNodeBehavior(ref -> Optional.empty(), (tenant, name) -> Optional.of(new MailProfile(tenant, name, "smtp.example.test", 587, "STARTTLS", false, "mailer", "primary", "from@example.test",
                Set.of("from@example.test", "very-long-sender@example.test"), Set.of("very-long-reply@example.test"), recipients, Set.of("x-trace", "x-other"),
                100, 1, 40, 1024, 10, 5, 10, 8, 1000, 1000, 1000, 0, 16)));
    }
    private static NodeMessage message(Object payload) { UUID id = UUID.randomUUID(); return new NodeMessage(new SecurityContext("r", "t", "s", PrincipalType.USER, "i"), id, id, id, id, Set.of(), "mail", payload, Map.of()); }
    private static void assertCode(MailSendException.Code code, org.junit.jupiter.api.function.Executable call) {
        Throwable failure = assertThrows(Throwable.class, call); while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        assertInstanceOf(MailSendException.class, failure); assertEquals(code, ((MailSendException) failure).code());
    }
}
