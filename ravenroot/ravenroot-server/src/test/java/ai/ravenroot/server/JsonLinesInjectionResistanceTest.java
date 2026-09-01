package ai.ravenroot.server;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.security.AuthorizationAction;
import ai.ravenroot.api.security.AuthorizationAuditEvent;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphMlParseException;
import ai.ravenroot.server.audit.GraphMlRejectionAuditEvent;
import ai.ravenroot.server.audit.StructuredArtifactLifecycleLogger;
import ai.ravenroot.server.audit.StructuredAuthorizationLogger;
import ai.ravenroot.server.audit.StructuredExecutionLogger;
import ai.ravenroot.server.audit.StructuredGraphMlRejectionLogger;
import ai.ravenroot.server.audit.StructuredRateLimitLogger;
import ai.ravenroot.server.error.PayloadRejectionAuditEvent;
import ai.ravenroot.server.error.StructuredPayloadRejectionLogger;
import ai.ravenroot.server.ratelimit.RateLimitAuditEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the direction that matters: a tab, a newline and a sub-{@code
 * 0x20} control character through every JSON-lines channel this module writes, confirming each
 * <b>record survives as one record</b> -- not merely that the characters are present somewhere in the
 * output. A test that only checked the value was present would pass on a forged record: an unescaped
 * newline in a value produces a second line that a downstream JSON-lines consumer reads as an
 * independent, attacker-shaped record, and a substring check cannot see the difference between that
 * and a correctly escaped single line containing the literal two-character sequence {@code \n}.
 *
 * <p>Every one of these six classes now shares one implementation ({@code JsonStrings.escape}); this
 * class exists to prove each of the six actually calls it on every field a caller controls, not to
 * re-derive {@code JsonStrings}'s own correctness, which {@code JsonStringsTest} covers directly.</p>
 */
class JsonLinesInjectionResistanceTest {
    /** A tab, a real newline, and a sub-0x20 control character (0x01) not among the six named escapes. */
    private static final String CANARY = "tab\tnewline\nctrl\u0001end";

    @Test
    void executionLoggerSurvivesAsOneRecord() {
        var event = new ExecutionEvent(1, Instant.EPOCH, "tenant-a", "req-1", CANARY, "graph-v1",
                UUID.randomUUID(), UUID.randomUUID(), null, null, ExecutionEventType.EXECUTION_STARTED, null,
                0, false, CANARY, null, null, null, CANARY, CANARY);

        // Through accept(), not the static toJson() helper directly, so this exercises the same
        // println path every other channel here does and the "exactly one terminator" check applies
        // uniformly across all six.
        var bytes = capture(output -> new StructuredExecutionLogger(output).accept(event));

        assertSurvivesAsOneRecord(bytes);
    }

    @Test
    void authorizationLoggerSurvivesAsOneRecord() {
        var bytes = capture(output -> new StructuredAuthorizationLogger(output).record(
                new AuthorizationAuditEvent(Instant.EPOCH, "req-1", CANARY, "tenant-a",
                        AuthorizationAction.EXECUTION_START, "execution", "e-1", true, CANARY)));

        assertSurvivesAsOneRecord(bytes);
    }

    @Test
    void rateLimitLoggerSurvivesAsOneRecord() {
        var bytes = capture(output -> new StructuredRateLimitLogger(output).record(
                new RateLimitAuditEvent(Instant.EPOCH, "req-1", "203.0.113.7", false, "tenant-a", CANARY,
                        "POST", "/executions", "rate_limited", "tenant", 429, 30)));

        assertSurvivesAsOneRecord(bytes);
    }

    @Test
    void artifactLifecycleLoggerSurvivesAsOneRecord() {
        var bytes = capture(output -> new StructuredArtifactLifecycleLogger(output).record(
                new ArtifactLifecycleAuditEvent(Instant.EPOCH, "req-1", CANARY, "tenant-a",
                        "ARTIFACT_VALIDATE", "artifact-1", "sha-1", ArtifactState.GENERATED,
                        ArtifactLifecycleAuditEvent.Disposition.ATTEMPT, 1L, "evidence-digest-fixture")));

        assertSurvivesAsOneRecord(bytes);
    }

    /**
     * {@code diagnosticDetail()}'s own content is not attacker-scriptable from outside
     * {@code ai.ravenroot.core.graph} (FIX-03's policy is package-private by design), so this drives
     * the canary through the fields the event wrapper controls -- {@code tenantId}/
     * {@code subject} -- which reach the same {@code escape()} call every other field does.
     */
    @Test
    void graphMlRejectionLoggerSurvivesAsOneRecord() {
        var bytes = capture(output -> new StructuredGraphMlRejectionLogger(output).record(
                new GraphMlRejectionAuditEvent(Instant.EPOCH, "req-1", CANARY, CANARY, malformedGraphMl())));

        assertSurvivesAsOneRecord(bytes);
    }

    @Test
    void payloadRejectionLoggerSurvivesAsOneRecord() {
        var bytes = capture(output -> new StructuredPayloadRejectionLogger(output).record(
                new PayloadRejectionAuditEvent(Instant.EPOCH, "req-1", CANARY, CANARY,
                        PayloadException.malformed())));

        assertSurvivesAsOneRecord(bytes);
    }

    private static GraphMlParseException malformedGraphMl() {
        try {
            GraphManager.readGraphMl(new ByteArrayInputStream("<not-well-formed".getBytes(StandardCharsets.UTF_8)));
        } catch (GraphMlParseException rejection) {
            return rejection;
        }
        throw new AssertionError("expected GraphManager.readGraphMl to reject malformed XML");
    }

    private static String capture(java.util.function.Consumer<PrintStream> write) {
        var bytes = new ByteArrayOutputStream();
        write.accept(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * The assertion that matters: exactly one {@code println}-terminated line, and the escaped
     * two-character sequences are present as literal text rather than the raw characters they stand
     * for. Checking only the second half is exactly the vacuous version of this test -- a raw newline
     * substring-matches {@code "\n"} too if the assertion is not written against the real character.
     */
    private static void assertSurvivesAsOneRecord(String output) {
        long realNewlines = output.chars().filter(character -> character == '\n').count();
        assertEquals(1, realNewlines,
                () -> "expected exactly one println-terminated line (the record itself); an unescaped "
                        + "newline in a value would add another, which a JSON-lines consumer reads as a "
                        + "second, forged record: " + output.replace("\n", "\\n(real)\n"));
        assertTrue(output.contains("\\t"), () -> "tab must be escaped, not raw: " + output);
        assertTrue(output.contains("\\n"), () -> "newline must be escaped, not raw: " + output);
        assertTrue(output.contains("\\u0001"), () -> "the sub-0x20 control character must be escaped: " + output);
        // The literal raw characters must not survive escaping under a different name -- e.g. a tab
        // that got JSON-escaped but a control character that did not.
        assertTrue(output.indexOf('\t') < 0, () -> "a raw tab character must not reach the output: " + output);
        assertTrue(output.indexOf('\u0001') < 0,
                () -> "a raw sub-0x20 control character must not reach the output: " + output);
    }
}
