package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads one MCP server per environment variable, as strict canonical Base64 of a small JSON document.
 *
 * <p>Variable name: {@code RAVENROOT_MCP_SERVER_<hex(profileName)>}, derived through
 * {@link EnvironmentKeyCodec} — the one injective derivation every resolver in this repository uses,
 * so two distinct server names can never collide onto one variable.</p>
 *
 * <p>Document, with {@code endpoint} and {@code allowedTools} required:</p>
 * <pre>{@code
 * {
 *   "endpoint": "https://mcp.example.com/mcp",
 *   "credentialBindingId": "mcp",
 *   "credentialReference": "mcp-token",
 *   "timeoutMs": 20000,
 *   "maxResponseBytes": 1048576,
 *   "maxConcurrency": 4,
 *   "allowedTools": ["search", "fetch_document"]
 * }
 * }</pre>
 *
 * <p><b>{@code allowedTools} is required and may not be empty.</b> Every other member has a default,
 * and this one deliberately does not: a default would have to be either "no tools", which makes the
 * profile useless in a way an operator would work around by copying a list they did not think about,
 * or "whatever the server announces", which would return authority over the callable set to the
 * server. A required list is the only reading in which forgetting it is a refusal.</p>
 *
 * <p><b>Malformed reads as absent</b>, for the reason {@link EnvironmentLlmProfileResolver} records
 * at length: a profile is read per execution and its absence refuses exactly the node that names it,
 * with the name in front of the operator. That is right here and would be wrong for a service grant,
 * which is read once at startup and must stop the process instead.</p>
 */
public final class EnvironmentMcpProfileResolver implements McpProfileResolver {

    /** Prefix of the one variable family this class reads. The suffix is {@code hex(profileName)}. */
    public static final String VARIABLE_PREFIX = "RAVENROOT_MCP_SERVER_";

    private static final Set<String> FIELDS = Set.of("endpoint", "credentialBindingId",
            "credentialReference", "timeoutMs", "maxRequestBytes", "maxResponseBytes", "maxConcurrency",
            "maxDiscoveredTools", "allowedTools");
    /** Same ASCII mask every other profile resolver applies before deriving a variable name. */
    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private final Map<String, String> environment;
    private final AgentOperationalConfiguration policy;

    public EnvironmentMcpProfileResolver() {
        this(System.getenv(), AgentOperationalConfiguration.fromEnvironment(System.getenv()));
    }

    EnvironmentMcpProfileResolver(Map<String, String> environment) {
        this(environment, AgentOperationalConfiguration.defaults());
    }

    EnvironmentMcpProfileResolver(Map<String, String> environment,
                                  AgentOperationalConfiguration policy) {
        this.environment = Map.copyOf(environment);
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
    }

    /** The exact variable an operator must set to declare {@code profileName}. */
    public static String environmentVariableName(String profileName) {
        return VARIABLE_PREFIX + EnvironmentKeyCodec.hex(profileName);
    }

    @Override
    public Optional<McpProfile> resolve(String profileName) {
        if (profileName == null || !profileName.matches(NAME_PATTERN)) {
            return Optional.empty();
        }
        try {
            String encoded = environment.get(environmentVariableName(profileName));
            if (encoded == null || encoded.isBlank()
                    || encoded.length() > (long) policy.maxMcpProfileBytes() * 2) {
                return Optional.empty();
            }
            byte[] json = Base64.getDecoder().decode(encoded);
            // Canonical, not merely decodable: a value that decodes but does not re-encode to itself
            // is a different string that happens to share a decoding, and accepting it would make two
            // spellings of one variable mean the same server.
            if (!Base64.getEncoder().encodeToString(json).equals(encoded)) {
                return Optional.empty();
            }
            Object read = PayloadJson.read(json, new PayloadLimits(policy.maxMcpProfileBytes(), 8,
                    Math.max(128, policy.maxDiscoveredMcpToolsPerServer()), 512,
                    policy.maxMcpProfileBytes(), 64)).toJava();
            if (!(read instanceof Map<?, ?> raw)) {
                return Optional.empty();
            }
            Map<String, Object> root = cast(raw);
            // Unknown members are refused rather than ignored. That matters more here than on a model
            // profile: "allowedTool" for "allowedTools" would otherwise read as a profile with no
            // list at all, and the refusal an operator needs is the one that names the typo.
            if (!FIELDS.containsAll(root.keySet())) {
                return Optional.empty();
            }
            String bindingId = text(root.get("credentialBindingId"), "");
            String reference = text(root.get("credentialReference"), "");
            Optional<OutboundCredentialBinding> credential = bindingId.isEmpty() && reference.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new OutboundCredentialBinding(bindingId, reference));
            int timeoutMs = integer(root.get("timeoutMs"), policy.defaultMcpTimeoutMs());
            int maxRequestBytes = integer(root.get("maxRequestBytes"),
                    policy.defaultMcpRequestBytes());
            int maxResponseBytes = integer(root.get("maxResponseBytes"),
                    policy.defaultMcpResponseBytes());
            int maxConcurrency = integer(root.get("maxConcurrency"), policy.defaultMcpConcurrency());
            int maxDiscoveredTools = integer(root.get("maxDiscoveredTools"),
                    policy.defaultMaxDiscoveredMcpToolsPerServer());
            Set<String> allowedTools = tools(root.get("allowedTools"));
            atMost("timeoutMs", timeoutMs, policy.maxMcpTimeoutMs());
            atMost("maxRequestBytes", maxRequestBytes, policy.maxMcpRequestBytes());
            atMost("maxResponseBytes", maxResponseBytes, policy.maxMcpResponseBytes());
            atMost("maxConcurrency", maxConcurrency, policy.maxMcpConcurrency());
            atMost("maxDiscoveredTools", maxDiscoveredTools,
                    policy.maxDiscoveredMcpToolsPerServer());
            atMost("allowedTools", allowedTools.size(), policy.maxMcpToolsPerServer());
            return Optional.of(new McpProfile(profileName,
                    new URI(text(root.get("endpoint"), null)),
                    credential,
                    timeoutMs, maxRequestBytes, maxResponseBytes, maxConcurrency,
                    maxDiscoveredTools, allowedTools));
        } catch (RuntimeException | java.net.URISyntaxException invalid) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static Set<String> tools(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("allowedTools");
        }
        var names = new LinkedHashSet<String>(list.size());
        for (Object element : list) {
            if (!(element instanceof String tool) || tool.length() > 128) {
                throw new IllegalArgumentException("allowedTools");
            }
            names.add(tool.strip());
        }
        // McpProfile decides whether each name is usable; this method only decides that the member
        // was a list of strings at all.
        return names;
    }

    /** {@code defaultValue} for an absent member; {@code null} as a default means the member is required. */
    private static String text(Object value, String defaultValue) {
        if (value == null) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("missing");
            }
            return defaultValue;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("text");
        }
        return text.strip();
    }

    private static int integer(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Long number) || number < 1 || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer");
        }
        return number.intValue();
    }

    private static void atMost(String field, int value, int ceiling) {
        if (value > ceiling) throw new IllegalArgumentException(field);
    }
}
