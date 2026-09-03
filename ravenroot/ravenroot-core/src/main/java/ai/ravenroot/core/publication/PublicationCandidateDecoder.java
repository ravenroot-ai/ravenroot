package ai.ravenroot.core.publication;

import ai.ravenroot.api.publication.PublicationCandidate;
import ai.ravenroot.api.publication.PublicationContent;
import ai.ravenroot.api.publication.PublicationDestination;
import ai.ravenroot.api.publication.PublicationProvenance;
import ai.ravenroot.api.publication.PublicationResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Strict map-compatible decoder for the built-in boundary-guard node. */
public final class PublicationCandidateDecoder {
    private PublicationCandidateDecoder() { }

    /** Decodes a typed candidate or the documented map projection. */
    public static PublicationCandidate decode(Object value) {
        if (value instanceof PublicationCandidate candidate) return candidate;
        if (!(value instanceof Map<?, ?> map)) throw malformed();
        String contract = string(map, "contract", 64);
        Map<?, ?> destination = object(map, "destination");
        List<?> resources = list(map, "resources", 1_024);
        var decodedResources = new ArrayList<PublicationResource>(resources.size());
        int fragments = 0;
        for (Object raw : resources) {
            if (!(raw instanceof Map<?, ?> resource)) throw malformed();
            Map<?, ?> content = object(resource, "content");
            String encoding = string(content, "encoding", 16);
            List<String> values = strings(content, "fragments", 4_096 - fragments);
            fragments += values.size();
            PublicationContent publicationContent = switch (encoding) {
                case "utf-8" -> new PublicationContent.Text(values);
                case "base64" -> new PublicationContent.Base64Binary(values);
                default -> throw malformed();
            };
            decodedResources.add(new PublicationResource(
                    string(resource, "path", 2_048),
                    string(resource, "artifactType", 128),
                    string(resource, "mediaType", 255),
                    optionalString(resource, "language", 63), publicationContent));
        }
        PublicationProvenance provenance = null;
        Object rawProvenance = map.get("provenance");
        if (rawProvenance != null) {
            if (!(rawProvenance instanceof Map<?, ?> fields)) throw malformed();
            provenance = new PublicationProvenance(
                    optionalString(fields, "sourceType", 128), optionalString(fields, "sourceId", 256),
                    optionalString(fields, "sourceVersion", 128), optionalString(fields, "contentDigest", 71));
        }
        try {
            return new PublicationCandidate(contract,
                    new PublicationDestination(string(destination, "type", 128),
                            string(destination, "address", 2_048)), decodedResources, provenance);
        } catch (RuntimeException invalid) {
            throw malformed();
        }
    }

    private static Map<?, ?> object(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> nested)) throw malformed();
        return nested;
    }

    private static List<?> list(Map<?, ?> map, String key, int maximum) {
        Object value = map.get(key);
        if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > maximum) throw malformed();
        return values;
    }

    private static List<String> strings(Map<?, ?> map, String key, int maximum) {
        List<?> values = list(map, key, maximum);
        var result = new ArrayList<String>(values.size());
        for (Object value : values) {
            if (!(value instanceof String text)) throw malformed();
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String string(Map<?, ?> map, String key, int maximum) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) throw malformed();
        return text;
    }

    private static String optionalString(Map<?, ?> map, String key, int maximum) {
        Object value = map.get(key);
        if (value == null) return "";
        if (!(value instanceof String text) || text.length() > maximum) throw malformed();
        return text;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("publication candidate is malformed");
    }
}
