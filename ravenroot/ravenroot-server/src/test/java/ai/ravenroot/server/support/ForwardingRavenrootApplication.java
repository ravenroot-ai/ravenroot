package ai.ravenroot.server.support;

import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.DurableExecutionEvent;
import ai.ravenroot.api.application.ExecutionEvent;
import ai.ravenroot.api.application.ExecutionLookup;
import ai.ravenroot.api.application.ExecutionSubmission;
import ai.ravenroot.api.application.GraphSummary;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.programming.ArtifactTestResult;
import ai.ravenroot.api.programming.GeneratedArtifact;
import ai.ravenroot.api.programming.ProgramLanguageDescriptor;
import ai.ravenroot.api.security.SecurityContext;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Transparent test decorator; deliberately inherits the interface's explicit-policy default. */
public class ForwardingRavenrootApplication implements RavenrootApplication {
    private final RavenrootApplication delegate;

    public ForwardingRavenrootApplication(RavenrootApplication delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    protected final RavenrootApplication delegate() { return delegate; }
    @Override public ApplicationStatus status() { return delegate.status(); }
    @Override public RuntimeSnapshot runtimeSnapshot() { return delegate.runtimeSnapshot(); }
    @Override public List<NodeTypeDescriptor> nodeTypes() { return delegate.nodeTypes(); }
    @Override public List<GeneratedArtifact> programArtifacts() { return delegate.programArtifacts(); }
    @Override public List<ProgramLanguageDescriptor> supportedProgramLanguages() {
        return delegate.supportedProgramLanguages();
    }
    @Override public GeneratedArtifact createProgramArtifact(String language, String source,
                                                              Map<String, String> metadata) {
        return delegate.createProgramArtifact(language, source, metadata);
    }
    @Override public CompletionStage<GeneratedArtifact> validateProgramArtifact(String id) {
        return delegate.validateProgramArtifact(id);
    }
    @Override public CompletionStage<ArtifactTestResult> testProgramArtifact(String id, Object payload) {
        return delegate.testProgramArtifact(id, payload);
    }
    @Override public GeneratedArtifact approveProgramArtifact(String id, Map<String, String> evidence) {
        return delegate.approveProgramArtifact(id, evidence);
    }
    @Override public GeneratedArtifact activateProgramArtifact(String id, Map<String, String> evidence) {
        return delegate.activateProgramArtifact(id, evidence);
    }
    @Override public GeneratedArtifact retireProgramArtifact(String id, Map<String, String> evidence) {
        return delegate.retireProgramArtifact(id, evidence);
    }
    @Override public GraphSummary inspectGraphMl(InputStream graphMl) { return delegate.inspectGraphMl(graphMl); }
    @Override public ExecutionSubmission startGraphMl(SecurityContext security, UUID executionId,
                                                       InputStream graphMl, Object payload) {
        return delegate.startGraphMl(security, executionId, graphMl, payload);
    }
    @Override public boolean cancelTraversal(UUID traversalId) { return delegate.cancelTraversal(traversalId); }
    @Override public boolean drain(Duration bound) { return delegate.drain(bound); }
    @Override public boolean processInventoryAvailable() { return delegate.processInventoryAvailable(); }
    @Override public ai.ravenroot.api.persistence.ProcessInventoryPage processInventory(
            String tenantId, ai.ravenroot.api.persistence.ProcessInventoryQuery query) {
        return delegate.processInventory(tenantId, query);
    }
    @Override public java.util.Optional<ai.ravenroot.api.persistence.ProcessInventoryEntry> processInstance(
            String tenantId, UUID processInstanceId) {
        return delegate.processInstance(tenantId, processInstanceId);
    }
    @Override public List<ai.ravenroot.api.persistence.TraversalInventoryEntry> processInstanceTraversals(
            String tenantId, UUID processInstanceId) {
        return delegate.processInstanceTraversals(tenantId, processInstanceId);
    }
    @Override public java.time.Instant processInventoryRetainedFrom(String tenantId) {
        return delegate.processInventoryRetainedFrom(tenantId);
    }
    @Override public List<ExecutionEvent> executionEventsAfter(long sequence) {
        return delegate.executionEventsAfter(sequence);
    }
    @Override public AutoCloseable subscribeToExecutionEvents(Consumer<ExecutionEvent> listener) {
        return delegate.subscribeToExecutionEvents(listener);
    }
    @Override public boolean durableEventJournalAvailable() { return delegate.durableEventJournalAvailable(); }
    @Override public List<DurableExecutionEvent> durableEventsAfter(String tenantId, long afterOffset, int limit) {
        return delegate.durableEventsAfter(tenantId, afterOffset, limit);
    }
    @Override public boolean executionResultsRetained() { return delegate.executionResultsRetained(); }
    @Override public ExecutionLookup executionResult(String tenantId, UUID executionId) {
        return delegate.executionResult(tenantId, executionId);
    }
    @Override public void close() { delegate.close(); }
}
