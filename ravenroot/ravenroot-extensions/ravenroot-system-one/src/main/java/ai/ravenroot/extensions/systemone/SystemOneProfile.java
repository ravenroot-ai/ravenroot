package ai.ravenroot.extensions.systemone;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.security.egress.ReservedNetworkPolicy.PlaintextAuthorization;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator-owned endpoint, model and protocol capability limits. */
public record SystemOneProfile(
        String name,
        URI endpoint,
        String model,
        Optional<OutboundCredentialBinding> credentialBinding,
        int timeoutMs,
        int maxRequestBytes,
        int maxResponseBytes,
        int maxConcurrency,
        int maxStateBytes,
        int maxQuestions,
        int maxInstructionBytes,
        int maxChoiceOptions,
        int maxScoreLevels,
        Set<String> supportedTypes,
        double probabilityTolerance,
        PlaintextAuthorization plaintextAuthorization) {

    public static final Set<String> PORTABLE_TYPES = Set.of("choice", "noul", "score");

    public SystemOneProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(credentialBinding, "credentialBinding");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("name");
        if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) throw new IllegalArgumentException("endpoint");
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("endpoint");
        int port = endpoint.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : endpoint.getPort();
        if (credentialBinding.isPresent() && scheme.equals("http")
                && (plaintextAuthorization == null || !plaintextAuthorization.matches(
                        "http", SystemOneNodePackage.ID, endpoint.getHost(), port))) {
            throw new IllegalArgumentException("endpoint");
        }
        if (model.isBlank() || model.length() > 256) throw new IllegalArgumentException("model");
        if (timeoutMs < 1 || timeoutMs > 600_000) throw new IllegalArgumentException("timeoutMs");
        if (maxRequestBytes < 1 || maxRequestBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("maxRequestBytes");
        if (maxResponseBytes < 1 || maxResponseBytes > 64 * 1024 * 1024) throw new IllegalArgumentException("maxResponseBytes");
        if (maxConcurrency < 1 || maxConcurrency > 1_024) throw new IllegalArgumentException("maxConcurrency");
        if (maxStateBytes < 1 || maxStateBytes > maxRequestBytes) throw new IllegalArgumentException("maxStateBytes");
        if (maxQuestions < 1 || maxQuestions > 1_000) throw new IllegalArgumentException("maxQuestions");
        if (maxInstructionBytes < 1 || maxInstructionBytes > maxRequestBytes) throw new IllegalArgumentException("maxInstructionBytes");
        if (maxChoiceOptions < 1 || maxChoiceOptions > 255) throw new IllegalArgumentException("maxChoiceOptions");
        if (maxScoreLevels < 2 || maxScoreLevels > 255) throw new IllegalArgumentException("maxScoreLevels");
        supportedTypes = supportedTypes == null ? Set.of() : supportedTypes.stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (supportedTypes.isEmpty() || !PORTABLE_TYPES.containsAll(supportedTypes)) {
            throw new IllegalArgumentException("supportedTypes");
        }
        if (!Double.isFinite(probabilityTolerance) || probabilityTolerance < 0.0
                || probabilityTolerance > 0.05) throw new IllegalArgumentException("probabilityTolerance");
    }
}
