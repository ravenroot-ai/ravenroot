package ai.ravenroot.core.graph;

import ai.ravenroot.api.application.StableEdgeId;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.ravenroot.core.graph.GraphMlRejection.Sentence;
import static ai.ravenroot.core.graph.GraphMlRejection.Term;
import static ai.ravenroot.core.graph.GraphMlRejection.compatibilityFailure;
import static ai.ravenroot.core.graph.GraphMlRejection.detail;

/**
 * Validates the GraphML subset that has unambiguous property-graph semantics and creates a
 * TinkerPop-readable view. The original bytes remain the authoritative serialization.
 */
final class GraphMlDocument {
    static final String GRAPHML_NAMESPACE = "http://graphml.graphdrawing.org/xmlns";
    private static final Set<String> SCALAR_TYPES =
            Set.of("boolean", "int", "long", "float", "double", "string");
    private static final Set<String> RESERVED_PROPERTIES =
            Set.of(GraphManager.KIND, GraphManager.BEHAVIOR, GraphManager.OUTCOME, GraphManager.COMMAND);
    /**
     * The {@code for} scopes that reach the imported property graph at all. Every other declared
     * scope is inert: kept in the authoritative document, removed from the TinkerPop view.
     */
    private static final Set<String> PROPERTY_GRAPH_SCOPES = Set.of("all", "node", "edge", "graph");
    /** Package-private so the corpus fixture that pins the collision case can reference it. */
    static final String SYNTHESIZED_EDGE_ID_PREFIX = "ravenroot-synthesized-edge-";

    private final byte[] original;
    private final byte[] propertyGraphView;
    private final List<GraphMlProfileReport.DeclaredKey> declaredKeys;
    private final int opaqueDataValues;
    private final int foreignNamespaceElements;
    private final int synthesizedEdgeIds;
    private final Map<String, Object> graphProperties;

    private GraphMlDocument(byte[] original, byte[] propertyGraphView,
                            List<GraphMlProfileReport.DeclaredKey> declaredKeys, int opaqueDataValues,
                            int foreignNamespaceElements, int synthesizedEdgeIds,
                            Map<String, Object> graphProperties) {
        this.original = original;
        this.propertyGraphView = propertyGraphView;
        this.declaredKeys = List.copyOf(declaredKeys);
        this.opaqueDataValues = opaqueDataValues;
        this.foreignNamespaceElements = foreignNamespaceElements;
        this.synthesizedEdgeIds = synthesizedEdgeIds;
        this.graphProperties = Map.copyOf(graphProperties);
    }

    static GraphMlDocument read(byte[] input) {
        if (input.length == 0) {
            throw compatibilityFailure(Sentence.EMPTY_INPUT);
        }
        Document document = parse(input);
        Element root = document.getDocumentElement();
        if (root == null || !"graphml".equals(root.getLocalName())
                || !GRAPHML_NAMESPACE.equals(root.getNamespaceURI())) {
            throw compatibilityFailure(Sentence.ROOT_NOT_GRAPHML,
                    detail("rootLocalName", root == null ? null : root.getLocalName()),
                    detail("rootNamespace", root == null ? null : root.getNamespaceURI()));
        }

        List<Element> rootGraphs = directChildren(root, GRAPHML_NAMESPACE, "graph");
        if (rootGraphs.size() != 1) {
            throw compatibilityFailure(Sentence.NOT_EXACTLY_ONE_GRAPH,
                    detail("topLevelGraphCount", rootGraphs.size()));
        }
        Element graph = rootGraphs.getFirst();
        if (!"directed".equals(graph.getAttribute("edgedefault"))) {
            throw compatibilityFailure(Sentence.EDGEDEFAULT_NOT_DIRECTED,
                    detail("graphId", rawId(graph)),
                    detail("edgedefault", graph.getAttribute("edgedefault")));
        }
        rejectUnsupportedStructure(document, graph);
        int synthesizedEdgeIds = resolveEdgeIdentity(graph);

        Map<String, KeyDefinition> keys = readKeys(root, graph);
        validateTopology(graph);
        validateData(document, keys);

        // Built from the validated DOM before the property-graph view strips anything from its
        // clone, so the report describes the document as written rather than what survived the
        // stripping — which is the whole point of reporting it (INT-05).
        List<GraphMlProfileReport.DeclaredKey> declaredKeys = describeKeys(document, keys);
        int opaqueDataValues = countOpaqueDataValues(document);
        int foreignNamespaceElements = countForeignNamespaceElements(document);

        Map<String, Object> graphProperties = readGraphProperties(graph, keys);

        Document propertyGraphDocument = (Document) document.cloneNode(true);
        preparePropertyGraphView(propertyGraphDocument, keys);
        return new GraphMlDocument(input.clone(), serialize(propertyGraphDocument), declaredKeys,
                opaqueDataValues, foreignNamespaceElements, synthesizedEdgeIds, graphProperties);
    }

