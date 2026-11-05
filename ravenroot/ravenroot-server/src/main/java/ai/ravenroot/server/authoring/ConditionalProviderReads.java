package ai.ravenroot.server.authoring;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.LinkedHashMap;

/** Bounded HTTP validators. A 304 is paired with the exact representation used by that request. */
final class ConditionalProviderReads {
    record Representation(String etag, byte[] bytes) {
        Representation {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private final LinkedHashMap<URI, Representation> values = new LinkedHashMap<>(16, .75f, true);
    private long retained;

    synchronized Representation get(URI uri) {
        return values.get(uri);
    }

    synchronized void observe(URI uri, byte[] bytes, HttpHeaders headers) {
        var old = values.remove(uri);
        if (old != null) retained -= old.bytes.length;
        String etag = headers.firstValue("ETag").orElse(null);
        if (etag != null && bytes.length <= 16 * 1024 * 1024L) {
            values.put(uri, new Representation(etag, bytes));
            retained += bytes.length;
        }
        while (values.size() > 1024 || retained > 16 * 1024 * 1024L) {
            var iterator = values.entrySet().iterator();
            retained -= iterator.next().getValue().bytes.length;
            iterator.remove();
        }
    }
}
