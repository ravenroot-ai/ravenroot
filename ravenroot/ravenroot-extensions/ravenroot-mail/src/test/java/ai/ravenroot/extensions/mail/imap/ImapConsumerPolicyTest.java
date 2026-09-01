package ai.ravenroot.extensions.mail.imap;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImapConsumerPolicyTest {
    @Test void environmentKeyIsInjectiveAndPolicyIsExactlyBounded() {
        assertNotEquals(EnvironmentImapConsumerPolicyResolver.variableName("a_b", "c"),
                EnvironmentImapConsumerPolicyResolver.variableName("a", "b_c"));
        String key = EnvironmentImapConsumerPolicyResolver.variableName("tenant", "reader");
        var resolver = new EnvironmentImapConsumerPolicyResolver(Map.of(key,
                "INBOX;100;4;32;100;1000;3;65536;preview;256"));
        var policy = resolver.resolve("tenant", "reader").orElseThrow();
        assertEquals("INBOX", policy.folder());
        assertEquals(4, policy.batchSize());
        assertEquals(256, policy.maxPreviewChars());
        assertTrue(policy.allowedHeaders().isEmpty(), "legacy ten-field records must deny headers");
        var withHeaders = new EnvironmentImapConsumerPolicyResolver(Map.of(key,
                "INBOX;100;4;32;100;1000;3;65536;preview;256;X-Trace,x-request-id"))
                .resolve("tenant", "reader").orElseThrow();
        assertEquals(Set.of("x-trace", "x-request-id"), withHeaders.allowedHeaders());
        assertTrue(new EnvironmentImapConsumerPolicyResolver(Map.of(key,
                "INBOX;100;0;32;100;1000;3;65536;preview;256"))
                .resolve("tenant", "reader").isEmpty());
        assertTrue(new EnvironmentImapConsumerPolicyResolver(Map.of(key,
                "INBOX;100;4;32;100;1000;3;65536;preview;256;Authorization"))
                .resolve("tenant", "reader").isEmpty(), "sensitive headers grant no authority");
    }

    @Test void folderLimitIsUtf8BytesAndControlSafe() {
        assertTrue(ImapConsumerPolicy.folder("é".repeat(128)));
        assertFalse(ImapConsumerPolicy.folder("é".repeat(129)));
        assertFalse(ImapConsumerPolicy.folder("INBOX\nArchive"));
    }

    @Test void headerAuthorityBoundsCountAndNamesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> ImapConsumerPolicy.parseHeaders("x-ok,bad name"));
        assertThrows(IllegalArgumentException.class, () -> ImapConsumerPolicy.parseHeaders("received"));
        assertThrows(IllegalArgumentException.class, () -> ImapConsumerPolicy.parseHeaders(
                java.util.stream.IntStream.range(0, 33).mapToObj(i -> "x-" + i)
                        .collect(java.util.stream.Collectors.joining(","))));
        assertThrows(IllegalArgumentException.class, () -> ImapConsumerPolicy.parseHeaders(
                "x-" + "a".repeat(ImapConsumerPolicy.MAX_HEADER_NAME_BYTES)));
    }
}
