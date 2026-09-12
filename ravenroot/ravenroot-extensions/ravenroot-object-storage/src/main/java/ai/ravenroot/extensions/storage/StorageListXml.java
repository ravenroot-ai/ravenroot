package ai.ravenroot.extensions.storage;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.service.OutboundHttpResponse;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict bounded ListObjectsV2 response projection. */
final class StorageListXml {
    private static final int MAX_XML_DEPTH = 8;
    private static final int MAX_XML_ELEMENTS = 16_384;
    private static final int MAX_FIELD_CHARS = 4096;

    static NodeResult project(StorageProfile profile, String tenantId, String prefix, int maximum,
                              Set<String> projection, OutboundHttpResponse response) {
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            throw StorageException.of(StorageException.Code.REDIRECT_REFUSED);
        }
        if (response.statusCode() != 200) throw StorageException.of(StorageException.Code.REMOTE_REJECTED);
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
                throw new XMLStreamException("External XML resolution is forbidden");
            });
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(response.body()));
            List<Map<String, Object>> objects = new ArrayList<>();
            Map<String, String> current = null;
            int depth = 0;
            int contentsDepth = -1;
            Boolean truncated = null;
            String nextToken = null;
            boolean rootSeen = false;
            String rootNamespace = null;
            int elements = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
                }
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++elements > MAX_XML_ELEMENTS) invalid();
                    depth++;
                    if (depth > MAX_XML_DEPTH) throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
                    String name = reader.getLocalName();
                    if (depth == 1) {
                        String namespace = reader.getNamespaceURI();
                        if (rootSeen || !name.equals("ListBucketResult")
                                || !(namespace == null || namespace.isEmpty()
                                || namespace.equals("http://s3.amazonaws.com/doc/2006-03-01/"))) invalid();
                        rootSeen = true;
                        rootNamespace = namespace == null ? "" : namespace;
                    } else if (!java.util.Objects.equals(rootNamespace,
                            reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI())) {
                        invalid();
                    } else if (depth == 2 && name.equals("Contents")) {
                        if (current != null || objects.size() >= maximum) invalid();
                        current = new LinkedHashMap<>();
                        contentsDepth = depth;
                    } else if (current != null && depth == contentsDepth + 1 && objectField(name)) {
                        String value = boundedText(reader);
                        if (current.putIfAbsent(name, value) != null) invalid();
                        depth--;
                    } else if (current == null && depth == 2 && name.equals("IsTruncated")) {
                        if (truncated != null) invalid();
                        String value = boundedText(reader);
                        if (!value.equals("true") && !value.equals("false")) invalid();
                        truncated = Boolean.valueOf(value);
                        depth--;
                    } else if (current == null && depth == 2 && name.equals("NextContinuationToken")) {
                        if (nextToken != null) invalid();
                        nextToken = boundedText(reader);
                        depth--;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (current != null && depth == contentsDepth
                            && reader.getLocalName().equals("Contents")) {
                        objects.add(projectObject(profile, prefix, projection, current));
                        current = null;
                        contentsDepth = -1;
                    }
                    depth--;
                    if (depth < 0) invalid();
                }
            }
            reader.close();
            if (!rootSeen || current != null || depth != 0 || truncated == null
                    || truncated && (nextToken == null || nextToken.isEmpty())
                    || !truncated && nextToken != null) invalid();
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("version", "object.list.result.v1");
            output.put("objects", List.copyOf(objects));
            output.put("truncated", truncated);
            if (nextToken != null) {
                output.put("cursor", StorageCursor.encode(profile, tenantId, prefix, maximum, projection, nextToken));
            }
            return NodeResult.continueWith(Map.copyOf(output));
        } catch (StorageException safe) {
            throw safe;
        } catch (Exception invalid) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
    }

    private static Map<String, Object> projectObject(StorageProfile profile, String prefix,
                                                     Set<String> projection, Map<String, String> fields) {
        String rawKey = fields.get("Key");
        if (rawKey == null) invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", StorageUri.relativeListedKey(profile, prefix, rawKey));
        for (String field : projection) {
            switch (field) {
                case "size" -> result.put("size", nonNegativeLong(fields.get("Size")));
                case "etag" -> result.put("etag", safe(fields.get("ETag"), 512));
                case "lastModified" -> result.put("lastModified",
                        Instant.parse(safe(fields.get("LastModified"), 128)).toString());
                case "storageClass" -> result.put("storageClass", safe(fields.get("StorageClass"), 64));
                default -> throw new AssertionError(field);
            }
        }
        return Map.copyOf(result);
    }

    private static String boundedText(XMLStreamReader reader) throws Exception {
        StringBuilder value = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) return value.toString();
            if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE
                    || event == XMLStreamConstants.START_ELEMENT) invalid();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE) {
                if (value.length() + reader.getTextLength() > MAX_FIELD_CHARS) invalid();
                String text = reader.getText();
                if (text.codePoints().anyMatch(code -> code < 0x20 && code != '\t' && code != '\n' && code != '\r')) {
                    invalid();
                }
                value.append(text);
            }
        }
        invalid();
        throw new AssertionError();
    }

    private static boolean objectField(String value) {
        return Set.of("Key", "Size", "ETag", "LastModified", "StorageClass").contains(value);
    }

    private static String safe(String value, int maximum) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || value.codePoints().anyMatch(code -> code < 0x20 || code == 0x7f)) invalid();
        return value;
    }

    private static long nonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(safe(value, 20));
            if (parsed < 0) invalid();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
    }

    private static void invalid() { throw StorageException.of(StorageException.Code.RESPONSE_INVALID); }

    private StorageListXml() { }
}
