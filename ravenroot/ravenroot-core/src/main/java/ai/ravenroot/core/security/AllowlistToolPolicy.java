package ai.ravenroot.core.security;

import ai.ravenroot.api.security.ToolDecision;
import ai.ravenroot.api.security.ToolInvocation;
import ai.ravenroot.api.security.ToolPolicy;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** Small policy adapter used by the standalone server; enterprise hosts may replace it. */
public final class AllowlistToolPolicy implements ToolPolicy {
    private final Set<String> allowed;

    public AllowlistToolPolicy(Collection<String> allowed) {
        this.allowed = allowed == null ? Set.of() : allowed.stream().map(String::trim)
                .filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    public static AllowlistToolPolicy fromCommaSeparated(String value) {
        return new AllowlistToolPolicy(value == null ? Set.of() : java.util.Arrays.asList(value.split(",")));
    }

    @Override
    public ToolDecision evaluate(ToolInvocation invocation) {
        if (allowed.contains(invocation.tool())) {
            return new ToolDecision(ToolDecision.Disposition.ALLOW, "Tool is explicitly allowlisted", "");
        }
        return new ToolDecision(ToolDecision.Disposition.DENY, "Tool is not allowlisted: " + invocation.tool(), "");
    }
}
