package ai.ravenroot.api.ingress;

/**
 * Sanitized identity projection delivered only after HTTP authentication and authorization.
 * @param tenantId stable tenant id for this declaration.
 * @param subject authenticated subject identifier.
 * @param issuer identity-provider issuer identifier.
 * @param principalType authenticated principal classification.
 */
public record IngressPrincipal(String tenantId, String subject, String issuer, String principalType) {
/**
 * Rejects blank or unbounded identity fields before they reach package code.
 */
    public IngressPrincipal {
        tenantId = require(tenantId); subject = require(subject); issuer = require(issuer); principalType = require(principalType);
    }
    private static String require(String value) {
        if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException("identity value is invalid");
        return value;
    }
}
