package ai.ravenroot.core.graph;

import java.util.Locale;

/**
 * The property namespace a graph may not write into (SEC-09).
 *
 * <h2>Reserved and unknown are different things</h2>
 * <p>SEC-09 requires two behaviours that read as opposites until the namespace is what separates
 * them. An <strong>unknown</strong> property — {@code owner}, {@code custom.string}, a third-party
 * editor's {@code viz:} extension — is data Ravenroot has no opinion about. It is preserved
 * byte-for-byte across a round trip and never interpreted, and rejecting it would break the lossless
 * import/export contract that {@code GraphMlCorpusTest} pins.</p>
 *
 * <p>A <strong>reserved</strong> property is different in kind: it is graph content claiming to be
 * Ravenroot's own operative state. There is no legitimate reason for an authoring tool to write one,
 * and the only thing such a property can achieve is to look authoritative to somebody — a node author
 * reading {@code node.properties()}, a template rendering {@code {{properties.*}}}, a log line, or a
 * human. So it is refused at ingest rather than preserved.</p>
 *
 * <p>The rule is therefore: <em>reserved is refused, everything else is preserved</em>. "Unknown" is
 * not the axis; provenance is.</p>
 *
 * <h2>Why the whole {@code ravenroot.} prefix, not just {@code ravenroot.security.}</h2>
 * <p>SEC-07 reserved only {@link ai.ravenroot.api.security.SecurityContext#RESERVED_KEY_PREFIX}
 * ({@code ravenroot.security.}) here, deliberately narrowly, because at that point nothing had
 * established a namespace policy and over-reserving risked breaking preservation. SEC-09 establishes
 * the policy, so the prefix widens to cover every operative namespace rather than only the identity
 * one.</p>
 *
 * <p>The cost is accepted knowingly: a graph authored by a <em>newer</em> Ravenroot that introduces
 * a new {@code ravenroot.*} property is refused by an older runtime rather than silently preserved.
 * For an operative namespace that is the correct direction — an older runtime that cannot honour a
 * property it does not understand should say so rather than run the graph as if the property were
 * absent, which is the failure mode that makes version skew undiagnosable.</p>
 *
 * <p>This is a <em>semantic</em> rule about what a graph may declare, which is why it lives here and
 * not in {@link SecureGraphMlParser}: that class owns XML-level safety and document budgets (SEC-08)
 * and has no notion of Ravenroot namespaces.</p>
 */
public final class ReservedGraphProperties {

    /**
     * Namespace prefix reserved for Ravenroot's own operative property state on graph nodes and edges.
     * A strict superset of {@link ai.ravenroot.api.security.SecurityContext#RESERVED_KEY_PREFIX}.
     */
    public static final String PREFIX = "ravenroot.";

    private ReservedGraphProperties() {
    }

    /**
     * Whether {@code key} falls in the reserved namespace and must be refused from graph content.
     *
     * <p>Matching is case-insensitive and whitespace-trimmed. XML attribute names are case-sensitive,
     * so {@code RavenRoot.Security.TenantId} is a <em>different</em> key to a strict parser while
     * being indistinguishable to every human reading the document and to any consumer that
     * lower-cases before comparing. Refusing both is the only reading that cannot be walked past.</p>
     */
    public static boolean isReserved(String key) {
        return key != null && key.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }
}
