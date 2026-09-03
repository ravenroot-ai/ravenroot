package ai.ravenroot.api.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One tool call proposed by a node, presented to {@link ToolPolicy} for a decision.
 *
 * <p>{@code security} is the SEC-07 identity carried on the {@code NodeMessage} that reached the
 * node. Without it a {@link ToolPolicy} was structurally incapable of a tenant-aware decision — an
 * allowlist could only ever be global — so a tenant permitted one tool was permitted it everywhere.
 * The policy now receives who is calling, not merely what is being called.</p>
 *
 * <p>Note that {@code security} carries no roles or scopes by construction. Tool policy in this
 * revision decides on tenant and principal identity, not on authority; role-conditioned tool policy
 * would require widening {@link SecurityContext} and is deliberately not introduced here.</p>
 * @param security trusted identity projection for this request
 * @param executionId non-null execution requesting the tool
 * @param nodeId non-blank graph node requesting the tool
 * @param tool non-blank tool identifier
 * @param arguments immutable untrusted tool arguments
 */
public record ToolInvocation(SecurityContext security, UUID executionId, String nodeId, String tool,
                             Map<String, Object> arguments) {
    /**
     * Rejects missing identity, execution, node, or tool fields and snapshots arguments.
     */
    public ToolInvocation {
        Objects.requireNonNull(security, "security");
        if (executionId == null) throw new IllegalArgumentException("executionId cannot be null");
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
        if (tool == null || tool.isBlank()) throw new IllegalArgumentException("tool cannot be blank");
        arguments = arguments == null ? Map.of() : immutableMap(arguments);
    }

    /**
     * The tenant on whose behalf the tool would run.
     * @return tenant selected by the trusted security context
     */
    public String tenantId() {
        return security.tenantId();
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source) {
        var copied = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("argument keys must be strings");
            }
            copied.put(key, immutable(entry.getValue()));
        }
        return Collections.unmodifiableMap(copied);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            return immutableMap(map);
        }
        if (value instanceof List<?> list) {
            var copied = new ArrayList<Object>(list.size());
            for (Object element : list) copied.add(immutable(element));
            return Collections.unmodifiableList(copied);
        }
        return value;
    }
}
