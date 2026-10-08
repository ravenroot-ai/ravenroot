package ai.ravenroot.server.authoring;

import ai.ravenroot.api.authoring.GraphAuthoringException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/** Reads and writes the two authored release fields without interpreting runtime graph versions. */
public final class GraphReleaseMetadata {
    public static final String GRAPH_ID = "ravenroot.authoring.graphId";
    public static final String RELEASE_VERSION = "ravenroot.authoring.releaseVersion";
    private static final String NS = "http://graphml.graphdrawing.org/xmlns";
    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private GraphReleaseMetadata() { }

    public record Metadata(String graphId, long releaseVersion) {
        public Metadata {
            if (graphId == null || !STABLE_ID.matcher(graphId).matches()) {
                throw invalid();
            }
            if (releaseVersion < 1) throw invalid();
        }
    }

    public record Rewritten(Metadata metadata, byte[] graphMl) {
        public Rewritten {
            graphMl = graphMl.clone();
        }
        @Override public byte[] graphMl() { return graphMl.clone(); }
    }

    public static Metadata read(byte[] graphMl) {
        var document = parse(graphMl);
        var graph = oneGraph(document);
        String graphId = value(document, graph, GRAPH_ID);
        String version = value(document, graph, RELEASE_VERSION);
        if (graphId == null && version == null) return null;
        if (graphId == null || version == null) throw invalid();
        try { return new Metadata(graphId.strip(), Long.parseLong(version.strip())); }
        catch (NumberFormatException malformed) { throw invalid(); }
    }

    public static Rewritten assign(byte[] graphMl, String graphId, long version) {
        Metadata target = new Metadata(graphId, version);
        var document = parse(graphMl);
        var graph = oneGraph(document);
        set(document, graph, GRAPH_ID, "string", target.graphId());
        set(document, graph, RELEASE_VERSION, "long", Long.toString(target.releaseVersion()));
        try {
            var factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            var output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return new Rewritten(target, output.toByteArray());
        } catch (Exception failure) {
            throw new GraphAuthoringException(GraphAuthoringException.Failure.INVALID_DOCUMENT, failure);
        }
    }

    public static Rewritten assignLegacy(byte[] graphMl) {
        Metadata existing = read(graphMl);
        return existing == null
                ? assign(graphMl, "g" + UUID.randomUUID().toString().replace("-", ""), 1)
                : new Rewritten(existing, graphMl);
    }

    private static org.w3c.dom.Document parse(byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0) throw invalid();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (GraphAuthoringException classified) {
            throw classified;
        } catch (Exception failure) {
            throw new GraphAuthoringException(GraphAuthoringException.Failure.INVALID_DOCUMENT, failure);
        }
    }

    private static org.w3c.dom.Element oneGraph(org.w3c.dom.Document document) {
        var root = document.getDocumentElement();
        if (root == null || !NS.equals(root.getNamespaceURI()) || !"graphml".equals(root.getLocalName())) throw invalid();
        var graphs = document.getElementsByTagNameNS(NS, "graph");
        if (graphs.getLength() != 1) throw invalid();
        return (org.w3c.dom.Element) graphs.item(0);
    }

    private static String value(org.w3c.dom.Document document, org.w3c.dom.Element graph, String name) {
        String keyId = null;
        var keys = document.getElementsByTagNameNS(NS, "key");
        for (int index = 0; index < keys.getLength(); index++) {
            var key = (org.w3c.dom.Element) keys.item(index);
            if (name.equals(key.getAttribute("attr.name")) && ("graph".equals(key.getAttribute("for"))
                    || "all".equals(key.getAttribute("for")))) {
                if (keyId != null) throw invalid();
                keyId = key.getAttribute("id");
            }
        }
        if (keyId == null || keyId.isBlank()) return null;
        String found = null;
        for (var child = graph.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof org.w3c.dom.Element element && NS.equals(element.getNamespaceURI())
                    && "data".equals(element.getLocalName()) && keyId.equals(element.getAttribute("key"))) {
                if (found != null || element.getChildNodes().getLength() != 1
                        || element.getFirstChild().getNodeType() != org.w3c.dom.Node.TEXT_NODE) throw invalid();
                found = element.getTextContent();
            }
        }
        return found;
    }

    private static void set(org.w3c.dom.Document document, org.w3c.dom.Element graph,
                            String name, String type, String value) {
        var root = document.getDocumentElement();
        String keyId = null;
        var keys = document.getElementsByTagNameNS(NS, "key");
        for (int index = 0; index < keys.getLength(); index++) {
            var key = (org.w3c.dom.Element) keys.item(index);
            if (name.equals(key.getAttribute("attr.name"))) {
                if (keyId != null || !("graph".equals(key.getAttribute("for")) || "all".equals(key.getAttribute("for")))) throw invalid();
                keyId = key.getAttribute("id");
            }
        }
        if (keyId == null) {
            keyId = name.equals(GRAPH_ID) ? "ravenroot-authoring-graph-id" : "ravenroot-authoring-release-version";
            for (int index = 0; index < keys.getLength(); index++) {
                if (keyId.equals(((org.w3c.dom.Element) keys.item(index)).getAttribute("id"))) throw invalid();
            }
            var key = document.createElementNS(NS, "key");
            key.setAttribute("id", keyId);
            key.setAttribute("for", "graph");
            key.setAttribute("attr.name", name);
            key.setAttribute("attr.type", type);
            root.insertBefore(key, graph);
        }
        org.w3c.dom.Element data = null;
        for (var child = graph.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof org.w3c.dom.Element element && NS.equals(element.getNamespaceURI())
                    && "data".equals(element.getLocalName()) && keyId.equals(element.getAttribute("key"))) {
                if (data != null) throw invalid();
                data = element;
            }
        }
        if (data == null) {
            data = document.createElementNS(NS, "data");
            data.setAttribute("key", keyId);
            graph.insertBefore(data, graph.getFirstChild());
        }
        while (data.hasChildNodes()) data.removeChild(data.getFirstChild());
        data.appendChild(document.createTextNode(value));
    }

    private static GraphAuthoringException invalid() {
        return new GraphAuthoringException(GraphAuthoringException.Failure.INVALID_DOCUMENT);
    }
}
