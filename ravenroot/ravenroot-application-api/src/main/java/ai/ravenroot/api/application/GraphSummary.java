package ai.ravenroot.api.application;

import java.util.List;
import java.util.Objects;

/**
 * What {@code inspectGraphMl} found: how the document counted, and whether it is a valid graph.
 *
 * <p>{@code startNodes}/{@code endNodes} report counts, not a verdict. Previously they were the
 * whole answer, and a count staying inside a plausible range does not mean the graph is sound: a
 * document declaring a node kind Ravenroot does not know reports the same {@code startNodes=1,
 * endNodes=1} as a valid two-node graph (measured), because the unrecognised kind on the node in
 * between never shows up in either count. Counting is not the same question as "is this graph
 * structurally sound", and a caller reading only the counts could not tell the two apart.
 * {@link #violations} answers the second question directly, the same way
 * {@code GraphMlProfileReport#violations()} does for {@code validate}: named rather than merely
 * implied by a count that does not add up.</p>
 *
 * @param nodes      nodes the property graph actually received
 * @param edges      edges the property graph actually received
 * @param startNodes nodes declaring {@code kind=START}
 * @param endNodes   nodes declaring {@code kind=END}
 * @param violations compatibility projection of the deterministic primary refusal; empty when the
 *                   document is admitted for the requested purpose
 * @param findings   bounded structured primary finding; empty when the document is admitted
 */
public record GraphSummary(int nodes, int edges, int startNodes, int endNodes, List<String> violations,
                           List<GraphAdmissionFinding> findings) {

/**
 * Copies graph summary data into an immutable observation snapshot.
 */
    public GraphSummary {
        if (nodes < 0 || edges < 0 || startNodes < 0 || endNodes < 0) {
            throw new IllegalArgumentException("graph counts must not be negative");
        }
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (findings.size() > 1) throw new IllegalArgumentException("only one primary finding is supported");
    }

    /**
     * Compatibility constructor for callers compiled against the original summary shape.
     * @param nodes nodes the property graph received
     * @param edges edges the property graph received
     * @param startNodes nodes declaring {@code kind=START}
     * @param endNodes nodes declaring {@code kind=END}
     * @param violations deterministic compatibility violations
     */
    public GraphSummary(int nodes, int edges, int startNodes, int endNodes, List<String> violations) {
        this(nodes, edges, startNodes, endNodes, violations, List.of());
    }

/**
 * Whether the document satisfies the complete policy-independent admission pipeline.
 * @return {@code true} only when this summary contains no admission violations or findings.
 */
    public boolean valid() {
        return violations.isEmpty() && findings.isEmpty();
    }
}
