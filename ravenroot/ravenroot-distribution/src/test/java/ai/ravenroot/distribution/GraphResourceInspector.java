package ai.ravenroot.distribution;

import ai.ravenroot.core.graph.GraphManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads whether a shipped graph resource declares an AI-invoking node, from an {@link InputStream}
 * so both {@link DefaultDistributionModelAdapterBoundaryTest} (files on disk) and
 * {@link ReleaseArtifactModelAdapterBoundaryIT} (entries inside a built jar/zip, never extracted to
 * disk) can share one reading, instead of two independent implementations that could quietly drift
 * apart.
 */
final class GraphResourceInspector {

    private static final Pattern JSON_KEY_PATTERN = Pattern.compile("\"(kind|behavior)\"\\s*:");

    private GraphResourceInspector() {
    }

    /**
     * Reads which node "behavior" values (the key/data indirection {@code ai.ravenroot.core.graph.
     * GraphManager} uses at import - {@code GraphManager.BEHAVIOR}, referenced rather than hardcoded,
     * so a rename breaks this test's compile instead of silently going blind) a GraphML document
     * declares, and reports the ones in {@code watchedBehaviors}.
     *
     * <p>Deliberately does not go through {@code GraphManager.readGraphMl}, but <strong>not</strong>
     * for the reason an earlier version of this comment gave. It claimed that {@code readGraphMl}
     * "applies the full production topology validation (exactly one start node, exactly one end
     * node, ...)" and that {@code ravenroot-minimal.graphml} would be rejected by it. Both halves
     * were false, and the second stopped being true when that fixture was rewritten: measured,
     * {@code readGraphMl} accepts a document with zero END nodes without complaint, and the
     * cardinality rules live in {@code GraphDefinition}'s constructor, which {@code readGraphMl}
     * never reaches. The sentence mattered beyond this file -- a reader who trusts it concludes the
     * import path is fail-closed on topology, which is exactly the premise the "is {@code Error}
     * mandatory" decision turns on.
     *
     * <p>The real reason is narrower and still holds: {@code readGraphMl} applies security limits,
     * the compatibility layer and property-graph mapping, all of which are orthogonal to whether a
     * node declares an AI-invoking behavior. This reader only needs the key-id indirection GraphML
     * itself defines, so it works on any well-formed GraphML document regardless of whether it is
     * also a complete, runnable Ravenroot graph.
     *
     * <p>Matches on the declared <em>value</em> of the key whose {@code attr.name} is exactly
     * {@code "behavior"} - never a raw substring search over the file. A substring search for
     * {@code "agent"} would false-positive on {@code AgentNodeBehaviorFactory} appearing in an
     * unrelated {@code <data key="name">}, and would teach whoever hits it to stop trusting - and
     * eventually silence - the check.
     */
    static List<String> aiInvokingNodeBehaviors(InputStream graphmlInput, String label,
                                                 Set<String> watchedBehaviors) throws IOException {
        Document document = parseXml(graphmlInput, label);

        Set<String> behaviorKeyIds = new HashSet<>();
        NodeList keys = document.getElementsByTagName("key");
        for (int i = 0; i < keys.getLength(); i++) {
            Element key = (Element) keys.item(i);
            String scope = key.getAttribute("for").trim();
            if (!scope.isEmpty() && !scope.equals("all") && !scope.equals("node")) {
                continue;
            }
            String attrName = key.getAttribute("attr.name").trim();
            if (attrName.isEmpty()) {
                attrName = key.getAttribute("attrname").trim();
            }
            if (GraphManager.BEHAVIOR.equals(attrName)) {
                behaviorKeyIds.add(key.getAttribute("id").trim());
            }
        }
        if (behaviorKeyIds.isEmpty()) {
            return List.of();
        }

        List<String> found = new ArrayList<>();
        NodeList nodeElements = document.getElementsByTagName("node");
        for (int i = 0; i < nodeElements.getLength(); i++) {
            Element nodeElement = (Element) nodeElements.item(i);
            String nodeId = nodeElement.getAttribute("id");
            NodeList dataElements = nodeElement.getElementsByTagName("data");
            for (int j = 0; j < dataElements.getLength(); j++) {
                Element data = (Element) dataElements.item(j);
                if (!behaviorKeyIds.contains(data.getAttribute("key").trim())) {
                    continue;
                }
                String declaredBehavior = data.getTextContent() == null ? "" : data.getTextContent().trim();
                if (watchedBehaviors.contains(declaredBehavior)) {
                    found.add(label + " node '" + nodeId + "' declares behavior '" + declaredBehavior + "'");
                }
            }
        }
        return found;
    }

    /**
     * {@code graphify-minimal.json} ships alongside the GraphML examples but is a Graphify
     * code-knowledge-graph example (schema: {@code nodes[].{id,label,source_file}}, {@code
     * edges[].{source,target,relation,weight}}), an entirely different tool's format with no node-
     * behavior concept at all - verified by direct reading, not assumed. Rather than pull in a JSON
     * library for a one-file, structurally-incompatible format, this asserts the narrower, still
     * meaningful fact: the raw text contains no {@code "kind"} or {@code "behavior"} JSON key at all.
     * Unlike a free-text search for a node-behavior value (see {@link #aiInvokingNodeBehaviors}),
     * matching a quoted {@code "key":} pattern is precise enough not to need a real parser - it is
     * exactly the syntax a JSON object key must use, not a word that could appear incidentally in
     * prose or an unrelated field value.
     */
    static List<String> suspiciousJsonKeys(InputStream jsonInput, String label) throws IOException {
        String text = new String(jsonInput.readAllBytes(), StandardCharsets.UTF_8);
        List<String> found = new ArrayList<>();
        var matcher = JSON_KEY_PATTERN.matcher(text);
        while (matcher.find()) {
            found.add(label + " contains a JSON key " + matcher.group(0).trim()
                    + " - this file was assumed to be a Graphify example with no node-behavior concept; "
                    + "that assumption no longer holds and this check needs to be extended to read it.");
        }
        return found;
    }

    private static Document parseXml(InputStream input, String label) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Same defensive posture as ai.ravenroot.core.graph.SecureGraphMlParser: no DTDs, no
            // external entities. These files are repository/artifact-controlled, but a check that
            // reads arbitrary XML should not depend on that being true forever.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(input);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse " + label + " while scanning for AI-invoking node behaviors", e);
        }
    }
}
