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
 * @param violations the semantic violations a {@code GraphDefinition} build against this document
 *                   found; empty when the document satisfies a graph's minimal structure
 */
public record GraphSummary(int nodes, int edges, int startNodes, int endNodes, List<String> violations) {

/**
 * Copies graph summary data into an immutable observation snapshot.
 */
    public GraphSummary {
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
    }

/**
 * Whether the document satisfies a graph's minimal semantic structure, not only its counts.
 * @return {@code true} only when this summary contains no semantic-structure violations.
 */
    public boolean valid() {
        return violations.isEmpty();
    }
}
