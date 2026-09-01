package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeRuntimeNature;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.programming.ArtifactRegistry;
import ai.ravenroot.api.programming.ArtifactState;
import ai.ravenroot.api.programming.ProgramRequest;
import ai.ravenroot.api.programming.ProgramRuntime;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProgramNodeBehaviorFactory implements NodeBehaviorFactory {
    private final ArtifactRegistry artifacts;
    private final ProgramRuntime runtime;
    private final ToolPolicy toolPolicy;

    ProgramNodeBehaviorFactory(ArtifactRegistry artifacts, ProgramRuntime runtime, ToolPolicy toolPolicy) {
        this.artifacts = artifacts;
        this.runtime = runtime;
        this.toolPolicy = toolPolicy;
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("program", "Program artifact", "Programming",
                "Builds and executes tenant-scoped source through the governed sandbox runtime.",
                "handler", false, List.of(
                NodePropertyDescriptor.required("language", "Language", NodePropertyType.STRING,
                        "Required runtime language identifier."),
                NodePropertyDescriptor.required("source", "Source", NodePropertyType.TEXT,
                        "Exact UTF-8 source; content identity is calculated by the server."),
                NodePropertyDescriptor.optional("testPayload", "Test payload", NodePropertyType.TEXT,
                        "Strict JSON becomes structured smoke input; other text remains literal.",
                        ai.ravenroot.api.programming.ProgramTestPayload.DEFAULT_TEXT),
                NodePropertyDescriptor.optional("artifactId", "Artifact ID", NodePropertyType.STRING,
                        "Managed read-only audit reference; never content or lifecycle authority.", "")),
                Set.of("programmable", "artifact-governed", "sandbox-required"))
                .withNature(NodeRuntimeNature.WORKER,
                        Set.of(NodeRuntimeNature.WORKER, NodeRuntimeNature.TRAVERSAL))
                .withOutcomes(NodeOutcomeDescriptor.literal("continue",
                        "The artifact ran and its result becomes the outgoing payload. The only outcome "
                                + "this node produces: a sandbox or artifact error fails the node."));
    }

    /**
     * SEC-12's TOCTOU half, carrying SEC-25's tenant check because it falls at the same
     * point in this same method.
     *
     * <p><b>What was here before.</b> {@code artifacts.find(artifactId)} took an immutable snapshot,
     * {@code state() != ACTIVE} was checked against that snapshot, and the snapshot was then handed to
     * {@code runtime.execute}. The check and the use were separate acts. {@code GraalVmProgramRuntime}
     * re-checked {@code requireState} on arrival, but on the same record, so the second check could
     * never observe anything the first had not -- two checks, one observation. Meanwhile the sandbox
     * work runs on a virtual thread: measured on this exact path, ~671 ms idle and ~5.4 s
     * at 8x CPU oversubscription elapse between admission and the source reaching the worker. A
     * retirement completes comfortably inside that.
     *
     * <p>The snapshot was also <b>tenant-blind</b> (SEC-25). This method contained no reference
     * to a tenant in any form, and {@code OWNER_TENANT_METADATA} is read only on the
     * {@code AuthorizedRavenrootApplication} reference-monitor path, which a graph run does not take.
     * One tenant's graph could name another tenant's ACTIVE artifact and execute it.
     *
     * <p><b>What replaces it.</b> {@link ArtifactRegistry#admitForExecution} returns a handle whose
     * {@link ProgramAdmission#redeem()} the runtime calls immediately before writing the source to the
     * worker, re-reading authoritative state under the registry's own per-artifact lock and checking
     * owner tenant, ACTIVE, and unchanged revision. There is no interval between the check and the
     * use because the check is the acquisition. There is deliberately <b>no</b> state check left in
     * this method: one here would be a second stale read, and its passing would say nothing.
     */
    @Override
    public NodeHandler create(GraphNode node) {
        String language = NodeProperties.required(node, "language");
        Object rawSource = node.properties().get("source");
        String source = rawSource == null ? "" : rawSource.toString();
        if (source.isBlank()) throw new IllegalArgumentException("Node " + node.id() + " requires property 'source'");
        String sourceDigest = ai.ravenroot.api.programming.ProgramArtifactIdentity.sha256(language, source);
        return message -> {
            String artifactId = artifacts.findByTenantAndDigest(message.tenantId(), sourceDigest)
                    .filter(candidate -> candidate.state() == ArtifactState.ACTIVE)
                    .filter(candidate -> compatible(candidate.metadata().get(
                            "ravenroot.program.compatibilityFingerprint"), runtime.compatibilityFingerprint()))
                    .map(ai.ravenroot.api.programming.GeneratedArtifact::id)
                    .orElseThrow(() -> new SecurityException("Program content is not ready for tenant "
                            + message.tenantId() + ": " + node.id()));
            var admission = artifacts.admitForExecution(message.tenantId(), artifactId);
            ToolAuthorization.requireAllowed(toolPolicy, message, "program.execute",
                    Map.of("artifactId", artifactId, "runtime", runtime.id()));
            // id and sha256 are invariant across an artifact's whole lifecycle -- a transition copies
            // both verbatim -- so recording them from the snapshot is sound. Nothing is GATED on it.
            var recorded = admission.unverifiedSnapshot();
            return runtime.execute(admission, new ProgramRequest(message.executionId(), node.id(),
                    message.payload(), message.attributes()))
                    .thenApply(payload -> new NodeResult("continue", payload,
                    NodeProperties.attributes(message, "program.artifact", recorded.id(),
                            "program.sha256", recorded.sha256(), "program.runtime", runtime.id())));
        };
    }

    /** Blank accepts artifacts activated through the legacy explicit lifecycle; Build always records it. */
    private static boolean compatible(String recorded, String current) {
        if (recorded == null || recorded.isBlank()) return true;
        return java.security.MessageDigest.isEqual(
                recorded.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                current.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
