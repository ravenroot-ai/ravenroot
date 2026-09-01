package ai.ravenroot.core.graph;

import java.util.List;
import java.util.Objects;

/**
 * What a GraphML document declared, read against the format profile this runtime implements
 * (INT-05).
 *
 * <h2>Why a report and not a boolean</h2>
 * <p>An import that returns only "accepted" is indistinguishable from an import that quietly threw
 * half the document away. Ravenroot's GraphML contract is deliberately generous — anything outside
 * the interpreted vocabulary is kept byte-for-byte and never given meaning (see
 * {@link ReservedGraphProperties}) — and generosity of that shape is exactly what makes a loss
 * silent: a key nobody understood produces no error, no warning and no difference in behaviour, so
 * the first time anyone learns their document said something Ravenroot ignored is when the graph
 * runs differently from how it reads.</p>
 *
 * <p>This record is the enumeration of that gap. It names every declared key, says which of the four
 * dispositions it fell into, and counts the constructs that survive the round trip without ever
 * reaching the property graph. "Without silent loss" is then a property something can be checked
 * against rather than a claim in a document.</p>
 *
 * <h2>The profile integer</h2>
 * <p>{@link #PROFILE} is the profile <em>this runtime implements</em>. It is an integer and not a
 * semantic version on purpose: semver is a vocabulary of range promises, while this report only
 * identifies the exact profile implemented by the current runtime.</p>
 *
 * <p><strong>It is not written into any document.</strong> Whether files Ravenroot authors should
 * carry a version declaration is outside this report's contract. This constant exists so a
 * report can say which reading produced it; a runtime-side constant commits nobody to a file format
 * change, whereas a marker in a document would be the product surface that decision is about.</p>
 *
 * <h2>Channel</h2>
 * <p>Every string in this record comes from the document. It therefore belongs to the same channel
 * class as {@link GraphMlRejectionDetail#diagnosticDetail()} and not to the one
 * {@link Throwable#getMessage()} belongs to: it is safe on an operator's own console, where the
 * operator is also the author of the file, and it is <em>not</em> safe to echo back to whoever
 * submitted a document over a network boundary. {@code ravenroot-cli} is the former; the HTTP
 * adapter would be the latter, and nothing here is wired into it.</p>
 *
 * @param profile                  the profile the reading runtime implements; always {@link #PROFILE}
 * @param nodes                    nodes the property graph actually received
 * @param edges                    edges the property graph actually received
 * @param keys                     every {@code <key>} the document declared, in document order
 * @param opaqueDataValues         {@code <data>} values carrying element children rather than a
 *                                 scalar; preserved in the authoritative bytes, never mapped
 * @param foreignNamespaceElements elements outside the GraphML namespace; same treatment
 * @param synthesizedEdgeIds       edges the author left unnamed, which carry an import-only handle
 *                                 that is never serialized back (see {@code GraphMlDocument})
 * @param violations               the semantic violations a {@link GraphDefinition} build against
 *                                 this document would raise, named rather than thrown; empty
 *                                 when the document is a valid graph, not only a well-formed one
 */