    byte[] original() {
        return original.clone();
    }

    /** What the {@code <graph>} element declared about itself. */
    Map<String, Object> graphProperties() {
        return graphProperties;
    }

    /**
     * Graph-scoped {@code <data>}, read here because nothing downstream can read it.
     *
     * <p>TinkerPop's {@code GraphMLReader} builds a property graph out of vertices and edges and has
     * no place to put a datum that belongs to the graph itself, so it drops such a {@code <data>}
     * silently. Measured before this method existed: a document declaring
     * {@code <key for="graph" attr.name="join.semantics"/>} imports cleanly, is reported as a
     * {@code PRESERVED} key, exports back byte for byte — and is invisible in the resulting
     * {@link GraphDefinition}. The value is therefore lifted off the validated DOM here, on the same
     * pass that already validates its key scope and its scalar type, and carried alongside the graph
     * rather than through it.</p>
     *
     * <p><strong>A key in Ravenroot's reserved namespace is excluded from this map rather than
     * refused</strong>, and the difference matters. {@code GraphMlProfileReport.Disposition
     * #RESERVED_NOT_REFUSED} records, deliberately and with a test pinning it, that SEC-09's ingest
     * refusal walks vertices and edges and therefore never fires on a graph-scoped reserved key;
     * closing that gap would change the SEC-09 contract and is outside this method's scope. This method
     * must not widen the gap: previously a graph-scoped {@code ravenroot.} key reached the property
     * graph as nothing at all, and it still does. It is preserved in the document's bytes and
     * reported by the profile, exactly as before, and simply never becomes interpreted graph
     * state.</p>
     *
     * <p>Only scalar values, and only the two owners GraphML allows: an explicit {@code <data>} on
     * the {@code <graph>} element, or a graph-scoped {@code <key>}'s {@code <default>} when the
     * document declared no explicit value — the same precedence
     * {@link #preparePropertyGraphView} applies to node and edge defaults. A {@code <data>} with
     * element children is opaque content this layer preserves in the bytes and does not interpret,
     * exactly as it does for nodes and edges.</p>
     */
    private static Map<String, Object> readGraphProperties(Element graph, Map<String, KeyDefinition> keys) {
        var properties = new LinkedHashMap<String, Object>();
        var explicit = new HashSet<String>();
        for (Element data : directChildren(graph, GRAPHML_NAMESPACE, "data")) {
            KeyDefinition key = keys.get(data.getAttribute("key").trim());
            if (key == null || hasElementChildren(data) || ReservedGraphProperties.isReserved(key.name())) {
                continue;
            }
            explicit.add(key.id());
            properties.put(key.name(), data.getTextContent().trim());
        }
        for (KeyDefinition key : keys.values()) {
            if (!"graph".equals(key.scope()) || explicit.contains(key.id())
                    || ReservedGraphProperties.isReserved(key.name())
                    || key.defaultElement() == null || hasElementChildren(key.defaultElement())) {
                continue;
            }
            properties.put(key.name(), key.defaultElement().getTextContent().trim());
        }
        return Map.copyOf(properties);
    }

