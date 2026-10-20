package ai.ravenroot.server.authoring;

import java.util.Map;
import java.util.Objects;

/**
 * Default-off provider delivery authentication; material remains behind the credential resolver.
 */
public record GraphAuthoringWebhookConfiguration(
        String credentialReference, Authentication authentication) {
    public enum Authentication {
        SIGNATURE,
        TOKEN
    }

    public GraphAuthoringWebhookConfiguration {
        credentialReference =
                Objects.requireNonNull(credentialReference, "credentialReference").trim();
        Objects.requireNonNull(authentication, "authentication");
        if (credentialReference.length() > 512
                || credentialReference.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid authoring webhook credential reference");
    }

    public static GraphAuthoringWebhookConfiguration disabled() {
        return new GraphAuthoringWebhookConfiguration("", Authentication.SIGNATURE);
    }

    public boolean enabled() {
        return !credentialReference.isEmpty();
    }

    public static GraphAuthoringWebhookConfiguration fromEnvironment(
            Map<String, String> environment) {
        String reference =
                environment.getOrDefault("RAVENROOT_GRAPH_AUTHORING_WEBHOOK_CREDENTIAL_REF", "");
        if (reference.isBlank()) return disabled();
        String selection =
                environment
                        .getOrDefault("RAVENROOT_GRAPH_AUTHORING_WEBHOOK_AUTH", "SIGNATURE")
                        .trim()
                        .toUpperCase(java.util.Locale.ROOT);
        Authentication authentication =
                switch (selection) {
                    case "SIGNATURE" -> Authentication.SIGNATURE;
                    case "TOKEN" -> Authentication.TOKEN;
                    default ->
                            throw new IllegalArgumentException(
                                    "RAVENROOT_GRAPH_AUTHORING_WEBHOOK_AUTH must be SIGNATURE or"
                                        + " TOKEN");
                };
        return new GraphAuthoringWebhookConfiguration(reference, authentication);
    }
}