public record GraphMlProfileReport(
        int profile,
        long nodes,
        long edges,
        List<DeclaredKey> keys,
        int opaqueDataValues,
        int foreignNamespaceElements,
        int synthesizedEdgeIds,
        List<String> violations,
        List<String> redundancies) {

    /** The GraphML profile this runtime reads and writes. See the class comment before changing it. */
    public static final int PROFILE = 0;

    public GraphMlProfileReport {
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
        redundancies = List.copyOf(Objects.requireNonNull(redundancies, "redundancies"));
    }

    /**
     * Whether this document is a valid {@link GraphDefinition}, not only a document profile 0 could
     * read. {@code validateGraphMl}'s whole point is to answer this without paying the price
     * of an import: raising the answer instead of naming it would take the profile below with it,
     * which is exactly the case a document that is well-formed but not executable needs kept open.
     */
    public boolean valid() {
        return violations.isEmpty();
    }

    /**
     * Same report, carrying the semantic violations a {@link GraphDefinition} build against the
     * document found. Package-private: {@link GraphMlDocument} knows nothing about semantic
     * validity at parse time, so it always reports {@code List.of()} here; {@link GraphManager} is
     * the caller with both the parsed document and the built definition, so it is the one that
     * attaches the answer.
     */
    GraphMlProfileReport withFindings(List<String> violations, List<String> redundancies) {
        return new GraphMlProfileReport(profile, nodes, edges, keys, opaqueDataValues,
                foreignNamespaceElements, synthesizedEdgeIds, violations, redundancies);
    }

    /**
     * What the document says that says nothing, reported and never refused.
     *
     * <p>A separate list from {@link #violations()} because the two call for opposite responses and
     * merging them would make {@link #valid()} false for a document that is perfectly valid. Today
     * this carries {@code joinPolicy=each} in a document declaring
     * {@code join.semantics=declared} — legal, because recorded graphs carry the name and breaking a
     * serialized name is a migration for zero information, and redundant, because an undeclared node
     * already runs once per arrival — and a semantics marker whose value selects nothing.</p>
     *
     * <p>Same channel discipline as {@link #violations()}: every string here is derived from the
     * document, so it goes where document-derived text is already allowed to go and nowhere else.</p>
     */
    public List<String> redundancies() {
        return redundancies;
    }

    /** What profile 0 does with the property a key declares. */
    public enum Disposition {
        /**
         * The key names one of the executable fields the runtime acts on: {@code kind},
         * {@code behavior}, {@code outcome}, {@code command}, at a scope that admits nodes or edges.
         */
        INTERPRETED,

        /**
         * The key is mapped into the property graph, or kept on the graph element, and carried
         * through an export unchanged — but nothing in Ravenroot reads it. This is the disposition
         * the report exists for: it is the one that is invisible at runtime.
         */
        PRESERVED,

        /**
         * The key declares a GraphML scope that has no place in a directed property graph
         * ({@code port}, {@code hyperedge}, {@code endpoint}, {@code graphml}). The declaration is
         * inert and stays in the authoritative document; it is stripped from the imported view.
         * Actual {@code <port>}, {@code <hyperedge>} and {@code <locator>} elements remain refused.
         */
        DECLARED_OUTSIDE_PROPERTY_GRAPH,

        /**
         * The key names a property inside Ravenroot's reserved {@code ravenroot.} namespace and the
         * document was <em>not</em> refused for it.
         *
         * <p>SEC-09 refuses reserved properties at ingest, but it does so by walking the imported
         * vertices and edges, so the refusal only fires where a reserved property actually became a
         * property. A reserved key declared and never used, or declared at graph scope, reaches the
         * property graph as nothing at all and is therefore preserved instead of refused. That is a
         * real gap in fail-closed behaviour and this disposition is how a reader finds out about it
         * rather than inferring it from silence — closing it changes the SEC-09 contract and is not
         * this report's decision to make.
         */
        RESERVED_NOT_REFUSED
    }

    /**
     * One {@code <key>} declaration as profile 0 read it.
     *
     * @param id           the key id the document used
     * @param name         the effective property name ({@code attr.name}, falling back to the id)
     * @param type         the declared scalar type ({@code attr.type}, defaulting to {@code string})
     * @param scope        the declared {@code for} scope, defaulting to {@code all}
     * @param disposition  what profile 0 does with it
     * @param opaqueValues how many {@code <data>} values for this key carry element children
     */
    public record DeclaredKey(
            String id, String name, String type, String scope, Disposition disposition,
            int opaqueValues) {

        public DeclaredKey {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(disposition, "disposition");
        }
    }

    /** The declared keys with one disposition, in document order. */
    public List<DeclaredKey> keysWith(Disposition disposition) {
        Objects.requireNonNull(disposition, "disposition");
        return keys.stream().filter(key -> key.disposition() == disposition).toList();
    }

    /** Every key the document declared that Ravenroot does not act on. */
    public List<DeclaredKey> uninterpretedKeys() {
        return keys.stream().filter(key -> key.disposition() != Disposition.INTERPRETED).toList();
    }
}
