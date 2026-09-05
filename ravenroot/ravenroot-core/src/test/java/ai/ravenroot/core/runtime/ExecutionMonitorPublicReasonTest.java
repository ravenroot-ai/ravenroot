package ai.ravenroot.core.runtime;

import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionEventType;
import ai.ravenroot.api.application.PublicExecutionDescription;
import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.execution.NodeActionDiagnostic;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.core.graph.GraphEdge;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producer-side classifier that lets a public surface stop guessing.
 *
 * <p>Before this, {@code detail} was the only thing distinguishing one {@code NODE_COMPLETED} from
 * another, and {@code detail} cannot cross an HTTP boundary. The public sentence was therefore chosen
 * by event type alone, and said <em>successfully</em> for a node whose outcome was {@code failed}.</p>
 */
class ExecutionMonitorPublicReasonTest {

    /**
     * The duplication {@code ExecutionEvent.DEFAULT_ROUTED_OUTCOME} declares, asserted from the one
     * module that can see both constants.
     *
     * <p>The dependency runs core → application-api, so the API module cannot name {@link GraphEdge}
     * and restates the value instead. If these ever diverge, every default-outcome completion renders
     * as a <em>named non-default</em> outcome — the panel would report a routed failure on every
     * ordinary success, which is the same class of false statement inverted.</p>
     */
    @Test
    void theApiModulesDefaultOutcomeIsTheEnginesDefaultOutcome() {
        assertEquals(GraphEdge.DEFAULT_OUTCOME, ExecutionEvent.DEFAULT_ROUTED_OUTCOME);
    }

    @Test
    void aCompletedNodeCarriesTheOutcomeItRouted() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();

        monitor.nodeStarted(identity, "cel-1", invocationId, invocationId);
        monitor.nodeCompleted(identity, "cel-1", invocationId, invocationId, false, "failed");

