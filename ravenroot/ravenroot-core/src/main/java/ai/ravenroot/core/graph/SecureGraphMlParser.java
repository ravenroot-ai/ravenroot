package ai.ravenroot.core.graph;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import static ai.ravenroot.core.graph.GraphMlParseException.Reason.COMPRESSED_ARCHIVE;
import static ai.ravenroot.core.graph.GraphMlParseException.Reason.DOCUMENT_TOO_LARGE;
import static ai.ravenroot.core.graph.GraphMlParseException.Reason.MALFORMED_XML;
import static ai.ravenroot.core.graph.GraphMlParseException.Reason.INVALID_GRAPH;
import static ai.ravenroot.core.graph.GraphMlParseException.Reason.RESOURCE_LIMIT;
import static ai.ravenroot.core.graph.GraphMlParseException.Reason.UNSAFE_XML;
import static ai.ravenroot.core.graph.GraphMlRejection.Sentence;
import static ai.ravenroot.core.graph.GraphMlRejection.Term;
import static ai.ravenroot.core.graph.GraphMlRejection.detail;
import static ai.ravenroot.core.graph.GraphMlRejection.parseFailure;

/** Security boundary shared by all server, CLI and embedded GraphML imports. */
final class SecureGraphMlParser {
    private static final String GRAPHML_NAMESPACE = "http://graphml.graphdrawing.org/xmlns";
    private static final Set<String> INTERPRETED_NAMES =
            Set.of("graphml", "graph", "key", "node", "edge", "data");

    private SecureGraphMlParser() {
    }

    static byte[] readAndValidate(InputStream input, GraphMlLimits limits) {
        if (input == null) {
            throw new IllegalArgumentException("GraphML input cannot be null");
        }
        byte[] bytes = readBounded(input, limits.maxBytes());
        validate(bytes, limits);
        return bytes;
    }

