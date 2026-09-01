package ai.ravenroot.api.ingress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded HTTP projection.
 *
 * <p>The relative path and ordered query values have already been decoded exactly once. Header
 * names are canonical lower-case and appear only when the composition root allowlisted them.
 * Principal identity is a separate server-derived value.</p>
 * @param principal authenticated principal derived by the adapter.
 * @param method normalized HTTP method.
 * @param relativePath decoded path below the admitted route.
 * @param query immutable decoded query values in request order.
 * @param headers allowlisted canonical lower-case headers.
 * @param body defensive snapshot of the bounded request body.
 */
public record IngressRequest(IngressPrincipal principal, String method, String relativePath,
                             Map<String, List<String>> query, Map<String, String> headers, byte[] body) {
/**
 * Requires all transport projections and makes maps, query values, and body bytes immutable to callers.
 */
    public IngressRequest {
        principal = Objects.requireNonNull(principal, "principal");
        method = Objects.requireNonNull(method, "method");
        relativePath = Objects.requireNonNull(relativePath, "relativePath");
        query = immutableQuery(query);
        var headerCopy = new LinkedHashMap<String, String>();
        Objects.requireNonNull(headers, "headers").forEach((key, value) ->
                headerCopy.put(Objects.requireNonNull(key, "header key"),
                        Objects.requireNonNull(value, "header value")));
        headers = Collections.unmodifiableMap(headerCopy);
        body = Objects.requireNonNull(body, "body").clone();
    }

/**
 * Retains the original constructor descriptor.
 * @param principal authenticated principal derived by the adapter.
 * @param method normalized HTTP method.
 * @param relativePath decoded path below the admitted route.
 * @param headers allowlisted canonical lower-case headers.
 * @param body defensive snapshot of the bounded request body.
 */
    public IngressRequest(IngressPrincipal principal, String method, String relativePath,
                          Map<String, String> headers, byte[] body) {
        this(principal, method, relativePath, Map.of(), headers, body);
    }

/**
 * Returns a defensive copy so a handler cannot alter the stored request bytes.
 * @return bounded request body copy.
 */
    @Override public byte[] body() { return body.clone(); }

    private static Map<String, List<String>> immutableQuery(Map<String, List<String>> source) {
        Objects.requireNonNull(source, "query");
        var copy = new LinkedHashMap<String, List<String>>();
        source.forEach((key, values) -> {
            var valueCopy = new ArrayList<String>();
            Objects.requireNonNull(values, "query values").forEach(value ->
                    valueCopy.add(Objects.requireNonNull(value, "query value")));
            copy.put(Objects.requireNonNull(key, "query key"),
                    Collections.unmodifiableList(valueCopy));
        });
        return Collections.unmodifiableMap(copy);
    }
}