    /**
     * The declaration half of {@link GraphMlProfileReport}. Node and edge counts are supplied by the
     * caller because they are a property of the graph that was actually imported, not of the XML:
     * counting {@code <node>} elements here would be a second source of truth for the same number,
     * and a report whose counts can disagree with the graph is worse than no report.
     */
    GraphMlProfileReport report(long nodes, long edges) {
        // No opinion on semantic validity at this layer -- this class only knows what the document
        // declared, never whether it is a valid GraphDefinition. GraphManager.validateGraphMl is the
        // caller that has both the parsed document and a built definition, and attaches the answer
        // via GraphMlProfileReport#withFindings.
        return new GraphMlProfileReport(GraphMlProfileReport.PROFILE, nodes, edges, declaredKeys,
                opaqueDataValues, foreignNamespaceElements, synthesizedEdgeIds, List.of(), List.of());
    }

    private static List<GraphMlProfileReport.DeclaredKey> describeKeys(
            Document document, Map<String, KeyDefinition> keys) {
        var opaqueValuesByKey = new HashMap<String, Integer>();
        for (Element data : elements(document, GRAPHML_NAMESPACE, "data")) {
            if (hasElementChildren(data)) {
                opaqueValuesByKey.merge(data.getAttribute("key").trim(), 1, Integer::sum);
            }
        }
        var described = new ArrayList<GraphMlProfileReport.DeclaredKey>();
        for (KeyDefinition key : keys.values()) {
            described.add(new GraphMlProfileReport.DeclaredKey(key.id(), key.name(), key.type(),
                    key.scope(), disposition(key), opaqueValuesByKey.getOrDefault(key.id(), 0)));
        }
        return described;
    }

    /**
     * The reserved check comes first, and deliberately so. A {@code ravenroot.*} key that reaches
     * this point is one SEC-09's ingest refusal did not fire on — it declared a scope that never
     * becomes a vertex or edge property, or it was declared and never used — so it is preserved like
     * any other unknown key. Classifying it as merely {@code PRESERVED} would report the fact and
     * hide the thing that matters about it.
     */
    private static GraphMlProfileReport.Disposition disposition(KeyDefinition key) {
        if (ReservedGraphProperties.isReserved(key.name())) {
            return GraphMlProfileReport.Disposition.RESERVED_NOT_REFUSED;
        }
        if (!PROPERTY_GRAPH_SCOPES.contains(key.scope())) {
            return GraphMlProfileReport.Disposition.DECLARED_OUTSIDE_PROPERTY_GRAPH;
        }
        if (RESERVED_PROPERTIES.contains(key.name().toLowerCase(Locale.ROOT))
                && !targetScopes(key.scope()).isEmpty()) {
            return GraphMlProfileReport.Disposition.INTERPRETED;
        }
        return GraphMlProfileReport.Disposition.PRESERVED;
    }

    private static int countOpaqueDataValues(Document document) {
        int opaque = 0;
        for (Element data : elements(document, GRAPHML_NAMESPACE, "data")) {
            if (hasElementChildren(data)) {
                opaque++;
            }
        }
        return opaque;
    }

    private static int countForeignNamespaceElements(Document document) {
        var all = document.getElementsByTagName("*");
        int foreign = 0;
        int length = all.getLength();
        for (int index = 0; index < length; index++) {
            if (!GRAPHML_NAMESPACE.equals(((Element) all.item(index)).getNamespaceURI())) {
                foreign++;
            }
        }
        return foreign;
    }

    ByteArrayInputStream propertyGraphInput() {
        return new ByteArrayInputStream(propertyGraphView);
    }

