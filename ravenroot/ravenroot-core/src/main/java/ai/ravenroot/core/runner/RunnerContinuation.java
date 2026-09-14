package ai.ravenroot.core.runner;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.payload.PayloadValue;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Version-one control-plane checkpoint. Identity is captured from ingress, never runner output. */
public record RunnerContinuation(SecurityContext security, Map<String, Object> attributes) {
    public static byte[] capture(NodeMessage message) {
        var identity = message.security();
        return PayloadJson.write(PayloadValue.fromJava(Map.of("request", identity.requestId(),
                "tenant", identity.tenantId(), "subject", identity.subject(), "issuer", identity.issuer(),
                "principalType", identity.principalType().name(), "attributes", message.attributes()),
                PayloadLimits.DEFAULTS)).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static RunnerContinuation decode(byte[] bytes) {
        Object value = PayloadJson.read(bytes, PayloadLimits.DEFAULTS).toJava();
        if (!(value instanceof Map<?, ?> map) || map.size() != 6 || !(map.get("attributes") instanceof Map<?, ?> attributes)) {
            throw new IllegalArgumentException("invalid runner continuation");
        }
        return new RunnerContinuation(new SecurityContext((String) map.get("request"), (String) map.get("tenant"),
                (String) map.get("subject"), PrincipalType.valueOf((String) map.get("principalType")),
                (String) map.get("issuer")), Map.copyOf((Map<String, Object>) attributes));
    }
}
