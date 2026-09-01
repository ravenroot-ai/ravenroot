package ai.ravenroot.core.graph;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single declared sanitisation policy for every public GraphML rejection (FIX-03).
 *
 * <h2>The asymmetry this exists to remove</h2>
 * <p>{@link SecureGraphMlParser} rejected untrusted documents with sentences it had authored itself,
 * while {@link GraphMlDocument} interpolated the document straight back into the rejection —
 * {@code GraphML key 'k0' declares unsupported scalar type '>tring'}. While the compatibility layer
 * was internal that was tolerable. Under SEC-08 both are public, caller-observable rejection
 * paths, so the difference became a real information-disclosure asymmetry on untrusted input.</p>
 *
 * <p>Fixing only the wording of the compatibility layer would have left two implementations that
 * happen to agree, which is the arrangement that produced the divergence in the first place. So the
 * policy is declared once, here, and is the <em>only</em> way either layer can build a rejection.</p>
 *
 * <h2>The policy</h2>
 * <ol>
 *   <li><b>A public message is assembled exclusively from text authored in this file.</b> Every
 *       sentence is a {@link Sentence} constant and every substitutable noun is a {@link Term}
 *       constant. Neither layer can pass a {@code String}, so interpolating document content into a
 *       public message is not something a caller can get wrong — it is not expressible.</li>
 *   <li><b>Everything derived from the document is a {@link Detail}.</b> Details reach
 *       {@link #diagnosticDetail()} and never {@link Throwable#getMessage()}. This includes
 *       identifiers the author chose. A key id, node id or edge id in a <em>rejected</em> document is
 *       a string the submitter wrote which nothing has validated, echoed at the exact moment the
 *       server decided the input was not trustworthy; treating it as "necessary context" would give
 *       the policy a carve-out, and a carve-out is how the original asymmetry arose. Internal handles
 *       are covered by the same rule from the other direction: a synthesized edge id
 *       ({@code GraphMlDocument#SYNTHESIZED_EDGE_ID_PREFIX}) is not the caller's business either, so
 *       it is a detail too and can no longer surface as it did once FIX-01 introduced synthesized edge ids.</li>
 *   <li><b>Sanitised means replaced, not shortened or escaped.</b> Truncating leaves attacker content
 *       in the response, and escaping only addresses injection into a rendering context, not
 *       disclosure. A public message therefore names the rule that was broken and the <em>class</em>
 *       of the offending construct, never its content.</li>
 *   <li><b>Every rejection carries an {@link #incidentId()}</b>, present in the response and in the
 *       server-side record, so the two can be joined. Diagnosis stays possible without the response
 *       carrying the document.</li>
 * </ol>
 *
 * <h2>Why a new handle rather than {@code RequestContext.requestId}</h2>
 * <p>{@link ai.ravenroot.api.security.RequestContext} carries a {@code requestId}, but it exists only
 * on the HTTP path. GraphML is also imported by {@code RavenrootCli} and by embedded callers, which
 * have no request at all, and {@link GraphManager#readGraphMl} takes no context. A correlation handle
 * absent from two of the three entry paths cannot be the one the policy declares. The incident id is
 * minted where the rejection is built, so it exists on every path, and it is one-to-one with the
 * rejection rather than with the request — a request that submits several documents produces several.
 * The HTTP layer records both, so a response can still be traced back to its request.</p>
 *
 * <h2>Where the detail goes</h2>
 * <p>{@code ravenroot-core} has no logging framework and must not depend on {@code ravenroot-server},
 * so the policy does not own a sink: it puts the detail on the exception and each entry point records
 * it the way that module already records things. In production the HTTP server routes it into the
 * SEC-13 durable audit trail ({@code ai.ravenroot.server.audit.AuditTrailGraphMlRejectionSink})
 * rather than a plain log line — that is what actually satisfies "must only ever reach a server-side
 * sink" in the sense of control rather than merely of topology; an embedder that never configures an
 * audit trail still gets the JSON-lines stdout default it always had
 * ({@code ai.ravenroot.server.audit.StructuredGraphMlRejectionLogger}). The CLI prints it to its own
 * error stream, which is already the operator's console.</p>
 */
final class GraphMlRejection {

    /**
     * A noun this policy authored and permits inside a public message. Being an enum is the point:
     * the substitution slot cannot be reached with a {@code String}, so a resource name taken from
     * the document cannot occupy it.
     */
    enum Term {
        XML_DEPTH("XML depth"),
        ELEMENT_COUNT("element count"),
        ATTRIBUTE_COUNT("attribute count"),
        NAMESPACE_DECLARATION_COUNT("namespace declaration count"),
        NODE_COUNT("node count"),
        EDGE_COUNT("edge count"),
        PROPERTY_COUNT("property count"),
        KEY_COUNT("key count"),
        ELEMENT_NAME("element name"),
        ELEMENT_PREFIX("element prefix"),
        ELEMENT_NAMESPACE("element namespace"),
        ATTRIBUTE_NAME("attribute name"),
        ATTRIBUTE_PREFIX("attribute prefix"),
        ATTRIBUTE_NAMESPACE("attribute namespace"),
        ATTRIBUTE_VALUE("attribute value"),
        NAMESPACE_PREFIX("namespace prefix"),
        NAMESPACE_URI("namespace URI"),
        PROCESSING_INSTRUCTION_TARGET("processing instruction target"),
        PROCESSING_INSTRUCTION_DATA("processing instruction data"),
        TEXT_VALUE("text value"),
        GRAPHML_ROOT("graphml root"),
        GRAPH("graph"),
        KEY("key"),
        NODE("node"),
        EDGE("edge"),
        DATA("data"),
        HYPEREDGE("hyperedge"),
        PORT("port"),
        LOCATOR("locator");

        private final String text;

        Term(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }

        /**
         * Maps an element name the parser has already confirmed is one of the interpreted GraphML
         * names onto its term. The name arrives from the document, so it is looked up rather than
         * used: an unexpected value is a programming error here, not a document that gets to choose
         * its own message.
         */
        static Term forElementName(String localName) {
            for (Term term : values()) {
                if (term.text.equals(localName)) {
                    return term;
                }
            }
            throw new IllegalStateException("Element name is not part of the declared public vocabulary");
        }
    }

    /**
     * A complete public rejection sentence. {@code %s} is filled from a {@link Term} and from nothing
     * else. Adding a rejection path means adding a constant here, which is what keeps the policy
     * genuinely declared in one place rather than described in one place and applied in two.
     */
    enum Sentence {
        // --- SecureGraphMlParser: the XML security boundary (SEC-08) -------------------------
        DOCUMENT_BYTE_LIMIT("GraphML document exceeds the configured byte limit"),
        DOCUMENT_UNREADABLE("Cannot read GraphML document"),
        DOCUMENT_NOT_WELL_FORMED("GraphML document is not well formed"),
        COMPRESSED_ARCHIVE("GraphML document was refused because it is a compressed archive, not an "
                + "XML document"),
        DTD_OR_ENTITY("GraphML DTD and entity declarations are not allowed"),
        NON_CANONICAL_ROOT("GraphML document must have a canonical graphml root element"),
        REDEFINED_INTERPRETED_NAME("GraphML extensions cannot redefine interpreted element names"),
        NODE_IDENTIFIERS("GraphML node identifiers must be present and unique"),
        EDGE_ENDPOINTS_UNDECLARED("GraphML edges must reference declared nodes"),
        RESOURCE_LIMIT_EXCEEDED("GraphML %s exceeds the configured limit"),
        INVALID_ELEMENT_CONTEXT("GraphML %s element appears in an invalid context"),

        // --- GraphMlDocument: the property-graph compatibility layer (CORE-01) ---------------
        EMPTY_INPUT("GraphML input is empty"),
        ROOT_NOT_GRAPHML(
                "GraphML root must be {" + GraphMlDocument.GRAPHML_NAMESPACE + "}graphml"),
        NOT_EXACTLY_ONE_GRAPH("GraphML must contain exactly one top-level graph"),
        EDGEDEFAULT_NOT_DIRECTED("GraphML graph must declare edgedefault=\"directed\""),
        NESTED_GRAPHS("Nested GraphML graphs are not supported"),
        UNSUPPORTED_ELEMENT("GraphML %s elements are not supported by the directed property graph"),
        EDGE_OVERRIDES_DIRECTEDNESS("GraphML edge cannot override directedness"),
        KEYS_AFTER_GRAPH("GraphML key declarations must precede the graph"),
        KEY_MISSING_ID("GraphML key declaration is missing an id"),
        KEY_UNSUPPORTED_SCOPE("GraphML key declares an unsupported scope"),
        KEY_UNSUPPORTED_SCALAR_TYPE("GraphML key declares an unsupported scalar type"),
        KEY_MULTIPLE_DEFAULTS("GraphML key declares more than one default value"),
        DUPLICATE_KEY_ID("Duplicate GraphML key id"),
        AMBIGUOUS_PROPERTY_NAME("Ambiguous GraphML property name across two keys in the same scope"),
        DUPLICATE_NODE_ID("Duplicate GraphML node id"),
        DUPLICATE_EDGE_ID("Duplicate GraphML edge id"),
        EDGE_ID_LIMIT_EXCEEDED("GraphML edge id exceeds the stable identity limit"),
        EDGE_UNDECLARED_ENDPOINT("GraphML edge references an undeclared endpoint"),
        DATA_UNDECLARED_KEY("GraphML data references an undeclared key"),
        DATA_WRONG_OWNER("GraphML data must belong directly to a graph, node or edge"),
        DATA_OUT_OF_KEY_SCOPE("GraphML key scope does not admit the element carrying the data"),
        COMPLEX_DATA_COLLIDES("Complex GraphML data collides with an executable property"),
        COMPLEX_DEFAULT_AMBIGUOUS("Complex default for an executable GraphML property is ambiguous"),
        DUPLICATE_DATA_KEY("Duplicate GraphML data key on a single element"),
        SCALAR_VALUE_MISMATCH("GraphML scalar value does not match the type declared by its key"),
        ELEMENT_MISSING_ID("GraphML %s is missing an id"),
        UNSAFE_COMPATIBILITY_XML("Invalid or unsafe GraphML XML"),

        // --- GraphManager: semantic ingest rules over the mapped property graph --------------
        SCALAR_MAPPING_FAILED("Cannot map GraphML scalar properties to the property graph"),
        RESERVED_PROPERTY("GraphML %s declares a property in Ravenroot's reserved namespace; the '"
                + ReservedGraphProperties.PREFIX + "' namespace is Ravenroot's own operative state "
                + "and cannot be set by graph content. Properties outside it are preserved untouched "
                + "and never interpreted");

        private final String pattern;

        Sentence(String pattern) {
            this.pattern = pattern;
        }

        private String render(Term term) {
            if (pattern.contains("%s") == (term == null)) {
                throw new IllegalStateException("Rejection sentence and term do not agree");
            }
            return term == null ? pattern : pattern.replace("%s", term.text());
        }
    }

    /**
     * One labelled fragment taken from the document. The label is authored here; the value is not, so
     * it is never public.
     *
     * <p>The value is bounded and control characters are escaped even on the diagnostic side. The
     * detail is written into a JSON-lines audit stream, and a value containing a newline or a quote
     * would otherwise be able to forge a second record. The sink escapes as well; both do, because
     * the property that a detail cannot break its own record belongs to the detail.</p>
     */
    record Detail(String label, String value) {
        private static final int MAX_VALUE_LENGTH = 256;

        Detail {
            label = Objects.requireNonNull(label, "label");
            value = bound(value);
        }

        private static String bound(String raw) {
            if (raw == null) {
                return "<absent>";
            }
            String truncated = raw.length() > MAX_VALUE_LENGTH
                    ? raw.substring(0, MAX_VALUE_LENGTH) + "…<truncated>"
                    : raw;
            var builder = new StringBuilder(truncated.length());
            truncated.codePoints().forEach(codePoint -> {
                if (Character.isISOControl(codePoint)) {
                    builder.append(String.format(Locale.ROOT, "\\u%04x", codePoint));
                } else {
                    builder.appendCodePoint(codePoint);
                }
            });
            return builder.toString();
        }
    }

    private static final SecureRandom INCIDENT_SOURCE = new SecureRandom();

    private GraphMlRejection() {
    }

    static Detail detail(String label, String value) {
        return new Detail(label, value);
    }

    static Detail detail(String label, Object value) {
        return new Detail(label, value == null ? null : value.toString());
    }

    /** Builds a classified security-layer rejection. */
    static GraphMlParseException parseFailure(GraphMlParseException.Reason reason, Sentence sentence,
                                              Detail... details) {
        return parseFailure(reason, sentence, null, null, details);
    }

    /** Builds a classified security-layer rejection whose sentence names a declared term. */
    static GraphMlParseException parseFailure(GraphMlParseException.Reason reason, Sentence sentence,
                                              Term term, Detail... details) {
        return parseFailure(reason, sentence, term, null, details);
    }

    static GraphMlParseException parseFailure(GraphMlParseException.Reason reason, Sentence sentence,
                                              Term term, Throwable cause, Detail... details) {
        Objects.requireNonNull(reason, "reason");
        return new GraphMlParseException(reason, sentence.render(term), newIncidentId(),
                render(details), cause);
    }

    /** Builds a compatibility-layer rejection under exactly the same policy. */
    static GraphMlCompatibilityException compatibilityFailure(Sentence sentence, Detail... details) {
        return compatibilityFailure(sentence, null, null, details);
    }

    static GraphMlCompatibilityException compatibilityFailure(Sentence sentence, Term term,
                                                              Detail... details) {
        return compatibilityFailure(sentence, term, null, details);
    }

    static GraphMlCompatibilityException compatibilityFailure(Sentence sentence, Term term,
                                                              Throwable cause, Detail... details) {
        return new GraphMlCompatibilityException(sentence.render(term), newIncidentId(),
                render(details), cause);
    }

    /**
     * Every message this policy is capable of producing.
     *
     * <p>{@code GraphManagerSecurityTest}'s mutation corpus asserts that a rejection's message is a
     * member of this set. That turns "no untyped failure escapes" into "no <em>undeclared</em>
     * message escapes": a rejection path that assembled its own text, whether by bypassing this class
     * or by being added without a {@link Sentence}, fails the fuzzer instead of passing it.</p>
     */
    static Set<String> declaredPublicMessages() {
        var messages = new ArrayList<String>();
        for (Sentence sentence : Sentence.values()) {
            if (sentence.pattern.contains("%s")) {
                for (Term term : Term.values()) {
                    messages.add(sentence.render(term));
                }
            } else {
                messages.add(sentence.pattern);
            }
        }
        return Set.copyOf(messages);
    }

    private static String newIncidentId() {
        byte[] bytes = new byte[8];
        INCIDENT_SOURCE.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Map<String, String> render(Detail... details) {
        if (details == null || details.length == 0) {
            return Map.of();
        }
        var rendered = new LinkedHashMap<String, String>();
        for (Detail detail : details) {
            if (detail != null) {
                rendered.put(detail.label(), detail.value());
            }
        }
        return Map.copyOf(rendered);
    }

    /** Kept so the sentence list above can name the interpreted element it refers to. */
    static List<Term> unsupportedElements() {
        return List.of(Term.HYPEREDGE, Term.PORT, Term.LOCATOR);
    }
}
