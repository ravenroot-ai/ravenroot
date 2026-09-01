package ai.ravenroot.server.audit;

import ai.ravenroot.api.programming.ArtifactLifecycleAuditEvent;
import ai.ravenroot.api.programming.ArtifactLifecycleAuditSink;

import java.io.PrintStream;
import java.util.Objects;

/** Payload/source-free JSON-lines lifecycle audit sink. */
public final class StructuredArtifactLifecycleLogger implements ArtifactLifecycleAuditSink {
    private final PrintStream output;

    public StructuredArtifactLifecycleLogger(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public void record(ArtifactLifecycleAuditEvent event) {
        Objects.requireNonNull(event, "event");
        output.println("{\"event\":\"artifact_lifecycle\",\"occurredAt\":\"" + event.occurredAt()
                + "\",\"requestId\":\"" + escape(event.requestId())
                + "\",\"subject\":\"" + escape(event.subject())
                + "\",\"tenantId\":\"" + escape(event.tenantId())
                + "\",\"action\":\"" + escape(event.action())
                + "\",\"artifactId\":\"" + escape(event.artifactId())
                + "\",\"sha256\":\"" + escape(event.sha256())
                + "\",\"state\":" + (event.state() == null ? "null" : "\"" + event.state() + "\"")
                + ",\"disposition\":\"" + event.disposition() + "\"}");
        if (output.checkError()) throw new IllegalStateException("artifact lifecycle audit output failed");
    }

    /** {@link JsonStrings} is the one implementation every sink in this module uses. */
    private static String escape(String value) {
        return JsonStrings.escape(value);
    }
}
