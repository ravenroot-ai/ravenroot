package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.NodeOutcomeDescriptor;
import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.ToolPolicy;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.NodeBehaviorFactory;
import ai.ravenroot.core.runtime.NodeHandler;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.core.security.egress.BoundedBodyHandlers;
import ai.ravenroot.core.security.egress.EgressHttpClients;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HttpRequestNodeBehaviorFactory implements NodeBehaviorFactory {
    private static final Set<String> GRAPH_FORBIDDEN_HEADERS = Set.of(
            "authorization", "cookie", "proxy-authorization");
    private final OutboundHttpPolicy outboundPolicy;
    private final CredentialResolver credentials;
    private final ToolPolicy toolPolicy;
    // Built through EgressHttpClients so the two controls that are easy to lose --
    // no redirect and no proxy -- are stated in one place and pinned by a test. See that class for
    // why the previous absence of a proxy was not a control.
    private final HttpClient client = EgressHttpClients.create();

    HttpRequestNodeBehaviorFactory(OutboundHttpPolicy outboundPolicy, CredentialResolver credentials,
                                   ToolPolicy toolPolicy) {
        this.outboundPolicy = outboundPolicy;
        this.credentials = credentials;
        this.toolPolicy = toolPolicy;
    }

    @Override
    public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("http-request", "HTTP request", "Connectors",
                "Calls an allowlisted HTTP endpoint. Secrets are resolved server-side by opaque reference.",
                "consumer", false, List.of(
                NodePropertyDescriptor.required("url", "URL", NodePropertyType.URI,
                        "HTTP(S) endpoint; the resolved host must be allowlisted by the server."),
                new NodePropertyDescriptor("method", "Method", NodePropertyType.STRING, false,
                        "HTTP method.", "GET", List.of("GET", "POST", "PUT", "PATCH", "DELETE")),
                NodePropertyDescriptor.optional("body", "Body", NodePropertyType.TEXT,
                        "Optional body template. Supports {{payload}}.", ""),
                NodePropertyDescriptor.optional("timeoutMs", "Timeout (ms)", NodePropertyType.INTEGER,
                        "Capped by the server maximum.", "10000"),
                NodePropertyDescriptor.optional("credentialRef", "Credential reference",
                        NodePropertyType.SECRET_REFERENCE, "Opaque server-side credential reference.", ""),
                NodePropertyDescriptor.optional("credentialHeader", "Credential header", NodePropertyType.STRING,
                        "Header receiving the resolved credential.", "Authorization"),
                NodePropertyDescriptor.optional("credentialScheme", "Credential scheme", NodePropertyType.STRING,
                        "For example Bearer. Empty writes only the secret value.", "Bearer"),
                NodePropertyDescriptor.optional("successOutcome", "Success outcome", NodePropertyType.STRING,
                        "Outcome for HTTP 2xx responses.", "continue"),
                NodePropertyDescriptor.optional("failureOutcome", "Failure outcome", NodePropertyType.STRING,
                        "Outcome for non-2xx responses.", "error"),
                // PERS-04 (ADR 0022). Required exactly where the answer has consequences: a GET
                // is idempotent and a POST is not, and the `side-effect` capability tag cannot express
                // the difference — which is why capability tags were rejected as a recovery contract.
                // The author is forced to decide for the methods that change something, and left
                // alone for the ones that do not. No default: an undeclared instance parks.
                ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty.declaration(
                        "Whether repeating this request after a crash of unknown outcome is safe. "
                                + "Required for methods that change state.",
                        ai.ravenroot.api.catalog.PropertyCondition.oneOf("method",
                                "POST", "PUT", "PATCH", "DELETE"))),
                Set.of("network", "credential-reference", "side-effect"))
                // Parameterized, not fixed: the author names these. Note that the failure outcome is a
                // non-2xx RESPONSE, which is a completed request — a transport error produces no
                // outcome at all and fails the node, which is a different route (see the failure
                // edges). Declaring the defaults "continue" and "error" as literals would be right
                // only for a node that left both properties alone.
                .withOutcomes(
                        NodeOutcomeDescriptor.fromProperty("successOutcome",
                                "The endpoint answered with an HTTP 2xx status."),
                        NodeOutcomeDescriptor.fromProperty("failureOutcome",
                                "The endpoint answered with a non-2xx status."));
    }

    @Override
    public NodeHandler create(GraphNode node) {
        String urlTemplate = NodeProperties.required(node, "url");
        String method = NodeProperties.string(node, "method", "GET").toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw new IllegalArgumentException("Unsupported HTTP method at node " + node.id() + ": " + method);
        }
        String bodyTemplate = NodeProperties.string(node, "body", "");
        long timeoutMs = NodeProperties.number(node, "timeoutMs", 10_000);
        // Verbatim, like every reader of this type. The rule and the reason live on
        // NodePropertyType.SECRET_REFERENCE, not here, because they belong to the type rather than to
        // this node -- which is how this line and the LLM node's came to disagree in the first place.
        // The two below keep their trim: they are STRING header plumbing, not references, and nothing
        // injective is derived from them.
        String credentialRef = NodeProperties.string(node, "credentialRef", "");
        String credentialHeader = NodeProperties.string(node, "credentialHeader", "Authorization").trim();
        String credentialScheme = NodeProperties.string(node, "credentialScheme", "Bearer").trim();
        String successOutcome = NodeProperties.string(node, "successOutcome", "continue");
        String failureOutcome = NodeProperties.string(node, "failureOutcome", "error");
        Map<String, String> headers = headers(node);

        return message -> {
            URI uri = URI.create(NodeProperties.render(urlTemplate, message, node));
            outboundPolicy.requireAllowed(uri);
            ToolAuthorization.requireAllowed(toolPolicy, message, "http.request",
                    Map.of("host", uri.getHost(), "method", method));
            var builder = HttpRequest.newBuilder(uri).timeout(outboundPolicy.timeout(timeoutMs));
            headers.forEach(builder::header);
            if (!credentialRef.isEmpty()) {
                // KNOWN GAP (SEC-07, deliberately out of scope and escalated separately).
                // `credentialRef` comes from GraphML node properties, and CredentialResolver.resolve
                // takes no tenant. A tenant-A graph can therefore name a credential reference that
                // resolves to another tenant's secret: tool authorization above is tenant-aware since
                // SEC-07, but credential resolution below is not. Closing this needs a per-tenant
                // reference naming contract, which is an documented decision rather than plumbing, so the
                // behaviour is unchanged here and the gap is recorded rather than silently inherited.
                // See CredentialResolverTenantScopeTest for the executable statement of this gap.
                var secret = credentials.resolve(credentialRef).orElseThrow(() ->
                        new SecurityException("Credential reference cannot be resolved: " + credentialRef));
                try (secret) {
                    char[] value = secret.copy();
                    try {
                        String headerValue = credentialScheme.isEmpty() ? new String(value)
                                : credentialScheme + " " + new String(value);
                        builder.header(credentialHeader, headerValue);
                    } finally {
                        java.util.Arrays.fill(value, '\0');
                    }
                }
            }
            String body = NodeProperties.render(bodyTemplate, message, node);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            // The request half of the volume gate. Checked on the rendered bytes,
            // because the template is small and the payload it interpolates need not be.
            outboundPolicy.requireRequestWithinLimit(bodyBytes);
            HttpRequest.BodyPublisher publisher = body.isEmpty() && Set.of("GET", "DELETE").contains(method)
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(bodyBytes);
            HttpRequest request = builder.method(method, publisher).build();
            // Bounded, not BodyHandlers.ofString, which reads without a ceiling.
            return client.sendAsync(request, BoundedBodyHandlers.ofString(
                            outboundPolicy.maximumResponseBytes(), StandardCharsets.UTF_8))
                    .thenApply(response -> new NodeResult(response.statusCode() >= 200 && response.statusCode() < 300
                            ? successOutcome : failureOutcome, response.body(),
                            NodeProperties.attributes(message, "http.status", response.statusCode(),
                                    "http.uri", uri.toString())));
        };
    }

    private static Map<String, String> headers(GraphNode node) {
        var headers = new LinkedHashMap<String, String>();
        node.properties().forEach((name, value) -> {
            if (!name.startsWith("header.") || value == null) return;
            String header = name.substring("header.".length()).trim();
            if (header.isEmpty() || GRAPH_FORBIDDEN_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Header must be non-sensitive graph metadata at node " + node.id());
            }
            headers.put(header, value.toString());
        });
        return Map.copyOf(headers);
    }
}
