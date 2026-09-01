package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadLimits;
import ai.ravenroot.api.security.EnvironmentKeyCodec;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads one profile per environment variable, as strict canonical Base64 of a small JSON document.
 *
 * <p>Variable name: {@code RAVENROOT_LLM_PROFILE_<hex(profileName)>}, derived through
 * {@link EnvironmentKeyCodec} — the one injective derivation every resolver in this repository uses,
 * so two distinct profile names can never collide onto one variable.</p>
 *
 * <p>Document, with only {@code endpoint} and {@code model} required:</p>
 * <pre>{@code
 * {
 *   "endpoint": "http://127.0.0.1:8000/v1/chat/completions",
 *   "model": "qwen38",
 *   "credentialBindingId": "llm",
 *   "credentialReference": "llm-key",
 *   "timeoutMs": 120000,
 *   "maxResponseBytes": 8388608,
 *   "maxConcurrency": 4,
 *   "systemPreamble": "You are running inside an operator-governed workflow engine."
 * }
 * }</pre>
 *
 * <p><b>Malformed reads as absent, deliberately, and unlike a startup service grant.</b> A grant is
 * read once at startup, so a typo there must stop the process rather than silently become "no
 * capability". A profile is read per execution and its absence refuses exactly the node that names
 * it, with the profile name in front of the operator — so answering {@link Optional#empty()} points
 * at the right thing, and is what {@code EnvironmentOpenApiClientProfileResolver} and
 * {@code EnvironmentStorageProfileResolver} already do for the same reason.</p>
 */
public final class EnvironmentLlmProfileResolver implements LlmProfileResolver {

    /** Prefix of the one variable family this class reads. The suffix is {@code hex(profileName)}. */
    public static final String VARIABLE_PREFIX = "RAVENROOT_LLM_PROFILE_";

    private static final int MAX_PROFILE_BYTES = 8 * 1024;
    private static final PayloadLimits LIMITS = new PayloadLimits(MAX_PROFILE_BYTES, 8, 32, 256, 2048, 64);
    private static final Set<String> FIELDS = Set.of("endpoint", "model", "credentialBindingId",
            "credentialReference", "timeoutMs", "maxResponseBytes", "maxConcurrency",
            "systemPreamble");
    /** Same ASCII mask every other profile resolver applies before deriving a variable name. */
    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

    private final Map<String, String> environment;

    public EnvironmentLlmProfileResolver() {
        this(System.getenv());
    }

    EnvironmentLlmProfileResolver(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    /** The exact variable an operator must set to declare {@code profileName}. */
    public static String environmentVariableName(String profileName) {
        return VARIABLE_PREFIX + EnvironmentKeyCodec.hex(profileName);
    }

    @Override
    public Optional<LlmProfile> resolve(String profileName) {
        if (profileName == null || !profileName.matches(NAME_PATTERN)) {
            return Optional.empty();
        }
        try {
            String encoded = environment.get(environmentVariableName(profileName));
            if (encoded == null || encoded.isBlank() || encoded.length() > MAX_PROFILE_BYTES * 2) {
                return Optional.empty();
            }
            byte[] json = Base64.getDecoder().decode(encoded);
            // Canonical, not merely decodable: a value that decodes but does not re-encode to itself
            // is a different string that happens to share a decoding, and accepting it would make two
            // spellings of one variable mean the same profile.
            if (!Base64.getEncoder().encodeToString(json).equals(encoded)) {
                return Optional.empty();
            }
            Object read = PayloadJson.read(json, LIMITS).toJava();
            if (!(read instanceof Map<?, ?> raw)) {
                return Optional.empty();
            }
            Map<String, Object> root = cast(raw);
            // Unknown members are refused rather than ignored: a mistyped key must be a refusal an
            // operator can see, not a silently narrower profile.
            if (!FIELDS.containsAll(root.keySet())) {
                return Optional.empty();
            }
            String bindingId = text(root.get("credentialBindingId"), "");
            String reference = text(root.get("credentialReference"), "");
            Optional<OutboundCredentialBinding> credential = bindingId.isEmpty() && reference.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new OutboundCredentialBinding(bindingId, reference));
            return Optional.of(new LlmProfile(profileName,
                    new URI(text(root.get("endpoint"), null)),
                    text(root.get("model"), null),
                    credential,
                    integer(root.get("timeoutMs"), 60_000),
                    integer(root.get("maxResponseBytes"), LlmProfile.HARD_MAX_RESPONSE_BYTES),
                    integer(root.get("maxConcurrency"), 4),
                    text(root.get("systemPreamble"), "")));
        } catch (RuntimeException | java.net.URISyntaxException invalid) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    /** {@code defaultValue} for an absent member; {@code null} as a default means the member is required. */
    private static String text(Object value, String defaultValue) {
        if (value == null) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("missing");
            }
            return defaultValue;
        }
        if (!(value instanceof String text) || text.length() > 2048) {
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
}
