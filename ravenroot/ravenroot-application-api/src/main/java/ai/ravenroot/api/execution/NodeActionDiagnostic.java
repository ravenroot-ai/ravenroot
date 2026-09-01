package ai.ravenroot.api.execution;

import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.application.RuntimeActivityData.OutputProjection;

import java.util.Objects;

/**
 * A typed author-display action emitted by a node behavior, separate from graph payload and routing.
 *
 * <p>The runtime consumes this once when it emits the node's activity event and does not carry it to
 * successor nodes. A free-form attribute cannot substitute for this type: attributes are graph data
 * and may be authored or rewritten by any node.</p>
* @param kind trusted author-display action kind
* @param output trusted value projected for author display
 */
public record NodeActionDiagnostic(Kind kind, OutputProjection output) {
    /** Trusted author-display action kinds. */
public enum Kind { /** Emits a bounded value in the authenticated author activity view. */
LOG }

    /** Requires a supported action kind and a bounded output projection. */
public NodeActionDiagnostic {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(output, "output");
    }

    /**
 * The exact rendered value the trusted log behavior intentionally emitted.
* @param output trusted value projected for author display
* @return typed log action containing a bounded output projection
 */
    public static NodeActionDiagnostic log(Object output) {
        return new NodeActionDiagnostic(Kind.LOG, RuntimeActivityData.output(output));
    }
}