    private static Document parse(byte[] input) {
        try {
            var factory = DocumentBuilderFactory.newDefaultNSInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(input));
        } catch (SAXException | IOException exception) {
            throw compatibilityFailure(Sentence.UNSAFE_COMPATIBILITY_XML, null, exception);
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("Cannot configure the GraphML parser", exception);
        }
    }

    private static void rejectUnsupportedStructure(Document document, Element graph) {
        if (elements(document, GRAPHML_NAMESPACE, "graph").size() != 1) {
            throw compatibilityFailure(Sentence.NESTED_GRAPHS,
                    detail("graphElementCount", elements(document, GRAPHML_NAMESPACE, "graph").size()));
        }
        for (Term unsupported : GraphMlRejection.unsupportedElements()) {
            List<Element> found = elements(document, GRAPHML_NAMESPACE, unsupported.text());
            if (!found.isEmpty()) {
                throw compatibilityFailure(Sentence.UNSUPPORTED_ELEMENT, unsupported,
                        detail("occurrences", found.size()));
            }
        }
        for (Element edge : directChildren(graph, GRAPHML_NAMESPACE, "edge")) {
            String directed = edge.getAttribute("directed");
            if ("false".equalsIgnoreCase(directed)) {
                // rejectUnsupportedStructure runs before resolveEdgeIdentity, so an id-less edge has
                // no id at all here rather than a synthesized one. Either way the id is a detail, so
                // the two orderings no longer produce different public messages (FIX-01).
                throw compatibilityFailure(Sentence.EDGE_OVERRIDES_DIRECTEDNESS,
                        detail("edgeId", rawId(edge)), detail("directed", directed));
            }
        }
    }

    private static Map<String, KeyDefinition> readKeys(Element root, Element graph) {
        var keys = new LinkedHashMap<String, KeyDefinition>();
        var effectiveNames = new HashMap<String, KeyDefinition>();
        boolean graphSeen = false;
        for (Element child : directChildren(root, null, null)) {
            if (child == graph) {
                graphSeen = true;
                continue;
            }
            if (!GRAPHML_NAMESPACE.equals(child.getNamespaceURI()) || !"key".equals(child.getLocalName())) {
                continue;
            }
            if (graphSeen) {
                throw compatibilityFailure(Sentence.KEYS_AFTER_GRAPH, detail("keyId", rawId(child)));
            }
            String id = child.getAttribute("id").trim();
            if (id.isEmpty()) {
                throw compatibilityFailure(Sentence.KEY_MISSING_ID);
            }
            String scope = child.getAttribute("for").trim();
            if (scope.isEmpty()) {
                scope = "all";
            }
            // GraphML/yFiles exporters routinely declare keys for structural scopes that are not
            // part of Ravenroot's executable property graph.  A declaration alone is inert: keep
            // it in the authoritative XML for lossless export and remove it only from the
            // TinkerPop import view below. Actual port/hyperedge/endpoint elements remain refused
            // by rejectUnsupportedStructure.
            if (!Set.of("all", "node", "edge", "graph", "graphml", "port", "hyperedge", "endpoint")
                    .contains(scope)) {
                throw compatibilityFailure(Sentence.KEY_UNSUPPORTED_SCOPE,
                        detail("keyId", id), detail("declaredScope", scope));
            }
            String name = child.getAttribute("attr.name").trim();
            if (name.isEmpty()) {
                name = child.getAttribute("attrname").trim();
            }
            if (name.isEmpty()) {
                name = id;
            }
            String type = child.getAttribute("attr.type").trim().toLowerCase(Locale.ROOT);
            if (type.isEmpty()) {
                type = "string";
            }
            if (!SCALAR_TYPES.contains(type)) {
                throw compatibilityFailure(Sentence.KEY_UNSUPPORTED_SCALAR_TYPE,
                        detail("keyId", id), detail("declaredType", type));
            }
            List<Element> defaults = directChildren(child, GRAPHML_NAMESPACE, "default");
            if (defaults.size() > 1) {
                throw compatibilityFailure(Sentence.KEY_MULTIPLE_DEFAULTS,
                        detail("keyId", id), detail("defaultCount", defaults.size()));
            }
            Element defaultElement = defaults.stream().findFirst().orElse(null);
            var definition = new KeyDefinition(id, name, type, scope, defaultElement);
            if (keys.putIfAbsent(id, definition) != null) {
                throw compatibilityFailure(Sentence.DUPLICATE_KEY_ID, detail("keyId", id));
            }
            for (String targetScope : targetScopes(scope)) {
                String collisionKey = targetScope + '\u0000' + name.toLowerCase(Locale.ROOT);
                KeyDefinition previous = effectiveNames.putIfAbsent(collisionKey, definition);
                if (previous != null) {
                    throw compatibilityFailure(Sentence.AMBIGUOUS_PROPERTY_NAME,
                            detail("propertyName", name), detail("scope", targetScope),
                            detail("firstKeyId", previous.id()), detail("secondKeyId", id));
                }
            }
        }
        return keys;
    }

    /**
     * Gives every edge a usable identity, because GraphML does not require the author to supply one.
     *
     * <p>The specification makes {@code id} mandatory on {@code <node>} and optional on
     * {@code <edge>}. In {@code graphml-structure.xsd}, {@code node.type} declares
     * {@code <xs:attribute name="id" type="xs:NMTOKEN" use="required">} while {@code edge.type}
     * declares {@code <xs:attribute name="id" type="xs:NMTOKEN" >} with no {@code use}, i.e. the
     * XML Schema default of {@code optional}. The equivalent DTD says
     * {@code <!ATTLIST node id ID #REQUIRED>} against {@code <!ATTLIST edge id ID #IMPLIED ...>}.
     * Requiring the attribute here rejected conforming documents (FIX-01).
     *
     * <p>Everything downstream of this class still needs a handle per edge: the TinkerPop view
     * addresses edges by id, {@link #ownerDataKey} keys default-value resolution by owner id, and
     * {@link GraphManager} snapshots edge ids to detect a post-import mutation. So the missing ids
     * are filled in on the DOM that validation and the property-graph view are built from. The
     * authoritative bytes held in {@link #original} are untouched, so an export still reproduces the
     * document exactly as it was written and never leaks a synthesized id.
     *
     * <p>The assignment is a pure function of the document. Candidates are consumed in document
     * order, and any candidate an author already used as an edge id is skipped, so a synthesized id
     * can never collide with an explicit one and re-reading the same bytes always yields the same
     * ids. {@link #validateTopology} still runs afterwards over explicit and synthesized ids
     * together, so a collision could never merge two edges silently: it would be a loud rejection.
     *
     * @return how many edges received a synthesized handle, which {@link #report(long, long)}
     *         surfaces: an author who never wrote an id cannot look one up either, so an operator
     *         reading a validation report should be told the handles exist rather than meet one for
     *         the first time inside a diagnostic.
     */
    private static int resolveEdgeIdentity(Element graph) {
        List<Element> edges = directChildren(graph, GRAPHML_NAMESPACE, "edge");
        var declared = new HashSet<String>();
        for (Element edge : edges) {
            String id = edge.getAttribute("id");
            if (edge.hasAttribute("id") && !id.isBlank()) {
                declared.add(id);
            }
        }
        int candidate = 0;
        int synthesizedCount = 0;
        for (Element edge : edges) {
            if (edge.hasAttribute("id")) {
                continue;
            }
            String synthesized;
            do {
                synthesized = SYNTHESIZED_EDGE_ID_PREFIX + candidate++;
            } while (declared.contains(synthesized));
            edge.setAttribute("id", synthesized);
            synthesizedCount++;
        }
        return synthesizedCount;
    }

    private static void validateTopology(Element graph) {
        var nodeIds = new HashSet<String>();
        for (Element node : directChildren(graph, GRAPHML_NAMESPACE, "node")) {
            String id = requiredId(node, Term.NODE);
            if (!nodeIds.add(id)) {
                throw compatibilityFailure(Sentence.DUPLICATE_NODE_ID, detail("nodeId", id));
            }
        }
        var edgeIds = new HashSet<String>();
        for (Element edge : directChildren(graph, GRAPHML_NAMESPACE, "edge")) {
            // resolveEdgeIdentity ran first, so an absent author id is no longer a rejection here;
            // requiredId now only asserts that invariant. Duplicate explicit ids are still refused.
            String id = requiredId(edge, Term.EDGE);
            try {
                StableEdgeId.requireValid(edge.getAttribute("id"));
            } catch (IllegalArgumentException invalid) {
                throw compatibilityFailure(Sentence.EDGE_ID_LIMIT_EXCEEDED,
                        detail("edgeId", rawId(edge)),
                        detail("maximumUtf8Bytes", StableEdgeId.MAX_UTF8_BYTES));
            }
            if (!edgeIds.add(id)) {
                throw compatibilityFailure(Sentence.DUPLICATE_EDGE_ID, detail("edgeId", id));
            }
            String source = edge.getAttribute("source");
            String target = edge.getAttribute("target");
            if (!nodeIds.contains(source) || !nodeIds.contains(target)) {
                throw compatibilityFailure(Sentence.EDGE_UNDECLARED_ENDPOINT,
                        detail("edgeId", id), detail("source", source), detail("target", target));
            }
        }
    }

    private static void validateData(Document document, Map<String, KeyDefinition> keys) {
        for (Element data : elements(document, GRAPHML_NAMESPACE, "data")) {
            String keyId = data.getAttribute("key").trim();
            KeyDefinition key = keys.get(keyId);
            if (key == null) {
                throw compatibilityFailure(Sentence.DATA_UNDECLARED_KEY,
                        detail("keyId", keyId), detail("owner", describeOwner(data)));
            }
            Element owner = ownerElement(data);
            boolean graphmlData = owner == document.getDocumentElement()
                    && ("graphml".equals(key.scope()) || "all".equals(key.scope()));
            if (!graphmlData && (owner == null || !GRAPHML_NAMESPACE.equals(owner.getNamespaceURI())
                    || !Set.of("graph", "node", "edge").contains(owner.getLocalName()))) {
                throw compatibilityFailure(Sentence.DATA_WRONG_OWNER,
                        detail("keyId", key.id()), detail("owner", describeOwner(data)));
            }
            String ownerScope = owner == null ? "" : owner.getLocalName();
            if (!scopeApplies(key.scope(), ownerScope)) {
                throw compatibilityFailure(Sentence.DATA_OUT_OF_KEY_SCOPE,
                        detail("keyId", key.id()), detail("keyScope", key.scope()),
                        detail("owner", describeOwner(data)));
            }
            if (hasElementChildren(data)) {
                if (RESERVED_PROPERTIES.contains(key.name().toLowerCase(Locale.ROOT))
                        && Set.of("node", "edge").contains(ownerScope)) {
                    throw compatibilityFailure(Sentence.COMPLEX_DATA_COLLIDES,
                            detail("keyId", key.id()), detail("propertyName", key.name()),
                            detail("owner", describeOwner(data)));
                }
                continue;
            }
            validateScalar(key, data.getTextContent(), describeOwner(data));
        }
        for (KeyDefinition key : keys.values()) {
            if (key.defaultElement() == null || hasElementChildren(key.defaultElement())) {
                if (key.defaultElement() != null
                        && RESERVED_PROPERTIES.contains(key.name().toLowerCase(Locale.ROOT))
                        && !"graph".equals(key.scope())) {
                    throw compatibilityFailure(Sentence.COMPLEX_DEFAULT_AMBIGUOUS,
                            detail("keyId", key.id()), detail("propertyName", key.name()));
                }
                continue;
            }
            validateScalar(key, key.defaultElement().getTextContent(), "default");
        }

        for (String ownerName : List.of("graph", "node", "edge")) {
            for (Element owner : elements(document, GRAPHML_NAMESPACE, ownerName)) {
                var seen = new HashSet<String>();
                for (Element data : directChildren(owner, GRAPHML_NAMESPACE, "data")) {
                    String key = data.getAttribute("key");
                    if (!seen.add(key)) {
                        throw compatibilityFailure(Sentence.DUPLICATE_DATA_KEY,
                                detail("keyId", key), detail("ownerKind", ownerName),
                                detail("ownerId", rawId(owner)));
                    }
                }
            }
        }
    }

    private static void validateScalar(KeyDefinition key, String lexicalValue, String owner) {
        String value = lexicalValue.trim();
        if (!SCALAR_TYPES.contains(key.type())) {
            throw compatibilityFailure(Sentence.KEY_UNSUPPORTED_SCALAR_TYPE,
                    detail("keyId", key.id()), detail("declaredType", key.type()),
                    detail("owner", owner));
        }
        try {
            switch (key.type()) {
                case "boolean" -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException();
                    }
                }
                case "int" -> Integer.parseInt(value);
                case "long" -> Long.parseLong(value);
                case "float" -> {
                    if (!Float.isFinite(Float.parseFloat(value))) {
                        throw new IllegalArgumentException();
                    }
                }
                case "double" -> {
                    if (!Double.isFinite(Double.parseDouble(value))) {
                        throw new IllegalArgumentException();
                    }
                }
                default -> {
                    // Strings have no lexical restriction.
                }
            }
        } catch (IllegalArgumentException exception) {
            throw compatibilityFailure(Sentence.SCALAR_VALUE_MISMATCH,
                    detail("keyId", key.id()), detail("declaredType", key.type()),
                    detail("owner", owner), detail("value", value));
        }
    }

    private static void preparePropertyGraphView(Document document, Map<String, KeyDefinition> keys) {
        Element root = document.getDocumentElement();
        var explicitData = new HashSet<String>();
        for (Element key : new ArrayList<>(directChildren(root, GRAPHML_NAMESPACE, "key"))) {
            KeyDefinition definition = keys.get(key.getAttribute("id"));
            if (definition != null && !PROPERTY_GRAPH_SCOPES.contains(definition.scope())) {
                root.removeChild(key);
                continue;
            }
            String id = key.getAttribute("id");
            if (key.getAttribute("attr.name").isBlank() && key.getAttribute("attrname").isBlank()) {
                key.setAttribute("attr.name", id);
            }
            if (key.getAttribute("attr.type").isBlank()) {
                key.setAttribute("attr.type", "string");
            }
            for (Element defaultElement : directChildren(key, GRAPHML_NAMESPACE, "default")) {
                if (hasElementChildren(defaultElement)) {
                    key.removeChild(defaultElement);
                }
            }
        }

        for (Element data : new ArrayList<>(elements(document, GRAPHML_NAMESPACE, "data"))) {
            Element owner = ownerElement(data);
            KeyDefinition key = keys.get(data.getAttribute("key"));
            if (owner == root || (key != null && !PROPERTY_GRAPH_SCOPES.contains(key.scope()))) {
                data.getParentNode().removeChild(data);
                continue;
            }
            if (owner != null) {
                explicitData.add(ownerDataKey(owner, data.getAttribute("key")));
            }
            if (hasElementChildren(data)) {
                data.getParentNode().removeChild(data);
            } else {
                if (key != null && requiresPropertyGraphNormalization(key)) {
                    data.setTextContent(data.getTextContent().trim());
                }
            }
        }
        var allElements = document.getElementsByTagName("*");
        var foreignElements = new ArrayList<Element>();
        for (int index = 0; index < allElements.getLength(); index++) {
            Element element = (Element) allElements.item(index);
            if (!GRAPHML_NAMESPACE.equals(element.getNamespaceURI())) {
                foreignElements.add(element);
            }
        }
        for (Element element : foreignElements) {
            Node parent = element.getParentNode();
            if (parent != null) {
                parent.removeChild(element);
            }
        }

        Element graph = directChildren(root, GRAPHML_NAMESPACE, "graph").getFirst();
        for (KeyDefinition key : keys.values()) {
            if (key.defaultElement() == null || hasElementChildren(key.defaultElement())) {
                continue;
            }
            for (String targetScope : targetScopes(key.scope())) {
                String localName = targetScope;
                for (Element owner : directChildren(graph, GRAPHML_NAMESPACE, localName)) {
                    boolean present = explicitData.contains(ownerDataKey(owner, key.id()));
                    if (!present) {
                        Element data = document.createElementNS(GRAPHML_NAMESPACE, "data");
                        data.setAttribute("key", key.id());
                        String defaultValue = key.defaultElement().getTextContent();
                        data.setTextContent(requiresPropertyGraphNormalization(key)
                                ? defaultValue.trim() : defaultValue);
                        owner.appendChild(data);
                    }
                }
            }
        }
    }

    private static byte[] serialize(Document document) {
        try {
            var factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            var output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (TransformerException exception) {
            throw new IllegalStateException("Cannot prepare GraphML property-graph view", exception);
        }
    }

    private static String requiredId(Element element, Term kind) {
        String id = element.getAttribute("id");
        if (id.isBlank()) {
            throw compatibilityFailure(Sentence.ELEMENT_MISSING_ID, kind);
        }
        return id;
    }

    /**
     * The element's id exactly as the document carries it, for the diagnostic channel only.
     *
     * <p>Replaces the former {@code elementId}, which substituted {@code <unnamed>} so that an
     * absent id still read acceptably inside a public message. Nothing is rendered into a public
     * message any more, so the substitution had no remaining purpose and an absent id is now
     * reported as absent rather than as a placeholder.</p>
     */
    private static String rawId(Element element) {
        String id = element.getAttribute("id").trim();
        return id.isEmpty() ? "<absent>" : id;
    }

    /** Diagnostic-channel description of the element a {@code <data>} hangs off. Never public. */
    private static String describeOwner(Element data) {
        Element owner = ownerElement(data);
        return owner == null
                ? "unknown element"
                : owner.getLocalName() + " '" + rawId(owner) + "'";
    }

    private static Element ownerElement(Element data) {
        Node parent = data.getParentNode();
        return parent instanceof Element element ? element : null;
    }

    private static String ownerDataKey(Element owner, String key) {
        return owner.getLocalName() + '\u0000' + rawId(owner) + '\u0000' + key;
    }

    private static boolean scopeApplies(String keyScope, String ownerScope) {
        return keyScope.equals("all") || keyScope.equals(ownerScope);
    }

    private static Set<String> targetScopes(String scope) {
        return switch (scope) {
            case "all" -> Set.of("node", "edge");
            case "node", "edge" -> Set.of(scope);
            default -> Set.of();
        };
    }

    private static boolean hasElementChildren(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                return true;
            }
        }
        return false;
    }

    /**
     * The imported TinkerPop view normalizes executable GraphML fields and non-string scalars.
     * The original XML stays untouched for lossless export; programmatic GraphDefinitions never
     * pass through this path and therefore retain their exact behavior and outcome strings.
     */
    private static boolean requiresPropertyGraphNormalization(KeyDefinition key) {
        return !"string".equals(key.type())
                || RESERVED_PROPERTIES.contains(key.name().toLowerCase(Locale.ROOT));
    }

    private static List<Element> elements(Document document, String namespace, String localName) {
        var result = new ArrayList<Element>();
        var nodes = document.getElementsByTagNameNS(namespace, localName);
        // getElementsByTagNameNS returns a LIVE NodeList. Re-reading getLength() in the loop
        // condition makes Xerces rescan the whole subtree on every iteration, which is quadratic
        // in element count. Snapshot the length once; the copy below is taken before any caller
        // mutates the document, so the resulting list is identical.
        int length = nodes.getLength();
        for (int index = 0; index < length; index++) {
            result.add((Element) nodes.item(index));
        }
        return result;
    }

    private static List<Element> directChildren(Element parent, String namespace, String localName) {
        var result = new ArrayList<Element>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element)) {
                continue;
            }
            if ((namespace == null || namespace.equals(element.getNamespaceURI()))
                    && (localName == null || localName.equals(element.getLocalName()))) {
                result.add(element);
            }
        }
        return result;
    }

    private record KeyDefinition(
            String id, String name, String type, String scope, Element defaultElement) {
    }
}
