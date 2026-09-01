package ai.ravenroot.server;

import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.server.audit.StructuredArtifactLifecycleLogger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredArtifactLifecycleLoggerTest {
    @Test
    void emitsCorrelationAndIdentityWithoutSourceOrPayload() {
        var bytes = new ByteArrayOutputStream();
        var logger = new StructuredArtifactLifecycleLogger(new PrintStream(bytes, true, StandardCharsets.UTF_8));

        logger.record(new ArtifactLifecycleAuditEvent(Instant.EPOCH, "request-1", "alice", "tenant-a",
                "ARTIFACT_VALIDATE", "artifact-1", "sha-1", ArtifactState.GENERATED,
                ArtifactLifecycleAuditEvent.Disposition.ATTEMPT, 1L, "evidence-digest-fixture"));

        String line = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("\"requestId\":\"request-1\""));
        assertTrue(line.contains("\"subject\":\"alice\""));
        assertTrue(line.contains("\"artifactId\":\"artifact-1\""));
        assertTrue(line.contains("\"disposition\":\"ATTEMPT\""));
        assertFalse(line.contains("source"));
        assertFalse(line.contains("payload"));
    }
}
