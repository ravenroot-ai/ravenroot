package ai.ravenroot.api.ingress;

import java.util.Map;

/**
 * Managed bounded response. No streaming or connection ownership enters the package contract.
 * @param status HTTP status selected by the handler.
 * @param headers immutable response headers permitted by the adapter.
 * @param body bounded response bytes.
 */
public record IngressResponse(int status, Map<String, String> headers, byte[] body) {
/**
 * Snapshots headers and response bytes so package code cannot mutate an admitted response.
 */
    public IngressResponse { headers = Map.copyOf(headers); body = body.clone(); }
/**
 * Returns a defensive copy of the bounded response body.
 * @return response bytes isolated from this record's stored body.
 */
    @Override public byte[] body() { return body.clone(); }
}