    static XMLInputFactory secureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setRequired(factory, XMLInputFactory.SUPPORT_DTD, false);
        setRequired(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setRequired(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        setRequired(factory, XMLInputFactory.IS_VALIDATING, false);
        setRequired(factory, XMLInputFactory.IS_NAMESPACE_AWARE, true);
        setRequired(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver(rejectingResolver());
        factory.setXMLReporter((message, errorType, relatedInformation, location) -> {
            // Parser diagnostics can contain attacker-controlled document fragments or URIs.
            // Callers receive the stable GraphMlParseException contract instead.
        });
        return factory;
    }

    private static byte[] readBounded(InputStream input, int maxBytes) {
        try {
            var output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int remaining = maxBytes - total;
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining + 1));
                if (read < 0) {
                    return output.toByteArray();
                }
                if (read == 0) {
                    int single = input.read();
                    if (single < 0) {
                        return output.toByteArray();
                    }
                    buffer[0] = (byte) single;
                    read = 1;
                }
                total += read;
                if (total > maxBytes) {
                    throw parseFailure(DOCUMENT_TOO_LARGE, Sentence.DOCUMENT_BYTE_LIMIT,
                            detail("bytesRead", total), detail("maxBytes", maxBytes));
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException error) {
            throw parseFailure(MALFORMED_XML, Sentence.DOCUMENT_UNREADABLE, null, error);
        }
    }

    private static void validate(byte[] bytes, GraphMlLimits limits) {
        if (looksLikeArchive(bytes)) {
            // A decompression-bomb defence: gzip/zip magic bytes are refused by container signature
            // alone, before any expansion is attempted. FIX-09 moved this from MALFORMED_XML, which
            // reported a hostile input as a corrupt file, to its own classified reason so a caller can
            // tell "this was refused as a compressed archive" from "this document is genuinely not
            // well-formed XML".
            throw parseFailure(COMPRESSED_ARCHIVE, Sentence.COMPRESSED_ARCHIVE,
                    detail("refusedAs", "archive container magic bytes"));
        }
        try {
            var reader = secureInputFactory().createXMLStreamReader(new ByteArrayInputStream(bytes));
            int depth = 0;
            int nodes = 0;
            int edges = 0;
            int properties = 0;
            int keys = 0;
            int elements = 0;
            int attributes = 0;
            int namespaceDeclarations = 0;
            int[] textLengths = new int[limits.maxDepth() + 1];
            String[] elementNames = new String[limits.maxDepth() + 1];
            boolean[] graphMlElements = new boolean[limits.maxDepth() + 1];
            var nodeIds = new HashSet<String>();
            var endpoints = new ArrayList<Endpoint>();
            boolean rootSeen = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    // Authoritative UNSAFE_XML signal. These are structural StAX events, not
                    // diagnostics, so the classification is identical on every locale and every JDK
                    // that honours the event constants. Every DTD - internal or external subset,
                    // PUBLIC or SYSTEM, parameter entities, notations, unparsed NDATA entities -
                    // arrives here as a DTD event because SUPPORT_DTD is disabled, which suppresses
                    // expansion without suppressing the event.
                    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_DECLARATION
                            || event == XMLStreamConstants.ENTITY_REFERENCE) {
                        throw parseFailure(UNSAFE_XML, Sentence.DTD_OR_ENTITY,
                                detail("streamEvent", event));
                    }
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth = increment(Term.XML_DEPTH, depth, limits.maxDepth());
                        elements = increment(Term.ELEMENT_COUNT, elements, limits.maxElements());
                        attributes = add(Term.ATTRIBUTE_COUNT, attributes, reader.getAttributeCount(),
                                limits.maxAttributes());
                        namespaceDeclarations = add(Term.NAMESPACE_DECLARATION_COUNT, namespaceDeclarations,
                                reader.getNamespaceCount(), limits.maxNamespaceDeclarations());
                        textLengths[depth] = 0;
                        String localName = reader.getLocalName();
                        boolean graphMlElement = GRAPHML_NAMESPACE.equals(reader.getNamespaceURI());
                        elementNames[depth] = localName;
                        graphMlElements[depth] = graphMlElement;
                        checkLength(Term.ELEMENT_NAME, localName, limits.maxStringLength());
                        checkLength(Term.ELEMENT_PREFIX, reader.getPrefix(), limits.maxStringLength());
                        checkLength(Term.ELEMENT_NAMESPACE, reader.getNamespaceURI(), limits.maxStringLength());
                        for (int index = 0; index < reader.getAttributeCount(); index++) {
                            checkLength(Term.ATTRIBUTE_NAME, reader.getAttributeLocalName(index),
                                    limits.maxStringLength());
                            checkLength(Term.ATTRIBUTE_PREFIX, reader.getAttributePrefix(index),
                                    limits.maxStringLength());
                            checkLength(Term.ATTRIBUTE_NAMESPACE, reader.getAttributeNamespace(index),
                                    limits.maxStringLength());
                            checkLength(Term.ATTRIBUTE_VALUE, reader.getAttributeValue(index),
                                    limits.maxStringLength());
                        }
                        for (int index = 0; index < reader.getNamespaceCount(); index++) {
                            checkLength(Term.NAMESPACE_PREFIX, reader.getNamespacePrefix(index),
                                    limits.maxStringLength());
                            checkLength(Term.NAMESPACE_URI, reader.getNamespaceURI(index),
                                    limits.maxStringLength());
                        }
                        if (!rootSeen) {
                            rootSeen = true;
                            if (!graphMlElement || !"graphml".equals(localName)) {
                                throw parseFailure(INVALID_GRAPH, Sentence.NON_CANONICAL_ROOT,
                                        detail("rootLocalName", localName),
                                        detail("rootNamespace", reader.getNamespaceURI()));
                            }
                        }
                        if (!graphMlElement && INTERPRETED_NAMES.contains(localName)) {
                            throw parseFailure(INVALID_GRAPH, Sentence.REDEFINED_INTERPRETED_NAME,
                                    detail("localName", localName),
                                    detail("namespace", reader.getNamespaceURI()));
                        }
                        validateContext(localName, graphMlElement, depth, elementNames, graphMlElements);
                        if (graphMlElement && "node".equals(localName)) {
                            nodes = increment(Term.NODE_COUNT, nodes, limits.maxNodes());
                            String id = reader.getAttributeValue(null, "id");
                            if (id == null || id.isEmpty() || !nodeIds.add(id)) {
                                throw parseFailure(INVALID_GRAPH, Sentence.NODE_IDENTIFIERS,
                                        detail("nodeId", id), detail("nodeOrdinal", nodes));
                            }
                        } else if (graphMlElement && "edge".equals(localName)) {
                            edges = increment(Term.EDGE_COUNT, edges, limits.maxEdges());
                            endpoints.add(new Endpoint(reader.getAttributeValue(null, "source"),
                                    reader.getAttributeValue(null, "target")));
                        } else if (graphMlElement && "data".equals(localName)) {
                            properties = increment(Term.PROPERTY_COUNT, properties, limits.maxProperties());
                        } else if (graphMlElement && "key".equals(localName)) {
                            keys = increment(Term.KEY_COUNT, keys, limits.maxKeys());
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        depth--;
                    } else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                        checkLength(Term.PROCESSING_INSTRUCTION_TARGET, reader.getPITarget(),
                                limits.maxStringLength());
                        checkLength(Term.PROCESSING_INSTRUCTION_DATA, reader.getPIData(),
                                limits.maxStringLength());
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA
                            || event == XMLStreamConstants.COMMENT) {
                        if (reader.getTextLength() > limits.maxStringLength() - textLengths[depth]) {
                            throw parseFailure(RESOURCE_LIMIT, Sentence.RESOURCE_LIMIT_EXCEEDED,
                                    Term.TEXT_VALUE, detail("maxStringLength", limits.maxStringLength()));
                        }
                        textLengths[depth] += reader.getTextLength();
                    }
                }
                for (Endpoint endpoint : endpoints) {
                    if (endpoint.source() == null || endpoint.target() == null
                            || !nodeIds.contains(endpoint.source()) || !nodeIds.contains(endpoint.target())) {
                        throw parseFailure(INVALID_GRAPH, Sentence.EDGE_ENDPOINTS_UNDECLARED,
                                detail("source", endpoint.source()), detail("target", endpoint.target()));
                    }
                }
                if (!rootSeen) {
                    throw parseFailure(INVALID_GRAPH, Sentence.NON_CANONICAL_ROOT,
                            detail("rootLocalName", "<no element seen>"));
                }
            } finally {
                reader.close();
            }
        } catch (GraphMlParseException rejection) {
            throw rejection;
        } catch (XMLStreamException error) {
            // A rejection is a security rejection only when this parser positively identified the
            // refused construct: the DTD, entity-declaration and entity-reference events above, or
            // the resolver refusal below. Never infer it from the JDK's diagnostic text - those
            // messages are localized, so the same document classified differently per machine, and
            // their wording is not part of any JDK compatibility contract. Anything the JDK refused
            // that we did not attribute to a refused construct is simply not well-formed XML.
            if (refusedUnsafeConstruct(error)) {
                // No cause. rejectionsNeverCarryParserDiagnosticsAsACause pins this: an
                // XMLStreamException's own message quotes the document ("Invalid encoding name
                // \"NO-SUCH-ENC\""), so attaching it would reintroduce through getCause() exactly the
                // disclosure the message no longer performs. The class name is recorded instead,
                // which is what a server-side reader actually needs and carries no content.
                throw parseFailure(UNSAFE_XML, Sentence.DTD_OR_ENTITY,
                        detail("refusedBy", "external resource resolver"),
                        detail("streamExceptionClass", error.getClass().getName()));
            }
            // No cause, for the reason given above.
            throw parseFailure(MALFORMED_XML, Sentence.DOCUMENT_NOT_WELL_FORMED,
                    detail("streamExceptionClass", error.getClass().getName()));
        }
    }

    private static boolean looksLikeArchive(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            return true;
        }
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4)
                || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8));
    }

    private static int increment(Term resource, int current, int maximum) {
        if (current >= maximum) {
            throw parseFailure(RESOURCE_LIMIT, Sentence.RESOURCE_LIMIT_EXCEEDED, resource,
                    detail("limit", maximum));
        }
        return current + 1;
    }

    private static int add(Term resource, int current, int added, int maximum) {
        if (added > maximum - current) {
            throw parseFailure(RESOURCE_LIMIT, Sentence.RESOURCE_LIMIT_EXCEEDED, resource,
                    detail("limit", maximum), detail("attemptedAddition", added));
        }
        return current + added;
    }

    private static void validateContext(String localName, boolean graphMlElement, int depth,
                                        String[] elementNames, boolean[] graphMlElements) {
        if (!graphMlElement || !INTERPRETED_NAMES.contains(localName)) {
            return;
        }
        if ("graphml".equals(localName)) {
            require(depth == 1, Term.GRAPHML_ROOT);
            return;
        }
        String parent = depth > 1 && graphMlElements[depth - 1] ? elementNames[depth - 1] : null;
        switch (localName) {
            case "graph" -> require("graphml".equals(parent) || "node".equals(parent), Term.GRAPH);
            case "key" -> require("graphml".equals(parent), Term.KEY);
            case "node", "edge" -> require("graph".equals(parent), Term.forElementName(localName));
            // GraphML permits document-level data directly under <graphml>; yFiles uses it for its
            // resources bundle. Scope/key validation belongs to GraphMlDocument, which can
            // distinguish a legitimate for="graphml" key from an orphan node/edge property.
            case "data" -> require("graphml".equals(parent) || "graph".equals(parent)
                            || "node".equals(parent) || "edge".equals(parent),
                    Term.DATA);
            default -> {
                // All interpreted names are handled above.
            }
        }
    }

    private static void require(boolean condition, Term element) {
        if (!condition) {
            throw parseFailure(INVALID_GRAPH, Sentence.INVALID_ELEMENT_CONTEXT, element);
        }
    }

    private static void checkLength(Term resource, String value, int maximum) {
        if (value != null) {
            checkLength(resource, value.length(), maximum);
        }
    }

    private static void checkLength(Term resource, int length, int maximum) {
        if (length > maximum) {
            throw parseFailure(RESOURCE_LIMIT, Sentence.RESOURCE_LIMIT_EXCEEDED, resource,
                    detail("length", length), detail("limit", maximum));
        }
    }

    private static void setRequired(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalStateException("XML parser does not support required security controls");
        }
    }

    private static XMLResolver rejectingResolver() {
        return (publicId, systemId, baseUri, namespace) -> {
            throw new ExternalResourceRefused();
        };
    }

    /**
     * Marks the one unsafe construct that this parser refuses through the resolver rather than
     * through a stream event, so classification can recognise it by type. Under the current JDK the
     * resolver is never consulted - DTDs surface as a {@link XMLStreamConstants#DTD} event and are
     * refused there - but the resolver remains a deliberate second line of defence, and a refusal it
     * does raise must stay classified as {@code UNSAFE_XML} without reading any message.
     */
    private static final class ExternalResourceRefused extends XMLStreamException {
        private static final long serialVersionUID = 1L;

        private ExternalResourceRefused() {
            super("External XML resources are disabled");
        }
    }

    private static boolean refusedUnsafeConstruct(Throwable error) {
        // XMLStreamException carries its wrapped throwable in a separate "nested" field that is not
        // always mirrored into getCause(), and the same factory is reused for the TinkerPop parse in
        // GraphManager, which re-wraps again. Follow both links or a refusal can go unrecognised.
        // The identity set keeps a self-referential or cyclic chain from looping forever.
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        enqueue(pending, error);
        while (!pending.isEmpty()) {
            Throwable current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof ExternalResourceRefused) {
                return true;
            }
            enqueue(pending, current.getCause());
            if (current instanceof XMLStreamException nesting) {
                enqueue(pending, nesting.getNestedException());
            }
        }
        return false;
    }

    private static void enqueue(ArrayDeque<Throwable> pending, Throwable candidate) {
        // ArrayDeque rejects null, and an absent cause or nested exception is the common case.
        if (candidate != null) {
            pending.add(candidate);
        }
    }

    private record Endpoint(String source, String target) {
    }
}
