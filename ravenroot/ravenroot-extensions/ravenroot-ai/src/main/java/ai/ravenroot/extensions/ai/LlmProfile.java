package ai.ravenroot.extensions.ai;

import ai.ravenroot.api.node.service.OutboundCredentialBinding;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One operator-owned model endpoint, named by a graph and never described by one.
 *
 * <p>The graph supplies a profile <em>name</em>; everything that decides where bytes go and which
 * credential travels with them is read from the operator's environment. That is the same split every
 * other bundle in this tree uses (Telegram, OpenAPI client, object storage, JDBC), and it is the one
 * that survives the question "can a graph author reach an endpoint the operator did not choose?" —
 * they cannot, because no field of this record is reachable from graph content.</p>
 *
 * <p>The endpoint is nonetheless <em>also</em> confined by the managed channel's own origin
 * allowlist, granted per package in {@code RAVENROOT_NODE_PACKAGE_SERVICES_<hex(packageId)>}. A
 * profile naming an origin the grant omits fails at the call, not here: this record is a statement of
 * intent and the grant is the authority, and it is right that the authority is the thing that
 * refuses.</p>
 *
 * @param name the profile name a graph writes into the node's {@code provider} property
 * @param endpoint absolute chat-completions URL, e.g. {@code http://127.0.0.1:8000/v1/chat/completions}
 * @param model model identifier sent on the wire when the node does not override it
 * @param credentialBinding binding the runtime uses to place the key, or empty for an unauthenticated
 *                          endpoint such as a local llama.cpp
 * @param timeoutMs deadline for one completion, in milliseconds
 * @param maxResponseBytes ceiling this bundle reads a response against
 * @param maxConcurrency completions this profile admits at once, per tenant
 * @param systemPreamble the operator's own opening of the system turn, or the empty string
 */
public record LlmProfile(String name, URI endpoint, String model,
                         Optional<OutboundCredentialBinding> credentialBinding,
                         int timeoutMs, int maxRequestBytes, int maxResponseBytes, int maxConcurrency,
                         String systemPreamble) {

    public LlmProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(credentialBinding, "credentialBinding");
        if (!endpoint.isAbsolute() || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("endpoint");
        }
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("endpoint");
        }
        // A credential may only be placed on an encrypted origin. The managed channel already refuses
        // to place one on a plaintext origin -- EnvironmentNodePackageServiceGrants requires an
        // https/wss origin for every credential binding it accepts -- and repeating the rule here
        // turns the refusal from "your call failed" into "your profile is wrong", at the point where
        // the operator can still see which profile they wrote.
        if (credentialBinding.isPresent() && !scheme.equals("https")) {
            throw new IllegalArgumentException("credentialBinding");
        }
        if (model.isBlank() || model.length() > 256) {
            throw new IllegalArgumentException("model");
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs");
        }
        if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("maxRequestBytes");
        }
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency");
        }
        systemPreamble = systemPreamble == null ? "" : systemPreamble;
    }

    /**
     * A profile with no operator preamble, which is every profile written before the {@code agent}
     * node existed.
     *
     * <p>Kept as a real constructor rather than left to callers to pass {@code ""}: a profile that
     * says nothing about the system turn is the normal case, and making every call site state its
     * silence would read as if the silence were a choice each of them made.</p>
     */
    public LlmProfile(String name, URI endpoint, String model,
                      Optional<OutboundCredentialBinding> credentialBinding,
                      int timeoutMs, int maxResponseBytes, int maxConcurrency) {
        this(name, endpoint, model, credentialBinding, timeoutMs, maxResponseBytes,
                maxResponseBytes, maxConcurrency, "");
    }

    /** Compatibility constructor preserving the pre-request-limit shape with a system preamble. */
    public LlmProfile(String name, URI endpoint, String model,
                      Optional<OutboundCredentialBinding> credentialBinding,
                      int timeoutMs, int maxResponseBytes, int maxConcurrency,
                      String systemPreamble) {
        this(name, endpoint, model, credentialBinding, timeoutMs, maxResponseBytes,
                maxResponseBytes, maxConcurrency, systemPreamble);
    }
}