        var completed = event(monitor, ExecutionEventType.NODE_COMPLETED);
        assertEquals("failed", completed.publicReason());
        // The diagnostic keeps its prose shape for logs and existing parsers; the classifier is the
        // bare token, because it is rendered INTO a sentence and a prefix would show up inside it.
        assertEquals("outcome=failed", completed.detail());
    }

    /** The exact row from the report: this pair must no longer be producible. */
    @Test
    void theCompletedSentenceForARoutedFailureNoLongerClaimsSuccess() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();

        monitor.nodeStarted(identity, "cel-1", invocationId, invocationId);
        monitor.nodeCompleted(identity, "cel-1", invocationId, invocationId, false, "failed");

        var completed = event(monitor, ExecutionEventType.NODE_COMPLETED);
        String sentence = PublicExecutionDescription.forType(completed.type(), completed.publicReason());
        assertFalse(sentence.contains("successfully"), sentence);
        assertEquals("Node completed and routed its \"failed\" outcome.", sentence);
    }

    @Test
    void anAuthoredDefaultOutcomeStillReadsAsPlainSuccess() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();

        monitor.nodeStarted(identity, "review", invocationId, invocationId);
        monitor.nodeCompleted(identity, "review", invocationId, invocationId, false, null);

        var completed = event(monitor, ExecutionEventType.NODE_COMPLETED);
        assertEquals(GraphEdge.DEFAULT_OUTCOME, completed.publicReason());
        assertEquals("Node completed successfully.",
                PublicExecutionDescription.forType(completed.type(), completed.publicReason()));
    }

    /**
     * The class, and specifically NOT the message — that is the whole reason this is publishable
     * under the information-disclosure rule. A message routinely quotes the value that caused it; a simple class name
     * is a Java type name written in a source file and cannot.
     */
    @Test
    void aFailedNodeCarriesTheCauseClassAndNeverItsMessage() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        var cause = new IllegalStateException("Unknown program artifact: 3a24e190-secret-payload");

        monitor.nodeStarted(identity, "program-1", invocationId, invocationId);
        monitor.nodeFailed(identity, "program-1", invocationId, invocationId,
                new RuntimeException("wrapper", cause));

        var failed = event(monitor, ExecutionEventType.NODE_FAILED);
        assertEquals("IllegalStateException", failed.publicReason());
        String sentence = PublicExecutionDescription.forType(failed.type(), failed.publicReason());
        assertFalse(sentence.contains("3a24e190"), sentence);
        assertFalse(sentence.contains("Unknown program artifact"), sentence);
        assertTrue(sentence.startsWith("Node failed with IllegalStateException."), sentence);
    }

    /**
     * The classifier walks to the same cause the diagnostic does. Reporting the wrapper's class beside
     * the cause's message would give a reader two facts about two different exceptions under one event.
     */
    @Test
    void theClassifierNamesTheSameCauseTheDiagnosticDoes() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        var deepest = new NumberFormatException("deepest");

        monitor.nodeStarted(identity, "n", invocationId, invocationId);
        monitor.nodeFailed(identity, "n", invocationId, invocationId,
                new RuntimeException("outer", new IllegalArgumentException("middle", deepest)));

        var failed = event(monitor, ExecutionEventType.NODE_FAILED);
        assertEquals("NumberFormatException", failed.publicReason());
        assertEquals("deepest", failed.detail());
    }

    /**
     * Regression: {@code message}/{@code failureClass} previously walked to the deepest cause with an
     * unbounded {@code while (current.getCause() != null)}, on a traversal-completion path with no
     * timeout above it. {@link Throwable#initCause} only refuses a direct self-reference, not a
     * longer cycle, so two throwables naming each other as cause used to hang this call forever. The
     * bound mirrors {@code ExecutionTermination.reasonOf}'s own MAX_CAUSE_DEPTH for the identical
     * chain. The {@code @Timeout} is the actual assertion: this test failing by timing out, rather
     * than by a wrong value, is exactly the regression it guards.
     */
    @Test
    @org.junit.jupiter.api.Timeout(5)
    void aCyclicCauseChainDoesNotHangTheClassifierOrTheMessage() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();

        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second); // first -> second -> first: a genuine cycle, not a self-reference.

        monitor.nodeStarted(identity, "n", invocationId, invocationId);
        monitor.nodeFailed(identity, "n", invocationId, invocationId, first);

        var failed = event(monitor, ExecutionEventType.NODE_FAILED);
        assertEquals("RuntimeException", failed.publicReason());
        assertTrue(failed.detail().equals("first") || failed.detail().equals("second"),
                () -> "expected the walk to stop on one of the cycle's own throwables: " + failed.detail());
    }

    @Test
    void anExecutionLimitKeepsItsClosedPublicCodeWhenJoinFailureWrapsIt() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        var wrapper = new IllegalStateException("join failed");
        wrapper.addSuppressed(new GraphExecutionLimitException(
                GraphExecutionLimitException.Reason.TRAVERSAL_STEPS, 11, 10));

        monitor.nodeStarted(identity, "n", invocationId, invocationId);
        monitor.nodeFailed(identity, "n", invocationId, invocationId, wrapper);

        assertEquals("GRAPH_LIMIT_TRAVERSAL_STEPS_EXCEEDED",
                event(monitor, ExecutionEventType.NODE_FAILED).publicReason());
    }

    @Test
    void failureAuthorMessageIsProjectedFromTheRawCauseBeforeLegacyDetailIsBounded() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();
        String secret = "hunter2-never-on-wire";
        String message = "SMTP rejected alice: password=" + secret + "; retry disabled " + "🙂".repeat(400);

        monitor.nodeStarted(identity, "mail", invocationId, invocationId);
        monitor.nodeFailed(identity, "mail", invocationId, invocationId,
                new RuntimeException("wrapper", new IllegalStateException(message)));

        var failed = event(monitor, ExecutionEventType.NODE_FAILED);
        assertEquals(ExecutionEvent.MAX_DETAIL_LENGTH, failed.detail().length());
        assertTrue(failed.authorMessage().value().contains("SMTP rejected alice"));
        assertTrue(failed.authorMessage().value().contains(RuntimeActivityData.REDACTION_MARKER));
        assertFalse(failed.authorMessage().value().contains(secret));
        assertTrue(failed.authorMessage().redacted());
        assertTrue(failed.authorMessage().truncated());
    }

    @Test
    void onlyTheTrustedLogCatalogNodeCanExposeItsTypedActionOutput() {
        var monitor = new ExecutionMonitor();
        var identity = new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "test", "graph",
                UUID.randomUUID(), UUID.randomUUID(), Map.of("logger", "log", "other", "program"));
        var diagnostic = NodeActionDiagnostic.log("sent password=hunter2; done");

        UUID logInvocation = UUID.randomUUID();
        monitor.nodeStarted(identity, "logger", logInvocation, logInvocation);
        monitor.nodeCompleted(identity, "logger", logInvocation, logInvocation, false, "continue", 1, diagnostic);
        UUID otherInvocation = UUID.randomUUID();
        monitor.nodeStarted(identity, "other", otherInvocation, otherInvocation);
        monitor.nodeCompleted(identity, "other", otherInvocation, otherInvocation, false, "continue", 1, diagnostic);

        var completed = monitor.eventsAfter(0).stream()
                .filter(candidate -> candidate.type() == ExecutionEventType.NODE_COMPLETED).toList();
        assertEquals(2, completed.size());
        assertTrue(PayloadJson.write(completed.get(0).authorOutput().value()).contains("sent"));
        assertFalse(PayloadJson.write(completed.get(0).authorOutput().value()).contains("hunter2"));
        assertTrue(completed.get(0).authorOutput().redacted());
        assertNull(completed.get(1).authorOutput(),
                "typed-looking data from a non-log catalog node must not become author output");
    }

    /**
     * Absent, not a placeholder. These types are fully described by their type; inventing a token for
     * them would put a value in front of a reader that means nothing and can be looked up nowhere.
     */
    @Test
    void eventsWhoseMeaningDoesNotDependOnAClassifierCarryNone() {
        var monitor = new ExecutionMonitor();
        var identity = identity();
        UUID invocationId = UUID.randomUUID();

        monitor.nodeStarted(identity, "n", invocationId, invocationId);

        assertNull(event(monitor, ExecutionEventType.NODE_STARTED).publicReason());
    }

    private static ExecutionMonitor.ExecutionIdentity identity() {
        return new ExecutionMonitor.ExecutionIdentity(TestIdentities.TENANT_A, "test", "graph",
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static ExecutionEvent event(ExecutionMonitor monitor, ExecutionEventType type) {
        return monitor.eventsAfter(0).stream().filter(candidate -> candidate.type() == type)
                .findFirst().orElseThrow();
    }
}
