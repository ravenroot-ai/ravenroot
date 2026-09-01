package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.error.ErrorCode;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImapMutationContractTest {
    @Test void descriptorsPublishCompleteGenericInspectorSchemasAndHardDeleteCondition() {
        var move = new MailImapMutationNodeBehavior(
                MailImapMutationNodeBehavior.Kind.MOVE).descriptor();
        var delete = new MailImapMutationNodeBehavior(
                MailImapMutationNodeBehavior.Kind.DELETE).descriptor();
        assertEquals("Mail", move.category());
        assertEquals("Mail", delete.category());
        assertEquals(Set.of("profile", "sourceFolder", "destinationFolder", "maxConcurrency",
                        "recovery.repeatable"), names(move));
        assertEquals(Set.of("profile", "sourceFolder", "deleteMode",
                        "hardDeleteAcknowledgement", "maxConcurrency", "recovery.repeatable"),
                names(delete));
        assertEquals(NodePropertyType.STRING, property(move, "destinationFolder").type());
        assertTrue(property(move, "destinationFolder").required());
        assertEquals(java.util.List.of("TRASH", "HARD_DELETE"),
                property(delete, "deleteMode").allowedValues());
        var acknowledgement = property(delete, "hardDeleteAcknowledgement");
        assertNotNull(acknowledgement.visibleWhen());
        assertNotNull(acknowledgement.requiredWhen());
        assertTrue(acknowledgement.visibleWhen().holds("HARD_DELETE"));
        assertTrue(acknowledgement.requiredWhen().holds("HARD_DELETE"));
        assertFalse(acknowledgement.visibleWhen().holds("TRASH"));
        assertEquals(Set.of("network", "credential-reference", "side-effect"),
                move.capabilities());
    }

    @Test void environmentPolicyIsExplicitExactAndLegacyProfileAbsenceGrantsNothing() {
        String key = EnvironmentImapMutationPolicyResolver.environmentVariableName(
                "tenant", "reader");
        assertEquals("RAVENROOT_IMAP_MUTATION_POLICY_74656E616E74_726561646572", key);
        var resolver = new EnvironmentImapMutationPolicyResolver(Map.of(key,
                "MOVE,TRASH,HARD_DELETE;Archive,Trash;Trash"));
        ImapMutationPolicy policy = resolver.resolve("tenant", "reader").orElseThrow();
        assertEquals(Set.of(ImapMutationOperation.MOVE, ImapMutationOperation.TRASH,
                ImapMutationOperation.HARD_DELETE), policy.allowedOperations());
        assertTrue(policy.allowsDestination("Archive"));
        assertEquals("Trash", policy.trashFolder());

        assertTrue(new EnvironmentImapMutationPolicyResolver(Map.of())
                .resolve("tenant", "reader").isEmpty());
        assertTrue(new EnvironmentImapMutationPolicyResolver(Map.of(key,
                "MOVE;Archive;unexpected-trash")).resolve("tenant", "reader").isEmpty());
        assertTrue(new EnvironmentImapMutationPolicyResolver(Map.of(key,
                "TRASH;Archive;Trash")).resolve("tenant", "reader").isEmpty());
        assertTrue(new EnvironmentImapMutationPolicyResolver(Map.of(key,
                "HARD_DELETE;;")).resolve("tenant", "reader").isPresent());
    }

    @Test void infrastructureFailuresUseSanitizedStableEnvelopeCodes() {
        assertEquals(ErrorCode.INVALID_REQUEST, failure(ImapMutationException.Code.INVALID_INPUT));
        assertEquals(ErrorCode.INTERNAL_ERROR,
                failure(ImapMutationException.Code.PROFILE_UNAVAILABLE));
        assertEquals(ErrorCode.INTERNAL_ERROR,
                failure(ImapMutationException.Code.CREDENTIAL_UNAVAILABLE));
        assertEquals(ErrorCode.REQUEST_LIMIT_EXCEEDED,
                failure(ImapMutationException.Code.SATURATED));
        assertEquals(ErrorCode.REQUEST_INTERRUPTED, failure(ImapMutationException.Code.TIMEOUT));
        assertEquals(ErrorCode.REQUEST_INTERRUPTED,
                failure(ImapMutationException.Code.TRANSPORT_FAILURE));
    }

    @Test void rejectedOperatorPolicyLogsOnlyAConstraintAndNeverTheRawValue() {
        String sentinel = "operator-policy-secret-sentinel";
        String loggerName = "ai.ravenroot.mail.imap.mutation-policy.rejected";
        Logger logger = Logger.getLogger(loggerName);
        var records = new ArrayList<LogRecord>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        boolean previousParents = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            String key = EnvironmentImapMutationPolicyResolver.environmentVariableName(
                    "tenant", "reader");
            assertTrue(new EnvironmentImapMutationPolicyResolver(
                    Map.of(key, "MOVE;Archive;" + sentinel)).resolve("tenant", "reader").isEmpty());
            assertEquals(1, records.size());
            String record = records.getFirst().getMessage() + " "
                    + java.util.Arrays.toString(records.getFirst().getParameters());
            assertTrue(record.contains("UNUSED_TRASH_FOLDER"));
            assertFalse(record.contains(sentinel));
            assertNull(records.getFirst().getThrown());
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(previousParents);
        }
    }

    private static Set<String> names(ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor) {
        return descriptor.properties().stream().map(property -> property.name())
                .collect(Collectors.toSet());
    }

    private static ai.ravenroot.api.catalog.NodePropertyDescriptor property(
            ai.ravenroot.api.catalog.NodeTypeDescriptor descriptor, String name) {
        return descriptor.properties().stream().filter(property -> property.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static ErrorCode failure(ImapMutationException.Code code) {
        return new ImapMutationException(code, "sanitized").errorCode();
    }
}
